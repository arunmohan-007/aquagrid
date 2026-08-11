package com.aquagrid.platform.iot.simulator;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;

/**
 * Probabilistic fault injection.
 *
 * <p>Real distribution networks are mostly healthy, with occasional leaks, rarer bursts, and
 * intermittent comms gaps. This engine rolls those probabilities per meter per tick, so a simulated
 * fleet produces the kind of traffic the alarm and NRW modules must learn to sift signal from noise
 * in.
 *
 * <p>Probabilities are per <em>simulated hour</em> and scaled by the elapsed interval, so they are
 * independent of the reporting rate: shortening the interval to watch a dashboard more closely makes
 * the fleet report more often, not break more often. Set every rate to zero and the fleet becomes
 * deterministic — the mode a validation run wants, where the only anomalies present are the ones
 * deliberately injected.
 */
public final class FaultScenarioEngine {

    private final Random rng;
    private final SimulatorProperties.FaultRates rates;

    public FaultScenarioEngine(SimulatorProperties.FaultRates rates, long seed) {
        this.rates = rates;
        this.rng = new Random(seed);
    }

    /** Rolls the fault dice for one meter over the elapsed interval and applies any outcome. */
    public void maybeInjectFaults(SimulatedMeter meter, Instant now, Duration interval) {
        double hours = interval.toMinutes() / 60.0;

        if (!meter.isLeaking() && rolls(rates.leak(), hours)) {
            // A slow leak: 0.2–1.0 L/min, persistent. This is the signature MNF anomaly.
            meter.startLeak(0.2 + rng.nextDouble() * 0.8);
        }
        if (rolls(rates.burst(), hours)) {
            // A burst: 8–20 L/min for 20–90 minutes. Loud, short, ends as the main is shut.
            meter.triggerBurst(now, Duration.ofMinutes(20 + rng.nextInt(70)),
                    8 + rng.nextDouble() * 12);
        }
        if (rolls(rates.commsLoss(), hours)) {
            // Comms loss: 5–60 minutes. Models gateway outage, deep-indoor fade, or PSM overrun.
            meter.loseComms(now, Duration.ofMinutes(5 + rng.nextInt(55)));
        }
        // Tamper is rare and sticky: once flagged it stays flagged (a magnet, or removal).
        if (!meter.isTampered() && rolls(rates.tamper(), hours)) {
            meter.setTampered(true);
        }
    }

    /**
     * Applies a named fault immediately, with representative magnitudes.
     *
     * <p>The magnitudes are the engine's, not the caller's, on purpose: an operator validating an
     * alarm should be reproducing the fault the platform will actually see, and a request body that
     * could specify 900 L/min would be testing the threshold against a number no main produces.
     */
    public void inject(SimulatedMeter meter, SimulatedFault fault, Instant now) {
        switch (fault) {
            case LEAK -> meter.startLeak(0.6);
            case BURST -> meter.triggerBurst(now, Duration.ofMinutes(45), 14);
            case COMMS_LOSS -> meter.loseComms(now, Duration.ofMinutes(30));
            case TAMPER -> meter.setTampered(true);
            case REVERSE_FLOW -> meter.setReverseFlow(true);
            case BATTERY_CRITICAL -> meter.depleteBattery();
            case HEALTHY -> meter.recover();
        }
    }

    private boolean rolls(double perHour, double hours) {
        return perHour > 0 && rng.nextDouble() < perHour * hours;
    }
}

package com.aquagrid.platform.iot.simulator;

import com.aquagrid.platform.iot.receiver.api.ReceiverGateway;
import com.aquagrid.platform.iot.receiver.api.ReceptionOutcome;
import com.aquagrid.platform.iot.receiver.application.security.IpAllowListService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The fleet simulator — Module 17.
 *
 * <p>Generates realistic water-meter traffic for registered devices marked
 * {@link com.aquagrid.platform.iot.domain.model.DeviceSource#SIMULATOR}, so the platform can be
 * inspected and validated end to end without a single meter in the ground: demand curves the NRW
 * analytics are built to read, faults the alarm rules are built to catch, and radio quality the
 * device-health views are built to plot.
 *
 * <p><b>It emits through {@link ReceiverGateway}, not through the ingest port.</b> That is the
 * decision the whole module rests on. The receiver is the single entry point every real transport
 * delivers to, and everything the platform guarantees about inbound telemetry — authentication,
 * device resolution, validation, replay rejection, the packet log, the metrics, the dead-letter
 * queue — is guaranteed there and nowhere else. A simulator wired to the ingest port would be a
 * second ingestion path: cheaper to write, and worth nothing as a test, because the traffic it
 * produced would have skipped every control the traffic it imitates must pass. It would also be the
 * exact hole {@code ReceiverGateway}'s contract exists to forbid.
 *
 * <p>The consequence is that a simulated packet can be <em>rejected</em>, and that is a feature. A
 * simulator-source device with a duplicate address, a decommissioned status or a malformed
 * registration fails here in the same way and with the same error code a real meter would — which
 * is how a registration mistake is found before a thousand real meters are commissioned against it.
 *
 * <h2>Replacement by physical devices</h2>
 *
 * <p>The module is built so a simulated device can be swapped for the real thing with no change to
 * any other part of the platform — no code, no migration, no API, dashboard, GIS or business-logic
 * edit. It shares the device registry, the device and asset ids, the communication profiles, the
 * provisioning, the credentials, the receiver pipeline, the payload formats and the database tables
 * with live traffic, because it uses those things rather than imitating them. The cutover is:
 *
 * <ol>
 *   <li>register the physical device, or reuse the existing row;</li>
 *   <li>set its {@code source} from {@code SIMULATOR} to {@code LIVE};</li>
 *   <li>configure its communication settings as usual;</li>
 *   <li>silence its virtual twin — {@link #suspend} for immediate effect, though step 2 already
 *       removes it from the fleet at the next refresh;</li>
 *   <li>the physical device's uplinks resolve through the same row and land in the same tables.</li>
 * </ol>
 *
 * <p>Nothing downstream is told which happened. A packet the simulator emits carries no marker of
 * any kind: the sole record that a reading was synthetic is {@code DeviceSource} on the device row,
 * which is what lets a fleet be replaced a few meters at a time, with simulated and live devices
 * reporting side by side for as long as the rollout takes.
 *
 * <p>Activation: {@code aquagrid.iot.transports.simulator=true}. Off by default, and
 * {@code ProductionReadinessVerifier} refuses to boot a production profile with it on.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "aquagrid.iot.transports", name = "simulator", havingValue = "true")
public class DeviceSimulator {

    /**
     * Upper bound on a manual {@code step}.
     *
     * <p>A step runs its ticks synchronously on the calling request thread, and each tick drives the
     * whole fleet through the receiver. Unbounded, one request could hold a servlet thread for
     * minutes and write hundreds of thousands of readings — so the bound is on the endpoint's cost,
     * not on the simulation's realism.
     */
    private static final int MAX_STEP_TICKS = 60;

    private final SimulatorFleet fleet;
    private final UplinkEncoder encoder;
    private final ReceiverGateway receiver;
    private final SimulatorProperties properties;
    private final IpAllowListService ipAllowList;

    private final DiurnalDemandModel demandModel = new DiurnalDemandModel();
    private final Map<UUID, DeviceOutcome> lastOutcomes = new ConcurrentHashMap<>();

    private final AtomicLong emitted = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong suppressed = new AtomicLong();

    private FaultScenarioEngine faults;
    private volatile State state = State.PAUSED;
    private volatile Instant lastTickAt;
    private volatile long lastTickMillis;

    /**
     * Whether the simulator is currently emitting.
     *
     * <p>Two states, not three. "Stopped" and "paused" would differ only in whether the fleet stays
     * loaded, and a simulator that discarded meter state on stop would lose every injected fault and
     * reset every register — so the distinction would exist only to offer the destructive option.
     */
    public enum State {
        RUNNING,
        PAUSED
    }

    /** What the receiver did with a device's most recent uplink. */
    public record DeviceOutcome(String status, String errorCode, String detail, Instant at) {
    }

    @PostConstruct
    void init() {
        faults = new FaultScenarioEngine(properties.faults(), System.currentTimeMillis());

        // Loopback is where simulated packets genuinely originate. If the operator narrowed the
        // receiver's allow-list without including it, every simulated packet will be refused at the
        // gate — correctly, and confusingly, so it is said once here rather than discovered in a
        // packet log an hour later.
        if (ipAllowList.isEnforced() && !ipAllowList.isAllowed(UplinkEncoder.SOURCE_IP)) {
            log.warn("Receiver IP allow-list excludes {} — every simulated packet will be rejected "
                    + "as IP-not-allowed. Add 127.0.0.1/32 to aquagrid.iot.receiver.security"
                    + ".ip-allow-list, or clear the list.", UplinkEncoder.SOURCE_IP);
        }

        int size;
        try {
            size = fleet.reload();
        } catch (RuntimeException e) {
            // Loud, but not fatal. The simulator is a development tool, and the usual cause here is
            // a tenant that has not been seeded yet — refusing to start the entire application over
            // that would block the very work the operator enabled it to do. It stays paused with an
            // empty fleet; /simulator/reload retries and reports the real reason to the caller.
            log.error("Device simulator could not load its fleet and will stay paused: {}",
                    e.getMessage());
            state = State.PAUSED;
            return;
        }

        state = properties.autoStart() ? State.RUNNING : State.PAUSED;
        log.info("Device simulator initialised: {} registered simulator-source meter(s), "
                        + "interval {}, state {}", size, properties.interval(), state);
        if (size == 0) {
            log.info("No devices to simulate. Register a device with source=SIMULATOR and the "
                    + "identity field its transport requires, then POST /api/v1/simulator/reload.");
        }
    }

    /**
     * One simulation tick.
     *
     * <p>{@code fixedDelay} rather than {@code fixedRate}, so a slow tick cannot pile up overlapping
     * runs: if ingestion stalls, the next tick waits for the previous one to finish rather than
     * queueing a second pass over the whole fleet behind it.
     */
    @Scheduled(fixedDelayString = "${aquagrid.iot.simulator.interval:1m}",
            initialDelayString = "${aquagrid.iot.simulator.initial-delay:15s}")
    public void scheduledTick() {
        if (state != State.RUNNING) {
            return;
        }
        if (fleet.isStale()) {
            fleet.reload();
        }
        tick(Instant.now());
    }

    /**
     * Drives every meter once and delivers the result to the receiver.
     *
     * <p>Synchronised against a concurrent manual step: two passes interleaving would advance the
     * same meter's register twice for one interval, which is the one arithmetic error a consumption
     * simulator must not make.
     */
    synchronized void tick(Instant now) {
        long startedAt = System.nanoTime();
        int sent = 0;
        int silent = 0;

        for (SimulatedMeter meter : fleet.meters()) {
            // A meter an engineer has silenced for cutover is skipped entirely: not ticked, not
            // integrated, not counted as suppressed. The physical device at that address is the one
            // reporting now, and advancing the virtual register alongside it would corrupt the very
            // consumption series the handover is meant to leave intact.
            if (meter.isSuspended() || !meter.isDue(now)) {
                continue;
            }
            faults.maybeInjectFaults(meter, now, meter.reportingInterval());
            Double flow = meter.emit(now, demandModel);
            if (flow == null) {
                silent++;
                continue;
            }
            record(meter, receiver.receive(encoder.encodeUplink(meter, flow, now)), now);
            sent++;
        }

        emitted.addAndGet(sent);
        suppressed.addAndGet(silent);
        lastTickAt = now;
        lastTickMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        log.debug("Simulator tick: {} uplink(s) emitted, {} suppressed by comms loss, {} ms",
                sent, silent, lastTickMillis);
    }

    /**
     * Tallies what the receiver decided, per device and in total.
     *
     * <p>Recorded rather than assumed. The simulator's own view of "I sent 500 uplinks" is worth
     * very little on its own — the number an operator needs is how many of them the platform
     * actually accepted, and the gap between the two is where a registration or configuration fault
     * shows up.
     */
    private void record(SimulatedMeter meter, ReceptionOutcome outcome, Instant now) {
        switch (outcome) {
            case ReceptionOutcome.Accepted ignored -> {
                accepted.incrementAndGet();
                lastOutcomes.put(meter.deviceId(),
                        new DeviceOutcome("ACCEPTED", null, null, now));
            }
            case ReceptionOutcome.Duplicate ignored -> {
                duplicates.incrementAndGet();
                lastOutcomes.put(meter.deviceId(),
                        new DeviceOutcome("DUPLICATE", null, null, now));
            }
            case ReceptionOutcome.Rejected refusal -> {
                rejected.incrementAndGet();
                lastOutcomes.put(meter.deviceId(), new DeviceOutcome(
                        "REJECTED", refusal.code().name(), refusal.detail(), now));
                // Warn, not debug. A refused simulated packet is never ordinary traffic — nothing is
                // spoofing the simulator — so it always means the fleet is misconfigured, and it is
                // exactly the finding a validation run is there to surface.
                log.warn("Simulated uplink from {} rejected: {} — {}",
                        meter.deviceCode(), refusal.code(), refusal.detail());
            }
        }
    }

    // ---- Control ---------------------------------------------------------------------------------

    /** Begins (or resumes) emission, reloading the fleet first so a just-registered device joins. */
    public State start() {
        fleet.reload();
        state = State.RUNNING;
        log.info("Device simulator started: {} meter(s)", fleet.size());
        return state;
    }

    /** Stops emission. The fleet and every meter's state — register, battery, faults — are kept. */
    public State pause() {
        state = State.PAUSED;
        log.info("Device simulator paused after {} uplink(s)", emitted.get());
        return state;
    }

    /** Re-reads the fleet from the device registry. */
    public int reloadFleet() {
        return fleet.reload();
    }

    /**
     * Runs {@code ticks} simulation intervals immediately, whatever the run state.
     *
     * <p>The endpoint a validation exercise actually uses. Waiting a real minute per simulated
     * minute makes any assertion about a trend — a leak crossing an MNF threshold, a battery
     * reaching its alarm — a test that takes hours. Stepping compresses the simulated clock while
     * every packet still travels the full production path.
     *
     * @return how many ticks were run, after clamping
     */
    public int step(int ticks) {
        int bounded = Math.clamp(ticks, 1, MAX_STEP_TICKS);
        Instant now = Instant.now();
        Duration interval = properties.interval();
        for (int i = 0; i < bounded; i++) {
            // Each step advances the simulated clock by one interval, so the demand curve, the fault
            // windows and the readings' timestamps all move together. Stepping at a single instant
            // would produce N readings sharing one timestamp — which the receiver's replay
            // protection would rightly collapse into one accepted packet and N-1 duplicates.
            tick(now.plus(interval.multipliedBy(i)));
        }
        return bounded;
    }

    /**
     * Puts one meter into a named fault state.
     *
     * @return empty when the device is not in the fleet — not registered as simulator-source, or
     *         registered without an address and therefore not driveable
     */
    public Optional<SimulatedFault> inject(UUID deviceId, SimulatedFault fault) {
        return fleet.meter(deviceId).map(meter -> {
            faults.inject(meter, fault, Instant.now());
            log.info("Injected {} into simulated meter {}", fault, meter.deviceCode());
            return fault;
        });
    }

    /**
     * Silences or resumes one meter, leaving the rest of the fleet running.
     *
     * <p>The immediate half of a per-device cutover, and the step that makes a <em>gradual</em>
     * replacement possible: a utility fits fifty real meters this month and ten thousand next year,
     * so the platform has to run a mixed fleet indefinitely rather than flip in one go. An engineer
     * commissioning a physical unit silences its virtual twin from here, confirms the real uplinks
     * are arriving, and only then sets the row's source to {@code LIVE} — which is the durable
     * control, and the one that survives a restart.
     *
     * @return empty when the device is not in the fleet
     */
    public Optional<Boolean> suspend(UUID deviceId, boolean suspended) {
        return fleet.meter(deviceId).map(meter -> {
            meter.setSuspended(suspended);
            log.info("Simulated meter {} {} — the durable control remains the device's source column",
                    meter.deviceCode(), suspended ? "silenced for cutover" : "resumed");
            return suspended;
        });
    }

    // ---- Status ----------------------------------------------------------------------------------

    public State state() {
        return state;
    }

    public Duration interval() {
        return properties.interval();
    }

    public int fleetSize() {
        return fleet.size();
    }

    public String organizationCode() {
        return fleet.organizationCode();
    }

    /** The tenant whose devices this simulator drives. The controller checks callers against it. */
    public UUID organizationId() {
        return fleet.organizationId();
    }

    /** Device codes registered as simulated but carrying no address, and so not driveable. */
    public List<String> unaddressable() {
        return fleet.unaddressable();
    }

    public Instant lastTickAt() {
        return lastTickAt;
    }

    public long lastTickMillis() {
        return lastTickMillis;
    }

    public Counters counters() {
        return new Counters(emitted.get(), accepted.get(), duplicates.get(), rejected.get(),
                suppressed.get());
    }

    /**
     * What the simulator sent and what became of it.
     *
     * @param emitted    uplinks handed to the receiver
     * @param accepted   ingested as telemetry
     * @param duplicates recognised as already ingested
     * @param rejected   refused. Non-zero always means a misconfigured fleet — see {@link #record}
     * @param suppressed ticks where a meter stayed silent under a comms-loss fault. Not a failure:
     *                   simulating an outage means sending nothing, and counting it separately is
     *                   what stops silence being mistaken for a stalled simulator
     */
    public record Counters(long emitted, long accepted, long duplicates, long rejected,
                           long suppressed) {
    }

    public Optional<DeviceOutcome> lastOutcome(UUID deviceId) {
        return Optional.ofNullable(lastOutcomes.get(deviceId));
    }

    public List<SimulatedMeter> meters() {
        return List.copyOf(fleet.meters());
    }

    @PreDestroy
    void shutdown() {
        state = State.PAUSED;
        log.info("Device simulator stopped after {} uplink(s): {} accepted, {} rejected",
                emitted.get(), accepted.get(), rejected.get());
    }
}

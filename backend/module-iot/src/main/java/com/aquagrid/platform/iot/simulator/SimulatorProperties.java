package com.aquagrid.platform.iot.simulator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * How the fleet simulator behaves once it is switched on.
 *
 * <p>Whether it runs at all is <b>not</b> here: that stays on {@code aquagrid.iot.transports
 * .simulator}, beside the real transports, because it is the same kind of decision — which sources
 * of telemetry this deployment loads — and because {@code ProductionReadinessVerifier} refuses to
 * boot a production profile with that flag set. Splitting the master switch away from the behaviour
 * knobs keeps the assertion reading one property rather than a nested block that could grow a second
 * way to be enabled.
 *
 * @param organizationCode which tenant's simulator-source devices to drive. The platform-operator
 *                         tenant by default; a customer code produces a demo populated with that
 *                         customer's own meters
 * @param interval         how often every meter reports. One minute is fast enough to see life on a
 *                         dashboard and slow enough to leave running for days
 * @param initialDelay     grace before the first tick, so the fleet loads after Flyway and the
 *                         receiver's own startup have finished competing for the pool
 * @param fleetRefresh     how often the fleet is re-read from the device registry. A device
 *                         registered while the simulator runs starts emitting within this window
 *                         rather than at the next restart
 * @param autoStart        whether ticking begins on its own. False leaves the simulator loaded but
 *                         idle, which is what a validation run wants: fleet visible, nothing written
 *                         until an operator says so
 * @param gatewayApiKey    the plaintext gateway key the simulator presents when a device carries no
 *                         credential of its own. It stands in for the network server or carrier
 *                         webhook that will deliver the physical devices' packets, and it is checked
 *                         against the same configured hash that gateway will be — so a fleet that
 *                         authenticates here authenticates after cutover. Empty means the simulator
 *                         presents no credential, and the receiver refuses it exactly as it would
 *                         refuse an uncredentialed real device
 * @param faults           the fault-injection rates — see {@link FaultRates}
 */
@Validated
@ConfigurationProperties(prefix = "aquagrid.iot.simulator")
public record SimulatorProperties(
        String organizationCode,
        Duration interval,
        Duration initialDelay,
        Duration fleetRefresh,
        boolean autoStart,
        String gatewayApiKey,
        FaultRates faults
) {

    public SimulatorProperties {
        organizationCode = organizationCode == null || organizationCode.isBlank()
                ? "SYSTEM" : organizationCode.trim();
        interval = interval == null || interval.isZero() ? Duration.ofMinutes(1) : interval;
        initialDelay = initialDelay == null ? Duration.ofSeconds(15) : initialDelay;
        fleetRefresh = fleetRefresh == null ? Duration.ofMinutes(5) : fleetRefresh;
        faults = faults == null ? FaultRates.defaults() : faults;
    }

    /**
     * Spontaneous fault probabilities, <b>per simulated hour</b> rather than per tick.
     *
     * <p>Per-hour is the unit that makes them meaningful. Expressed per tick, halving
     * {@code interval} to watch a dashboard more closely would double the leak rate — so the fleet
     * an engineer tuned an alarm threshold against would stop being the fleet they tested it on.
     *
     * <p>Every rate can be set to zero, which is how a deterministic run is obtained: a fleet with no
     * spontaneous faults produces exactly the demand curve, and any anomaly an analysis then reports
     * is one the operator injected on purpose. That is the mode a validation exercise wants.
     *
     * @param leak      a slow persistent addition to baseline flow — the signature MNF anomaly
     * @param burst     a sudden large flow that ends when the main is shut
     * @param commsLoss a window in which the meter reports nothing at all
     * @param tamper    magnet or removal. Sticky: once flagged it stays flagged until cleared
     */
    public record FaultRates(
            double leak,
            double burst,
            double commsLoss,
            double tamper
    ) {
        static FaultRates defaults() {
            return new FaultRates(0.002, 0.0002, 0.005, 0.0001);
        }
    }
}

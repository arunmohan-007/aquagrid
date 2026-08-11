package com.aquagrid.platform.iot.simulator;

/**
 * A condition the simulator can put a meter into on demand.
 *
 * <p>These are the same conditions {@link FaultScenarioEngine} rolls for spontaneously. Naming them
 * is what turns the simulator from a traffic generator into a test instrument: waiting for a 0.002
 * per-hour leak to appear so an alarm rule can be checked is not a test, it is a vigil. An operator
 * validating a threshold picks the meter, picks the fault, and watches the pipeline react.
 *
 * <p>The set is deliberately the set the platform can <em>detect</em>. A fault nothing downstream
 * reads would generate traffic and prove nothing, so each entry below corresponds to something the
 * alarm, NRW or device-health modules are built to find.
 */
public enum SimulatedFault {

    /**
     * A slow persistent leak added to baseline flow. The anomaly minimum-night-flow analysis exists
     * to find, and the one that is invisible in any single reading.
     */
    LEAK,

    /** A large flow for a bounded window — a main failure, loud and short. */
    BURST,

    /**
     * The meter stops transmitting. Deliberately not "the meter reports zero": silence and zero flow
     * are different faults, and a platform that conflates them cannot tell a burst pipe that took
     * the gateway out from a holiday home.
     */
    COMMS_LOSS,

    /** Magnet or enclosure removal. Sticky — it stays set until the meter is cleared. */
    TAMPER,

    /**
     * Flow measured in the wrong direction — backflow into the supply, a contamination risk and a
     * common signature of an incorrectly fitted meter.
     */
    REVERSE_FLOW,

    /**
     * Drives the cell to its replacement threshold immediately, rather than over the two years of
     * simulated uplinks it would otherwise take. The only way to exercise a battery alarm inside a
     * test run.
     */
    BATTERY_CRITICAL,

    /** Clears every active fault. The control that makes a scenario repeatable. */
    HEALTHY
}

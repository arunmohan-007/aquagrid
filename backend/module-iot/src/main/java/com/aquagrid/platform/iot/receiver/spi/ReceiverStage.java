package com.aquagrid.platform.iot.receiver.spi;

import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import org.springframework.core.Ordered;

/**
 * One link in the reception chain.
 *
 * <p>The pipeline is a chain of responsibility over these: authenticate, resolve the tenant, resolve
 * the communication profile, resolve the device, validate, parse, build the event, dispatch. Each
 * stage does one thing to the {@link ReceptionContext} and says whether the chain should go on.
 *
 * <p>Modelling it this way buys three specific things over an eight-call method. A stage can be
 * inserted (payload decryption, a per-tenant quota) by adding a bean with an order, with no edit to
 * any existing stage. The runner times and instruments every stage identically, so "which stage is
 * slow" is answerable without anyone having remembered to add a timer. And every stage halts the
 * same way — by recording a rejection — so there is exactly one place where a refusal turns into a
 * log row, a metric and a response, rather than eight places that must agree.
 *
 * <p>Order values are spaced in {@code Stages} so a new stage can be slotted between two existing
 * ones without renumbering them.
 */
public interface ReceiverStage extends Ordered {

    /** Stage name — the metric tag, the timing key and what a slow-stage log line names. */
    String name();

    /**
     * Runs this stage.
     *
     * @return {@link Decision#CONTINUE} to proceed. {@link Decision#HALT} to stop, having first
     *         recorded on the context <em>why</em> — a rejection, or that the packet is a duplicate
     */
    Decision execute(ReceptionContext context);

    enum Decision {
        CONTINUE,
        HALT
    }

    /**
     * Canonical stage ordering. Gaps of ten leave room to insert without renumbering.
     *
     * <p>The sequence is not arbitrary — each step is a precondition for the next, and two of the
     * orderings are load-bearing security properties:
     * <ul>
     *   <li>Authentication precedes everything, so an unauthenticated packet never reaches a
     *       database lookup and cannot be used to probe which device identifiers exist.</li>
     *   <li>Validation precedes parsing, so a payload is size- and signature-checked before any
     *       decoder is pointed at it.</li>
     * </ul>
     */
    final class Stages {
        public static final int AUTHENTICATION = 100;
        public static final int TENANT_RESOLUTION = 200;
        public static final int DEVICE_RESOLUTION = 400;

        /**
         * After device resolution, not before it.
         *
         * <p>This is the one place the implemented order departs from the nominal pipeline, and
         * deliberately. A communication profile is a property of the <em>device</em>, read from its
         * registration; the only thing available before the device is resolved is the transport
         * that delivered the packet, and the two are routinely different. ChirpStack delivers
         * LoRaWAN uplinks over an HTTP webhook — resolving the profile from the transport would
         * label them {@code HTTP}, point a JSON parser at a base64 LoRaWAN frame, and reject every
         * uplink from a working fleet.
         */
        public static final int COMMUNICATION_RESOLUTION = 450;

        public static final int VALIDATION = 500;
        public static final int PARSING = 600;

        /** Rules that can only be judged once there are readings to judge. */
        public static final int TELEMETRY_VALIDATION = 650;

        /**
         * Applies the device's data configuration to what was parsed, and notices what it does not
         * describe.
         *
         * <p>After validation, and it must stay there. This stage never halts — a field nobody
         * configured is normal operation, not a fault — so putting it earlier would only mean
         * running it for packets the chain was about to refuse anyway. Putting it later, after the
         * event is built, would mean the event could not carry the verdicts it produces.
         */
        public static final int PARAMETER_CONFIGURATION = 660;

        public static final int EVENT_BUILD = 700;
        public static final int DISPATCH = 800;

        private Stages() {
        }
    }
}

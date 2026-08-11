package com.aquagrid.platform.iot.receiver.spi;

import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.domain.model.Rejection;
import org.springframework.core.Ordered;

import java.util.Optional;

/**
 * One rule a packet must satisfy.
 *
 * <p>Split into separate beans rather than gathered into one validate method so that each rule can
 * be configured, disabled and tested on its own — a deployment behind a carrier VPN switches off
 * signature checking without touching size limits, and each rule's unit test needs only the field
 * it constrains.
 *
 * <p>Validators run in {@link Ordered} order and the first rejection stops the rest, so cheap
 * structural checks belong before expensive cryptographic ones. Size is checked before anything
 * parses the bytes; that ordering is what stops an oversized payload from being decoded at all.
 */
public interface PayloadValidator extends Ordered {

    String name();

    /**
     * When in the pipeline this rule can be evaluated.
     *
     * <p>Declared rather than inferred from whether the telemetry happens to be populated. A rule
     * that silently no-ops because it ran too early is a rule that stops enforcing anything the day
     * someone reorders the stages, and nothing fails — which is the worst way for a validation to
     * be lost.
     */
    default Phase phase() {
        return Phase.PRE_PARSE;
    }

    /** The two points at which a rule can apply. */
    enum Phase {
        /**
         * Before any decoder sees the payload. Structural rules belong here — size, mandatory
         * signature, device state — because their whole value is that they run before the receiver
         * does expensive work on attacker-chosen bytes.
         */
        PRE_PARSE,

        /** After decoding, when the readings themselves can be judged. */
        POST_PARSE
    }

    /**
     * @return empty when the rule is satisfied or does not apply, otherwise the reason to refuse.
     *         Returning a rejection rather than throwing keeps the common case allocation-free and
     *         makes "which rule refused this" a value the packet log can store
     */
    Optional<Rejection> validate(ReceptionContext context);
}

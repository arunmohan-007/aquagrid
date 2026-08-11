package com.aquagrid.platform.iot.receiver.application.pipeline;

import com.aquagrid.platform.iot.receiver.application.security.ReplayProtectionService;
import com.aquagrid.platform.iot.receiver.application.validator.PayloadValidatorChain;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.domain.model.Rejection;
import com.aquagrid.platform.iot.receiver.spi.PayloadValidator;
import com.aquagrid.platform.iot.receiver.spi.ReceiverStage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Judges the decoded readings, then claims the packet's identity.
 *
 * <p>Replay protection lives here rather than at the gate, and the placement is forced by what a
 * packet identity is made of. The strongest key is a frame counter, which only exists once the
 * payload has been decoded; the fallback is a hash narrowed by the <em>device's</em> timestamp,
 * which likewise comes out of the parser. Claiming an identity earlier would mean claiming it from
 * the raw bytes alone, which cannot tell two identical quiet-hour readings apart.
 *
 * <p>Sanity validation runs before the claim, deliberately. A packet refused as a decode fault must
 * not have burned its identity — otherwise fixing the parser and replaying the stored bytes would
 * find the claim already taken and discard the reading as a duplicate.
 *
 * <p>A duplicate halts the pipeline but is <b>not</b> a rejection. The wire must still be
 * acknowledged, or the network retransmits indefinitely; the packet simply must not be counted
 * twice.
 */
@Component
@RequiredArgsConstructor
public class TelemetryValidationStage implements ReceiverStage {

    private final PayloadValidatorChain validators;
    private final ReplayProtectionService replayProtection;

    @Override
    public String name() {
        return "TELEMETRY_VALIDATION";
    }

    @Override
    public Decision execute(ReceptionContext context) {
        Optional<Rejection> rejection =
                validators.validate(context, PayloadValidator.Phase.POST_PARSE);
        if (rejection.isPresent()) {
            context.reject(rejection.get());
            return Decision.HALT;
        }

        // Replaying a stored packet is an authorised act by a named operator, so it deliberately
        // bypasses the claim — the identity was already taken by the original delivery, and
        // refusing it here would make the dead-letter queue unable to do the one thing it exists for.
        if (context.getPacket().isReplay()) {
            context.note("replayBypass", true);
            return Decision.CONTINUE;
        }

        boolean fresh = replayProtection.claim(
                context.getDevice().getId(), context.getPacket(), context.getTelemetry());
        if (!fresh) {
            context.markDuplicate();
            return Decision.HALT;
        }
        return Decision.CONTINUE;
    }

    @Override
    public int getOrder() {
        return Stages.TELEMETRY_VALIDATION;
    }
}

package com.aquagrid.platform.iot.receiver.application.pipeline;

import com.aquagrid.platform.iot.receiver.application.validator.PayloadValidatorChain;
import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.domain.model.Rejection;
import com.aquagrid.platform.iot.receiver.spi.PayloadValidator;
import com.aquagrid.platform.iot.receiver.spi.ReceiverStage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Applies the structural rules — before any decoder is pointed at the payload.
 *
 * <p>That ordering is the whole value of the stage. Size limits, mandatory signatures and device
 * state are all cheap to evaluate and all decide whether the expensive, attacker-influenced work of
 * parsing should happen at all. A validation stage placed after parsing would be checking
 * constraints on data the receiver had already done the dangerous part of processing.
 */
@Component
@RequiredArgsConstructor
public class PayloadValidationStage implements ReceiverStage {

    private final PayloadValidatorChain validators;

    @Override
    public String name() {
        return "VALIDATION";
    }

    @Override
    public Decision execute(ReceptionContext context) {
        Optional<Rejection> rejection =
                validators.validate(context, PayloadValidator.Phase.PRE_PARSE);
        if (rejection.isPresent()) {
            context.reject(rejection.get());
            return Decision.HALT;
        }
        return Decision.CONTINUE;
    }

    @Override
    public int getOrder() {
        return Stages.VALIDATION;
    }
}

package com.aquagrid.platform.iot.receiver.application.validator;

import com.aquagrid.platform.iot.receiver.domain.model.ReceptionContext;
import com.aquagrid.platform.iot.receiver.domain.model.Rejection;
import com.aquagrid.platform.iot.receiver.spi.PayloadValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs the validation rules for one phase and returns the first refusal.
 *
 * <p>Fail-fast rather than collect-all. Ingestion is machine-to-machine: nothing at the other end
 * reads a list of everything wrong with a packet and corrects it, so gathering every violation
 * would only mean running the expensive rules after a cheap one had already settled the outcome.
 * The first reason is the one that goes on the packet log, and it is the one an operator acts on.
 *
 * <p>Rules are indexed by phase at construction, so the hot path does no filtering — a pre-parse
 * pass iterates only pre-parse rules.
 */
@Slf4j
@Service
public class PayloadValidatorChain {

    private final Map<PayloadValidator.Phase, List<PayloadValidator>> byPhase =
            new EnumMap<>(PayloadValidator.Phase.class);

    public PayloadValidatorChain(List<PayloadValidator> validators) {
        List<PayloadValidator> ordered = new ArrayList<>(validators);
        ordered.sort(AnnotationAwareOrderComparator.INSTANCE);
        for (PayloadValidator.Phase phase : PayloadValidator.Phase.values()) {
            byPhase.put(phase, ordered.stream().filter(v -> v.phase() == phase).toList());
        }
        log.info("Receiver validation rules: pre-parse={}, post-parse={}",
                names(PayloadValidator.Phase.PRE_PARSE),
                names(PayloadValidator.Phase.POST_PARSE));
    }

    public Optional<Rejection> validate(ReceptionContext context, PayloadValidator.Phase phase) {
        for (PayloadValidator validator : byPhase.getOrDefault(phase, List.of())) {
            Optional<Rejection> rejection = validator.validate(context);
            if (rejection.isPresent()) {
                context.note("failedValidator", validator.name());
                return rejection;
            }
        }
        return Optional.empty();
    }

    public List<String> names(PayloadValidator.Phase phase) {
        return byPhase.getOrDefault(phase, List.of()).stream()
                .map(PayloadValidator::name).toList();
    }
}

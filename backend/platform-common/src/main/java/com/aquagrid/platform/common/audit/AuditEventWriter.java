package com.aquagrid.platform.common.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Performs the actual audit write on a background thread, in its own transaction.
 *
 * <p>This is a separate bean from {@link AuditService} on purpose: {@code @Async} and
 * {@code @Transactional} are proxy-based, so a self-invocation inside a single bean would silently
 * bypass both and run the write inline on the caller's transaction — a defect that is invisible
 * until a rollback swallows the very audit rows an investigation needs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditEventWriter {

    private final AuditEventRepository repository;

    @Async("auditExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(AuditEvent event, String traceId) {
        try {
            repository.save(AuditEventEntity.builder()
                    .organizationId(event.organizationId())
                    .actorUserId(event.actorUserId())
                    .actorUsername(truncate(event.actorUsername(), 320))
                    .eventType(event.eventType())
                    .category(event.category())
                    .severity(event.severity())
                    .resourceType(event.resourceType())
                    .resourceId(truncate(event.resourceId(), 100))
                    .success(event.success())
                    .message(truncate(event.message(), 1000))
                    .clientIp(event.clientIp())
                    .userAgent(truncate(event.userAgent(), 512))
                    .traceId(traceId)
                    .metadata(event.metadata())
                    .createdAt(Instant.now())
                    .build());

            if (event.severity() == AuditSeverity.CRITICAL) {
                log.warn("SECURITY ALERT [{}] org={} actor={} ip={} — {}", event.eventType(),
                        event.organizationId(), event.actorUsername(), event.clientIp(),
                        event.message());
            }
        } catch (RuntimeException e) {
            // Never propagate: this runs detached from the business operation, which has completed.
            log.error("Failed to persist audit event {} for actor {}", event.eventType(),
                    event.actorUsername(), e);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}

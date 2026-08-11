package com.aquagrid.platform.iot.receiver.application.service;

import com.aquagrid.platform.iot.receiver.domain.model.ReceiverLog;
import com.aquagrid.platform.iot.receiver.infrastructure.persistence.ReceiverLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Records the receiver's own operational events.
 *
 * <p>Deliberately a different table from the packet log and a different method from
 * {@code log.info}. A listener failing to bind is the kind of event someone needs to find three
 * weeks later, correlated with a gap in the telemetry, and a log file has usually rotated by then —
 * whereas a per-packet table cannot be retained that long. This is the small, durable middle.
 *
 * <p>Never throws. An operational event that could not be recorded must not turn into a second
 * failure on top of the one it was describing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiverLogService {

    private final ReceiverLogRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void info(String eventType, String transport, String message,
                     Map<String, Object> details) {
        write(eventType, transport, "INFO", message, details, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void warn(String eventType, String transport, String message,
                     Map<String, Object> details) {
        write(eventType, transport, "WARN", message, details, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void error(String eventType, String transport, String message,
                      Map<String, Object> details) {
        write(eventType, transport, "ERROR", message, details, null, null);
    }

    /** For operator-initiated events, where who did it is part of the record. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void action(String eventType, String transport, String message,
                       Map<String, Object> details, UUID actorUserId, UUID organizationId) {
        write(eventType, transport, "INFO", message, details, actorUserId, organizationId);
    }

    private void write(String eventType, String transport, String severity, String message,
                       Map<String, Object> details, UUID actorUserId, UUID organizationId) {
        try {
            ReceiverLog entry = new ReceiverLog();
            entry.setEventType(eventType);
            entry.setTransport(transport);
            entry.setSeverity(severity);
            entry.setMessage(message == null || message.length() <= 1000
                    ? message : message.substring(0, 1000));
            entry.setDetails(details == null ? Map.of() : details);
            entry.setActorUserId(actorUserId);
            entry.setOrganizationId(organizationId);
            // Ties the row to the request that caused it, so a receiver event and the application
            // log lines around it can be read together.
            entry.setCorrelationId(MDC.get(com.aquagrid.platform.common.web.CorrelationIdFilter.TRACE_ID_KEY));
            repository.save(entry);
        } catch (RuntimeException e) {
            log.error("Failed to write receiver log entry {}", eventType, e);
        }
    }
}

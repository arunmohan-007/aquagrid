package com.aquagrid.platform.common.audit;

import com.aquagrid.platform.common.web.CorrelationIdFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * The single entry point for writing the audit trail.
 *
 * <p>Modules depend on this facade, never on the repository, so retention, partitioning and the
 * tamper-evident hash chain introduced in Module 30 remain one concern in one place.
 *
 * <p>The trace id is captured here, on the calling thread, because the SLF4J MDC does not propagate
 * across the executor hand-off performed by {@link AuditEventWriter}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditEventWriter writer;

    public void record(AuditEvent event) {
        writer.write(event, MDC.get(CorrelationIdFilter.TRACE_ID_KEY));
    }
}

package com.aquagrid.platform.common.audit;

import lombok.Builder;

import java.util.Map;
import java.util.UUID;

/**
 * An immutable description of something that happened and must be provable later.
 *
 * <p>Modules construct these through {@link #builder()} and hand them to {@link AuditService}.
 * They never write to the audit table directly, so retention, partitioning and (from Module 30)
 * hash-chaining remain a single concern.
 */
@Builder
public record AuditEvent(
        UUID organizationId,
        UUID actorUserId,
        String actorUsername,
        String eventType,
        AuditCategory category,
        AuditSeverity severity,
        String resourceType,
        String resourceId,
        boolean success,
        String message,
        String clientIp,
        String userAgent,
        Map<String, Object> metadata
) {
    public AuditEvent {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        category = category == null ? AuditCategory.SYSTEM : category;
        severity = severity == null ? AuditSeverity.INFO : severity;
    }
}

package com.aquagrid.platform.common.audit;

/** Operational severity of an audit event; {@code CRITICAL} events also raise a security alert. */
public enum AuditSeverity {
    INFO,
    WARNING,
    CRITICAL
}

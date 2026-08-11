package com.aquagrid.platform.common.audit;

/** Top-level classification of an audit event, used for retention policy and filtering. */
public enum AuditCategory {
    AUTHENTICATION,
    AUTHORIZATION,
    DATA,
    CONFIGURATION,
    SECURITY,
    SYSTEM
}

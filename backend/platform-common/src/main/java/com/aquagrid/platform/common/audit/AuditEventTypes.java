package com.aquagrid.platform.common.audit;

/**
 * Canonical audit event type constants.
 *
 * <p>String constants rather than an enum: modules add their own types without recompiling the
 * kernel, and historical rows keep meaning after a type is retired. The naming convention is
 * {@code DOMAIN_ACTION_OUTCOME}.
 */
public final class AuditEventTypes {

    // Authentication
    public static final String LOGIN_SUCCESS = "AUTH_LOGIN_SUCCESS";
    public static final String LOGIN_FAILURE = "AUTH_LOGIN_FAILURE";
    public static final String LOGIN_BLOCKED_LOCKED = "AUTH_LOGIN_BLOCKED_LOCKED";
    public static final String LOGIN_BLOCKED_RATE_LIMIT = "AUTH_LOGIN_BLOCKED_RATE_LIMIT";
    public static final String ACCOUNT_LOCKED = "AUTH_ACCOUNT_LOCKED";
    public static final String LOGOUT = "AUTH_LOGOUT";
    public static final String LOGOUT_ALL = "AUTH_LOGOUT_ALL";

    // Tokens
    public static final String TOKEN_REFRESHED = "AUTH_TOKEN_REFRESHED";
    public static final String TOKEN_REUSE_DETECTED = "AUTH_TOKEN_REUSE_DETECTED";
    public static final String SESSION_REVOKED = "AUTH_SESSION_REVOKED";

    // Multi-factor
    public static final String MFA_CHALLENGE_SUCCESS = "AUTH_MFA_CHALLENGE_SUCCESS";
    public static final String MFA_CHALLENGE_FAILURE = "AUTH_MFA_CHALLENGE_FAILURE";
    public static final String MFA_ENROLLED = "AUTH_MFA_ENROLLED";
    public static final String MFA_DISABLED = "AUTH_MFA_DISABLED";
    public static final String MFA_RECOVERY_CODE_USED = "AUTH_MFA_RECOVERY_CODE_USED";

    // Password
    public static final String PASSWORD_CHANGED = "AUTH_PASSWORD_CHANGED";
    public static final String PASSWORD_RESET_REQUESTED = "AUTH_PASSWORD_RESET_REQUESTED";
    public static final String PASSWORD_RESET_COMPLETED = "AUTH_PASSWORD_RESET_COMPLETED";
    public static final String PASSWORD_RESET_TOKEN_INVALID = "AUTH_PASSWORD_RESET_TOKEN_INVALID";

    // User & role administration (Module 2)
    public static final String USER_CREATED = "USER_CREATED";
    public static final String USER_UPDATED = "USER_UPDATED";
    public static final String USER_STATUS_CHANGED = "USER_STATUS_CHANGED";
    public static final String USER_DELETED = "USER_DELETED";
    public static final String USER_ROLES_ASSIGNED = "USER_ROLES_ASSIGNED";
    public static final String USER_PASSWORD_ADMIN_RESET = "USER_PASSWORD_ADMIN_RESET";
    public static final String USER_INVITATION_ISSUED = "USER_INVITATION_ISSUED";
    public static final String USER_INVITATION_ACCEPTED = "USER_INVITATION_ACCEPTED";
    public static final String USER_INVITATION_REVOKED = "USER_INVITATION_REVOKED";
    public static final String ROLE_CREATED = "ROLE_CREATED";
    public static final String ROLE_UPDATED = "ROLE_UPDATED";
    public static final String ROLE_DELETED = "ROLE_DELETED";

    /*
     * Data Management — the attribute catalogue.
     *
     * These are CONFIGURATION events, and they belong in the trail for a reason that is easy to
     * miss: deactivating an attribute is not a UI preference, it removes a field from every
     * subsequent import and export. A field a contractor has been delivering for two years quietly
     * stops being read, and the only record of why is this one.
     */
    public static final String LAYER_ATTRIBUTE_CREATED = "LAYER_ATTRIBUTE_CREATED";
    public static final String LAYER_ATTRIBUTE_UPDATED = "LAYER_ATTRIBUTE_UPDATED";
    public static final String LAYER_ATTRIBUTE_RENAMED = "LAYER_ATTRIBUTE_RENAMED";
    public static final String LAYER_ATTRIBUTE_RETYPED = "LAYER_ATTRIBUTE_RETYPED";
    public static final String LAYER_ATTRIBUTE_DEACTIVATED = "LAYER_ATTRIBUTE_DEACTIVATED";
    public static final String LAYER_ATTRIBUTE_REACTIVATED = "LAYER_ATTRIBUTE_REACTIVATED";
    public static final String IMPORT_MAPPING_PROFILE_SAVED = "IMPORT_MAPPING_PROFILE_SAVED";
    public static final String ASSET_EXPORT_GENERATED = "ASSET_EXPORT_GENERATED";
    public static final String IMPORT_RUN_COMPLETED = "IMPORT_RUN_COMPLETED";
    public static final String IMPORT_RUN_FAILED = "IMPORT_RUN_FAILED";
    public static final String IMPORT_RUN_DATA_DELETED = "IMPORT_RUN_DATA_DELETED";

    /*
     * Layer Management and Layer Style Management.
     *
     * CONFIGURATION events, one step above the attribute events beside them. Archiving a layer takes
     * it off the map, out of the import hub and out of every export at once, while leaving every
     * feature it ever held in place — so the data looks intact and the product looks like it lost a
     * module, and this trail is the only account of which it was. Style events are recorded for a
     * quieter reason: a map that has silently changed what its colours mean is a map an operator
     * will still read the old way.
     */
    public static final String GIS_LAYER_CREATED = "GIS_LAYER_CREATED";
    public static final String GIS_LAYER_UPDATED = "GIS_LAYER_UPDATED";
    public static final String GIS_LAYER_ENABLED = "GIS_LAYER_ENABLED";
    public static final String GIS_LAYER_DISABLED = "GIS_LAYER_DISABLED";
    public static final String GIS_LAYER_ARCHIVED = "GIS_LAYER_ARCHIVED";
    public static final String GIS_LAYER_STYLE_CREATED = "GIS_LAYER_STYLE_CREATED";
    public static final String GIS_LAYER_STYLE_UPDATED = "GIS_LAYER_STYLE_UPDATED";
    public static final String GIS_LAYER_STYLE_ACTIVATED = "GIS_LAYER_STYLE_ACTIVATED";
    public static final String GIS_LAYER_STYLE_DEACTIVATED = "GIS_LAYER_STYLE_DEACTIVATED";
    public static final String GIS_LAYER_STYLE_DEFAULTED = "GIS_LAYER_STYLE_DEFAULTED";

    /*
     * Device Data Configuration — the parameter catalogue.
     *
     * CONFIGURATION events for the same reason the layer attributes above are, one step further
     * along: a parameter's definition decides the unit a reading is recorded in, the range that
     * makes it an alarm and whether it reaches a report at all. Widening a range retrospectively
     * changes what "out of range" meant; deactivating a parameter removes it from every dashboard
     * and rule that reads it, and nothing about the readings themselves changes to announce it.
     * This trail plus iot.device_parameter_history is the only account of why.
     */
    public static final String DEVICE_PARAMETER_CREATED = "DEVICE_PARAMETER_CREATED";
    public static final String DEVICE_PARAMETER_UPDATED = "DEVICE_PARAMETER_UPDATED";
    public static final String DEVICE_PARAMETER_DEACTIVATED = "DEVICE_PARAMETER_DEACTIVATED";
    public static final String DEVICE_PARAMETER_REACTIVATED = "DEVICE_PARAMETER_REACTIVATED";
    public static final String DEVICE_PARAMETER_DISCOVERY_IGNORED = "DEVICE_PARAMETER_DISCOVERY_IGNORED";

    // System
    public static final String BOOTSTRAP_ADMIN_CREATED = "SYSTEM_BOOTSTRAP_ADMIN_CREATED";

    private AuditEventTypes() {
    }
}

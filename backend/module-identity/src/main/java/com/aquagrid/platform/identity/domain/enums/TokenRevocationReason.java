package com.aquagrid.platform.identity.domain.enums;

/** Why a refresh token stopped being valid. Retained for forensics, never overwritten. */
public enum TokenRevocationReason {

    /** The user signed out of this device. */
    LOGOUT,
    /** The user signed out of every device. */
    LOGOUT_ALL,
    /** Normal rotation: this token was exchanged for its successor. */
    ROTATED,
    /** An already-rotated or revoked token was presented — the token leaked. */
    REUSE_DETECTED,
    PASSWORD_CHANGED,
    ADMIN_REVOKED,
    MFA_CHANGED,
    EXPIRED,
    USER_DISABLED
}

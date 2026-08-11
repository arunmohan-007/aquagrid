package com.aquagrid.platform.identity.domain.enums;

/**
 * Lifecycle state of a user account.
 *
 * <p>{@code LOCKED} is reserved for an administrator's deliberate lock. A temporary lockout caused
 * by failed logins is <em>not</em> represented here — it lives in {@code lockout_until}, because it
 * is a transient condition that expires by itself. Conflating the two makes it impossible to tell
 * "the security team locked this account" from "someone fat-fingered their password five times",
 * and leaves accounts stuck in a locked state after the window passes.
 */
public enum UserStatus {

    /** Created but not yet activated; cannot authenticate. */
    PENDING,

    /** Normal, able to authenticate. */
    ACTIVE,

    /** Deactivated by an administrator; cannot authenticate. */
    DISABLED,

    /** Locked by an administrator; requires administrative action to restore. */
    LOCKED
}

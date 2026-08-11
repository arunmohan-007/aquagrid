package com.aquagrid.platform.identity.domain.enums;

/**
 * Result of an authentication attempt, as recorded in {@code identity.login_attempts}.
 *
 * <p>These values are far more granular than what is returned to the client. The API answers
 * "invalid credentials" for every failure mode, so an attacker learns nothing; the real reason is
 * recorded here for the operator, for lockout evaluation and for threat analytics.
 */
public enum LoginOutcome {

    SUCCESS,
    /** Password verified; awaiting the second factor. */
    MFA_PENDING,
    INVALID_CREDENTIALS,
    UNKNOWN_IDENTIFIER,
    ACCOUNT_LOCKED,
    ACCOUNT_DISABLED,
    ACCOUNT_PENDING,
    ORGANIZATION_INACTIVE,
    MFA_FAILED,
    RATE_LIMITED;

    public boolean isFailure() {
        return this != SUCCESS && this != MFA_PENDING;
    }

    /**
     * Whether this outcome should advance the account's failed-attempt counter.
     *
     * <p>Notably {@code RATE_LIMITED} does not: the attempt never reached credential verification,
     * so counting it would let an attacker lock any account out simply by flooding the endpoint —
     * turning a brute-force defence into a denial-of-service weapon against legitimate users.
     */
    public boolean countsTowardLockout() {
        return this == INVALID_CREDENTIALS || this == MFA_FAILED;
    }
}

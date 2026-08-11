package com.aquagrid.platform.identity.domain.model;

import com.aquagrid.platform.common.domain.AuditableEntity;
import com.aquagrid.platform.identity.domain.enums.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A person who can authenticate.
 *
 * <p>This entity owns its own security invariants rather than exposing raw setters for the
 * authentication service to manipulate. {@link #registerFailedLogin} and {@link #registerSuccessfulLogin}
 * exist because "increment the counter, compare it to the threshold, set the lockout timestamp,
 * remember to reset all three on success" is a rule, not plumbing — and a rule scattered across
 * call sites is a rule that will eventually be applied inconsistently.
 *
 * <p>The entity never leaves the module: controllers return DTOs, and other modules see the
 * published {@code api} types.
 */
@Getter
@Setter
@Entity
@Table(name = "users", schema = "identity")
public class User extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false, updatable = false)
    private Organization organization;

    // citext, not varchar: sign-in must not depend on the case the user typed, and the
    // uniqueness constraints in V1100 rely on the database collating these case-insensitively.
    @Column(name = "username", nullable = false, length = 60, columnDefinition = "citext")
    private String username;

    @Column(name = "email", nullable = false, length = 320, columnDefinition = "citext")
    private String email;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "password_hash", nullable = false, length = 120)
    private String passwordHash;

    @Column(name = "password_updated_at", nullable = false)
    private Instant passwordUpdatedAt;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(name = "job_title", length = 120)
    private String jobTitle;

    @Column(name = "phone", length = 40)
    private String phone;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status = UserStatus.PENDING;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    @Column(name = "lockout_until")
    private Instant lockoutUntil;

    @Column(name = "mfa_enabled", nullable = false)
    private boolean mfaEnabled;

    /** AES-256-GCM ciphertext of the TOTP seed. Decrypted only inside {@code MfaService}. */
    @Column(name = "mfa_secret")
    private String mfaSecret;

    @Column(name = "mfa_confirmed_at")
    private Instant mfaConfirmedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "last_login_ip", columnDefinition = "inet")
    private String lastLoginIp;

    @Column(name = "timezone", length = 60)
    private String timezone;

    @Column(name = "locale", length = 20)
    private String locale;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "user_roles",
            schema = "identity",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new LinkedHashSet<>();

    // --- Domain behaviour -----------------------------------------------------------------------

    /** True while a temporary, self-expiring lockout from failed logins is in force. */
    public boolean isTemporarilyLockedOut(Instant now) {
        return lockoutUntil != null && lockoutUntil.isAfter(now);
    }

    /** True when the account may proceed to credential verification. */
    public boolean canAuthenticate(Instant now) {
        return status == UserStatus.ACTIVE && !isTemporarilyLockedOut(now);
    }

    /**
     * Records a failed credential check and applies the lockout threshold.
     *
     * @return {@code true} if this failure caused the account to become locked
     */
    public boolean registerFailedLogin(Instant now, int threshold, java.time.Duration lockoutDuration) {
        failedLoginAttempts++;
        if (failedLoginAttempts >= threshold) {
            lockoutUntil = now.plus(lockoutDuration);
            return true;
        }
        return false;
    }

    /**
     * Records a successful authentication.
     *
     * <p>Clearing the counter <em>and</em> the lockout timestamp together is the point: leaving a
     * stale {@code lockoutUntil} behind would lock the user out again on their next attempt, and
     * leaving a stale counter would lock them out after a single future typo.
     */
    public void registerSuccessfulLogin(Instant now, String clientIp) {
        failedLoginAttempts = 0;
        lockoutUntil = null;
        lastLoginAt = now;
        lastLoginIp = clientIp;
    }

    public void applyNewPassword(String encodedPassword, Instant now) {
        this.passwordHash = encodedPassword;
        this.passwordUpdatedAt = now;
        this.mustChangePassword = false;
        this.failedLoginAttempts = 0;
        this.lockoutUntil = null;
    }

    public void enableMfa(String encryptedSecret, Instant now) {
        this.mfaSecret = encryptedSecret;
        this.mfaEnabled = true;
        this.mfaConfirmedAt = now;
    }

    public void disableMfa() {
        this.mfaEnabled = false;
        this.mfaSecret = null;
        this.mfaConfirmedAt = null;
    }

    public String effectiveTimezone() {
        return timezone != null ? timezone : organization.getTimezone();
    }

    public String effectiveLocale() {
        return locale != null ? locale : organization.getLocale();
    }
}

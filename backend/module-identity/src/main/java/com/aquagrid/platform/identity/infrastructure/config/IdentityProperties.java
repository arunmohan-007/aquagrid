package com.aquagrid.platform.identity.infrastructure.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration of the authentication module.
 *
 * <p>Every security-relevant number in Module 1 is here rather than embedded in code, because these
 * are the values a customer's security team will want to see, review and tune during procurement.
 */
@Validated
@ConfigurationProperties(prefix = "aquagrid.identity")
public record IdentityProperties(
        @NotNull Lockout lockout,
        @NotNull RefreshToken refreshToken,
        @NotNull RateLimit rateLimit,
        @NotNull Password password,
        @NotNull Mfa mfa,
        @NotNull Bootstrap bootstrap,
        @NotBlank String appBaseUrl
) {

    /**
     * Progressive account lockout.
     *
     * @param maxFailedAttempts failures before the account locks
     * @param duration          how long the lock lasts
     * @param multiplier        applied to {@code duration} for each subsequent lockout, so a
     *                          persistent attacker faces exponentially longer waits while a user
     *                          who mistyped once is barely inconvenienced
     * @param maxDuration       ceiling, so an account is never locked out effectively forever by
     *                          an attacker deliberately failing logins against it
     */
    public record Lockout(
            @Min(3) int maxFailedAttempts,
            @NotNull Duration duration,
            @Min(1) int multiplier,
            @NotNull Duration maxDuration
    ) {
        public Lockout {
            maxFailedAttempts = maxFailedAttempts == 0 ? 5 : maxFailedAttempts;
            duration = duration == null ? Duration.ofMinutes(15) : duration;
            multiplier = multiplier == 0 ? 2 : multiplier;
            maxDuration = maxDuration == null ? Duration.ofHours(4) : maxDuration;
        }
    }

    /**
     * Refresh token and its cookie.
     *
     * @param secure  {@code Secure} attribute. <b>Must be true in production.</b> Left configurable
     *                only so that a plain-HTTP local development server works; a startup check
     *                refuses to run a non-development profile with this disabled.
     * @param path    cookie path. Scoped to the auth endpoints so the token is not attached to
     *                every API request — it is only ever needed at {@code /refresh} and
     *                {@code /logout}.
     * @param sameSite {@code Strict}: the browser will not send this cookie on any cross-site
     *                 request, which is what makes disabling CSRF protection safe.
     * @param absoluteTtl ceiling on a session's total age, measured from the original login rather
     *                    than from the last refresh. {@code ttl} alone is a sliding window: a
     *                    session used at least once every {@code ttl} renews indefinitely. This
     *                    caps that, forcing re-authentication after {@code absoluteTtl} regardless
     *                    of activity.
     */
    public record RefreshToken(
            @NotNull Duration ttl,
            @NotBlank String cookieName,
            boolean secure,
            @NotBlank String path,
            @NotBlank String sameSite,
            String domain,
            @NotNull Duration cleanupGracePeriod,
            @Min(1) int maxSessionsPerUser,
            @NotNull Duration absoluteTtl
    ) {
        public RefreshToken {
            ttl = ttl == null ? Duration.ofDays(14) : ttl;
            cookieName = cookieName == null || cookieName.isBlank() ? "ag_rt" : cookieName;
            path = path == null || path.isBlank() ? "/api/v1/auth" : path;
            sameSite = sameSite == null || sameSite.isBlank() ? "Strict" : sameSite;
            cleanupGracePeriod = cleanupGracePeriod == null ? Duration.ofDays(7) : cleanupGracePeriod;
            maxSessionsPerUser = maxSessionsPerUser == 0 ? 10 : maxSessionsPerUser;
            absoluteTtl = absoluteTtl == null ? Duration.ofDays(30) : absoluteTtl;

            if (absoluteTtl.compareTo(ttl) < 0) {
                throw new IllegalArgumentException(
                        "aquagrid.identity.refresh-token.absolute-ttl must not be shorter than ttl: "
                                + "it caps the sliding window, not the other way round");
            }
        }
    }

    /**
     * Per-IP and per-identifier throttling of the login endpoint.
     *
     * <p>Two independent dimensions because either alone is trivially bypassed: per-identifier
     * limiting alone does not stop one host enumerating thousands of accounts, and per-IP limiting
     * alone does not stop a botnet spraying one account.
     */
    public record RateLimit(
            @Min(1) int loginAttemptsPerIp,
            @Min(1) int loginAttemptsPerIdentifier,
            @NotNull Duration window,
            @Min(1) int passwordResetPerIp,
            @NotNull Duration passwordResetWindow
    ) {
        public RateLimit {
            loginAttemptsPerIp = loginAttemptsPerIp == 0 ? 30 : loginAttemptsPerIp;
            loginAttemptsPerIdentifier = loginAttemptsPerIdentifier == 0 ? 10 : loginAttemptsPerIdentifier;
            window = window == null ? Duration.ofMinutes(5) : window;
            passwordResetPerIp = passwordResetPerIp == 0 ? 5 : passwordResetPerIp;
            passwordResetWindow = passwordResetWindow == null ? Duration.ofHours(1) : passwordResetWindow;
        }
    }

    public record Password(
            @Min(8) int minLength,
            @Min(16) int maxLength,
            boolean requireUppercase,
            boolean requireLowercase,
            boolean requireDigit,
            boolean requireSpecial,
            @Min(0) int historyDepth,
            @Min(0) int maxAgeDays
    ) {
        public Password {
            minLength = minLength == 0 ? 12 : minLength;
            maxLength = maxLength == 0 ? 128 : maxLength;
            historyDepth = historyDepth == 0 ? 5 : historyDepth;
        }
    }

    /**
     * @param issuer        label shown in the user's authenticator app
     * @param recoveryCodes how many single-use recovery codes are issued at enrolment
     */
    public record Mfa(@NotBlank String issuer, @Min(4) int recoveryCodes) {
        public Mfa {
            issuer = issuer == null || issuer.isBlank() ? "AquaGrid" : issuer;
            recoveryCodes = recoveryCodes == 0 ? 10 : recoveryCodes;
        }
    }

    /**
     * First-run administrator.
     *
     * @param password supplied by the environment. There is deliberately no default: a shipped
     *                 default credential is a shipped vulnerability. When this is blank, no
     *                 administrator is created and the startup log says so.
     */
    public record Bootstrap(
            String organizationCode,
            String username,
            String email,
            String fullName,
            String password
    ) {
        public Bootstrap {
            organizationCode = blankTo(organizationCode, "SYSTEM");
            username = blankTo(username, "admin");
            email = blankTo(email, "admin@aquagrid.local");
            fullName = blankTo(fullName, "Platform Administrator");
        }

        public boolean isEnabled() {
            return password != null && !password.isBlank();
        }

        private static String blankTo(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }
}

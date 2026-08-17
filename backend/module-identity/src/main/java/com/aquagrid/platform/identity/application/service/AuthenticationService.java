package com.aquagrid.platform.identity.application.service;

import com.aquagrid.platform.common.audit.AuditCategory;
import com.aquagrid.platform.common.audit.AuditEvent;
import com.aquagrid.platform.common.audit.AuditEventTypes;
import com.aquagrid.platform.common.audit.AuditService;
import com.aquagrid.platform.common.audit.AuditSeverity;
import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.identity.application.command.AuthCommands;
import com.aquagrid.platform.identity.application.mapper.UserMapper;
import com.aquagrid.platform.identity.domain.enums.LoginOutcome;
import com.aquagrid.platform.identity.domain.enums.TokenRevocationReason;
import com.aquagrid.platform.identity.domain.enums.UserStatus;
import com.aquagrid.platform.identity.domain.model.User;
import com.aquagrid.platform.identity.infrastructure.config.IdentityProperties;
import com.aquagrid.platform.identity.infrastructure.persistence.UserRepository;
import com.aquagrid.platform.identity.web.dto.AuthResponses;
import com.aquagrid.platform.security.core.AuthClaims;
import com.aquagrid.platform.security.jwt.AccessTokenRequest;
import com.aquagrid.platform.security.jwt.IssuedToken;
import com.aquagrid.platform.security.jwt.JwtTokenService;
import com.aquagrid.platform.security.ratelimit.RateLimitDecision;
import com.aquagrid.platform.security.ratelimit.RateLimiter;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The login use case.
 *
 * <p>This class exists because authentication is a <em>sequence</em> of decisions whose order is
 * itself a security property. Spread across a controller and an entity, that order drifts; here it
 * is one readable method with the reasoning attached.
 *
 * <h2>Order of checks, and why</h2>
 * <ol>
 *   <li><b>Rate limit before anything else.</b> Cheap, and it caps the cost an unauthenticated
 *       caller can impose — BCrypt verification is deliberately expensive, which makes an
 *       unthrottled login endpoint a CPU amplifier.</li>
 *   <li><b>Resolve the account, then verify the password <em>even when no account exists</em>.</b>
 *       Skipping the hash comparison for unknown users returns in ~1 ms instead of ~250 ms, which
 *       is a reliable oracle for "does this address have an account". A dummy hash is verified
 *       instead.</li>
 *   <li><b>Lockout before credential check.</b> A locked account must not have its password tested
 *       at all — otherwise the lockout still leaks whether a guess was correct through timing and
 *       through the counter's behaviour.</li>
 *   <li><b>MFA after the password, never instead of it.</b> A successful password step yields only
 *       a five-minute {@code typ=mfa} token that no API endpoint accepts.</li>
 * </ol>
 *
 * <p>Every failure returns the same {@code AUTH_INVALID_CREDENTIALS} to the client. The specific
 * reason goes to {@code login_attempts} and the audit trail, where the operator can see it and the
 * attacker cannot.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthenticationService {

    private static final String RATE_KEY_IP = "login:ip:";
    private static final String RATE_KEY_IDENTIFIER = "login:id:";
    private static final String RATE_KEY_MFA = "mfa:user:";

    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final RefreshCookieService refreshCookieService;
    private final LoginAttemptService loginAttemptService;
    private final MfaService mfaService;
    private final JwtTokenService jwtTokenService;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiter rateLimiter;
    private final AuditService auditService;
    private final UserMapper userMapper;
    private final IdentityProperties properties;
    private final Clock clock;

    /**
     * A hash of a random value, verified against when the supplied identifier matches no account.
     *
     * <p>Generated at startup rather than hard-coded so it always uses the configured cost factor —
     * a mismatched cost would reintroduce the timing difference this field exists to remove.
     */
    private String dummyPasswordHash;

    @PostConstruct
    void initialiseDummyHash() {
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    // ---- Login ---------------------------------------------------------------------------------

    /**
     * {@code noRollbackFor} is load-bearing, not a tuning knob.
     *
     * <p>Every failure path in this method deliberately <em>writes</em> before it throws: the failed
     * attempt counter that drives lockout, the {@code login_attempts} row that is the forensic
     * record, the audit event, and — on the attempt that trips the threshold — the revocation of
     * every existing session. {@link BusinessException} is a {@code RuntimeException}, so under
     * Spring's default rollback rule the very act of reporting the failure discards the evidence and
     * the counter that recorded it. Lockout then never engages: each wrong password increments a
     * counter that is rolled back to zero microseconds later, and an attacker gets unlimited
     * attempts against a system that believes it is enforcing a three-attempt limit.
     *
     * <p>Scoped to {@code BusinessException} on purpose. That type means "the request was refused
     * for a reason we chose"; anything else — a constraint violation, a driver error, a bug — is an
     * unknown half-completed state and must still roll back.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthenticationResult login(AuthCommands.Login command) {
        String identifier = normalise(command.identifier());
        enforceLoginRateLimits(identifier, command);

        Optional<User> maybeUser = resolveUser(identifier, command.organizationCode());
        if (maybeUser.isEmpty()) {
            // Constant-work path: verify against a dummy hash so an unknown account costs the same
            // as a wrong password.
            passwordEncoder.matches(command.password(), dummyPasswordHash);
            loginAttemptService.record(identifier, null, null, LoginOutcome.UNKNOWN_IDENTIFIER,
                    "No matching account", command.clientIp(), command.userAgent(), false);
            throw invalidCredentials();
        }

        User user = maybeUser.get();
        Instant now = clock.instant();
        assertOrganizationActive(user, identifier, command);
        assertAccountUsable(user, identifier, command, now);

        if (!passwordEncoder.matches(command.password(), user.getPasswordHash())) {
            handleFailedPassword(user, identifier, command, now);
            throw invalidCredentials();
        }

        // A legitimate user should not remain throttled by their own earlier typos.
        rateLimiter.reset(RATE_KEY_IDENTIFIER + identifier);
        rateLimiter.reset(RATE_KEY_IP + command.clientIp());

        if (user.isMfaEnabled()) {
            loginAttemptService.record(identifier, user, user.getOrganization().getId(),
                    LoginOutcome.MFA_PENDING, null, command.clientIp(), command.userAgent(), false);

            IssuedToken mfaToken = jwtTokenService.issueMfaToken(user.getId(),
                    user.getOrganization().getId());
            return AuthenticationResult.bodyOnly(
                    AuthResponses.Authentication.mfaPending(mfaToken.value(),
                            mfaToken.expiresInSeconds()));
        }

        return completeLogin(user, command.clientIp(), command.userAgent(), command.deviceLabel(),
                identifier, false);
    }

    // ---- MFA challenge -------------------------------------------------------------------------

    // Same reason as login(): a wrong code increments the lockout counter before throwing, and a
    // rolled-back counter leaves a 10^6 code space defended by nothing but the rate limiter.
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthenticationResult completeMfaChallenge(AuthCommands.MfaChallenge command) {
        Jwt mfaToken = jwtTokenService.decodeMfaToken(command.mfaToken());
        UUID userId = UUID.fromString(mfaToken.getSubject());

        // Throttled per user, not per IP: the code space is only 10^6, so an unthrottled challenge
        // endpoint reduces the second factor to a few minutes of guessing.
        RateLimitDecision decision = rateLimiter.tryConsume(RATE_KEY_MFA + userId, 5,
                Duration.ofMinutes(5));
        if (!decision.allowed()) {
            throw rateLimited(decision);
        }

        User user = userRepository.findByIdWithAuthorities(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_TOKEN_INVALID));
        Instant now = clock.instant();
        assertOrganizationActive(user, user.getEmail(), toLogin(command, user));
        assertAccountUsable(user, user.getEmail(), toLogin(command, user), now);

        if (!user.isMfaEnabled()) {
            // The factor was removed between the two steps; the password step alone is not enough.
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID,
                    "Your sign-in session is no longer valid. Please sign in again.");
        }

        if (!mfaService.verifyCode(user, command.code(), command.clientIp())) {
            boolean locked = user.registerFailedLogin(now,
                    properties.lockout().maxFailedAttempts(), properties.lockout().duration());
            loginAttemptService.record(user.getEmail(), user, user.getOrganization().getId(),
                    LoginOutcome.MFA_FAILED, "Invalid verification code", command.clientIp(),
                    command.userAgent(), true);
            auditService.record(loginAudit(user, AuditEventTypes.MFA_CHALLENGE_FAILURE, false,
                    "Invalid multi-factor verification code", command.clientIp(),
                    command.userAgent(), AuditSeverity.WARNING));
            if (locked) {
                auditAccountLocked(user, command.clientIp(), command.userAgent());
            }
            throw new BusinessException(ErrorCode.MFA_CODE_INVALID,
                    "That verification code is not valid.");
        }

        rateLimiter.reset(RATE_KEY_MFA + userId);
        auditService.record(loginAudit(user, AuditEventTypes.MFA_CHALLENGE_SUCCESS, true,
                "Multi-factor verification succeeded", command.clientIp(), command.userAgent(),
                AuditSeverity.INFO));

        return completeLogin(user, command.clientIp(), command.userAgent(), command.deviceLabel(),
                user.getEmail(), true);
    }

    // ---- Refresh -------------------------------------------------------------------------------

    /**
     * Also {@code noRollbackFor}, and for a sharper reason than login's.
     *
     * <p>This is the outermost transaction for {@link RefreshTokenService#rotate}, so it — not the
     * inner method — decides whether rotate's writes survive. Rotate answers a presented token that
     * has already been used by revoking the entire token family and then throwing
     * {@code AUTH_REFRESH_TOKEN_REUSED}. Rolling that back leaves the thief's successor token live
     * while telling everyone the leak was contained: the audit log records a CRITICAL reuse event
     * and the session it was supposed to kill keeps working.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthenticationResult refresh(AuthCommands.Refresh command) {
        if (!StringUtils.hasText(command.refreshToken())) {
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_MISSING,
                    "You are not signed in.");
        }

        RefreshTokenService.IssuedRefreshToken rotated = refreshTokenService.rotate(
                command.refreshToken(), command.clientIp(), command.userAgent());
        User user = rotated.entity().getUser();

        IssuedToken accessToken = issueAccessToken(user, rotated.entity().getFamilyId());
        auditService.record(loginAudit(user, AuditEventTypes.TOKEN_REFRESHED, true,
                "Access token refreshed", command.clientIp(), command.userAgent(),
                AuditSeverity.INFO));

        return AuthenticationResult.of(
                buildAuthentication(user, accessToken),
                refreshCookieService.build(rotated.plaintext()));
    }

    // ---- Logout --------------------------------------------------------------------------------

    @Transactional
    public ResponseCookie logout(String refreshToken, UUID actingUserId, String clientIp,
                                 String userAgent) {
        if (StringUtils.hasText(refreshToken)) {
            refreshTokenService.revokeByToken(refreshToken, TokenRevocationReason.LOGOUT);
        }
        if (actingUserId != null) {
            userRepository.findByIdWithAuthorities(actingUserId).ifPresent(user ->
                    auditService.record(loginAudit(user, AuditEventTypes.LOGOUT, true,
                            "Signed out of this device", clientIp, userAgent, AuditSeverity.INFO)));
        }
        return refreshCookieService.clear();
    }

    @Transactional
    public ResponseCookie logoutAll(UUID userId, String clientIp, String userAgent) {
        int revoked = refreshTokenService.revokeAllForUser(userId, TokenRevocationReason.LOGOUT_ALL);
        userRepository.findByIdWithAuthorities(userId).ifPresent(user ->
                auditService.record(loginAudit(user, AuditEventTypes.LOGOUT_ALL, true,
                        "Signed out of all devices (%d session(s) revoked)".formatted(revoked),
                        clientIp, userAgent, AuditSeverity.INFO)));
        return refreshCookieService.clear();
    }

    // ---- Current user --------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public AuthResponses.CurrentUser currentUser(UUID userId) {
        return userRepository.findByIdWithAuthorities(userId)
                .map(userMapper::toCurrentUser)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
    }

    // ---- Internals -----------------------------------------------------------------------------

    private AuthenticationResult completeLogin(User user, String clientIp, String userAgent,
                                               String deviceLabel, String identifier,
                                               boolean mfaUsed) {
        Instant now = clock.instant();
        user.registerSuccessfulLogin(now, clientIp);

        RefreshTokenService.IssuedRefreshToken refreshToken =
                refreshTokenService.issueNewFamily(user, clientIp, userAgent, deviceLabel);
        IssuedToken accessToken = issueAccessToken(user, refreshToken.entity().getFamilyId());

        loginAttemptService.record(identifier, user, user.getOrganization().getId(),
                LoginOutcome.SUCCESS, null, clientIp, userAgent, mfaUsed);
        auditService.record(loginAudit(user, AuditEventTypes.LOGIN_SUCCESS, true,
                mfaUsed ? "Signed in with password and second factor" : "Signed in with password",
                clientIp, userAgent, AuditSeverity.INFO));

        return AuthenticationResult.of(
                buildAuthentication(user, accessToken),
                refreshCookieService.build(refreshToken.plaintext()));
    }

    private IssuedToken issueAccessToken(User user, UUID sessionId) {
        return jwtTokenService.issueAccessToken(AccessTokenRequest.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .organizationId(user.getOrganization().getId())
                .organizationCode(user.getOrganization().getCode())
                .roles(userMapper.roleCodes(user))
                .permissions(userMapper.permissionCodes(user))
                .sessionId(sessionId)
                .mustChangePassword(user.isMustChangePassword())
                .build());
    }

    private AuthResponses.Authentication buildAuthentication(User user, IssuedToken accessToken) {
        return AuthResponses.Authentication.builder()
                .accessToken(accessToken.value())
                .tokenType("Bearer")
                .expiresIn(accessToken.expiresInSeconds())
                .expiresAt(accessToken.expiresAt())
                .mfaRequired(false)
                .mustChangePassword(user.isMustChangePassword())
                .user(userMapper.toCurrentUser(user))
                .build();
    }

    private Optional<User> resolveUser(String identifier, String organizationCode) {
        // Login is by username, and username is unique only within a tenant, so the organisation
        // code is mandatory. Without it there is nothing to disambiguate the identifier against, so
        // no account is resolved and the caller falls onto the same constant-work "unknown account"
        // path as a wrong username.
        if (!StringUtils.hasText(organizationCode)) {
            return Optional.empty();
        }
        return userRepository.findByUsernameAndOrganizationCode(identifier, organizationCode.trim());
    }

    private void enforceLoginRateLimits(String identifier, AuthCommands.Login command) {
        IdentityProperties.RateLimit limits = properties.rateLimit();

        RateLimitDecision byIp = rateLimiter.tryConsume(RATE_KEY_IP + command.clientIp(),
                limits.loginAttemptsPerIp(), limits.window());
        if (!byIp.allowed()) {
            recordRateLimited(identifier, command, "IP rate limit exceeded");
            throw rateLimited(byIp);
        }

        RateLimitDecision byIdentifier = rateLimiter.tryConsume(RATE_KEY_IDENTIFIER + identifier,
                limits.loginAttemptsPerIdentifier(), limits.window());
        if (!byIdentifier.allowed()) {
            recordRateLimited(identifier, command, "Identifier rate limit exceeded");
            throw rateLimited(byIdentifier);
        }
    }

    private void assertOrganizationActive(User user, String identifier, AuthCommands.Login command) {
        if (!user.getOrganization().isActive()) {
            loginAttemptService.record(identifier, user, user.getOrganization().getId(),
                    LoginOutcome.ORGANIZATION_INACTIVE, "Organization is not active",
                    command.clientIp(), command.userAgent(), false);
            throw new BusinessException(ErrorCode.TENANT_INACTIVE,
                    "Your organisation's access is currently suspended. Please contact your administrator.");
        }
    }

    private void assertAccountUsable(User user, String identifier, AuthCommands.Login command,
                                     Instant now) {
        if (user.isTemporarilyLockedOut(now)) {
            long retryAfter = Math.max(1, Duration.between(now, user.getLockoutUntil()).toSeconds());
            loginAttemptService.record(identifier, user, user.getOrganization().getId(),
                    LoginOutcome.ACCOUNT_LOCKED, "Account is temporarily locked",
                    command.clientIp(), command.userAgent(), false);
            auditService.record(loginAudit(user, AuditEventTypes.LOGIN_BLOCKED_LOCKED, false,
                    "Sign-in attempted while the account was locked", command.clientIp(),
                    command.userAgent(), AuditSeverity.WARNING));
            // The remaining time is disclosed deliberately: the caller has already proven the
            // account exists by triggering the lock, and an unexplained failure drives support cost.
            throw new BusinessException(ErrorCode.AUTH_ACCOUNT_LOCKED,
                    "Too many failed attempts. Try again in %d minute(s)."
                            .formatted(Math.max(1, retryAfter / 60)),
                    Map.of("lockedUntil", user.getLockoutUntil().toString(),
                            "retryAfterSeconds", retryAfter));
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            LoginOutcome outcome = switch (user.getStatus()) {
                case PENDING -> LoginOutcome.ACCOUNT_PENDING;
                case LOCKED -> LoginOutcome.ACCOUNT_LOCKED;
                default -> LoginOutcome.ACCOUNT_DISABLED;
            };
            loginAttemptService.record(identifier, user, user.getOrganization().getId(), outcome,
                    "Account status is " + user.getStatus(), command.clientIp(),
                    command.userAgent(), false);
            throw switch (user.getStatus()) {
                case PENDING -> new BusinessException(ErrorCode.AUTH_ACCOUNT_PENDING,
                        "Your account has not been activated yet.");
                case LOCKED -> new BusinessException(ErrorCode.AUTH_ACCOUNT_LOCKED,
                        "Your account has been locked. Please contact your administrator.");
                default -> new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED,
                        "Your account has been disabled. Please contact your administrator.");
            };
        }
    }

    private void handleFailedPassword(User user, String identifier, AuthCommands.Login command,
                                      Instant now) {
        boolean locked = user.registerFailedLogin(now, properties.lockout().maxFailedAttempts(),
                properties.lockout().duration());

        loginAttemptService.record(identifier, user, user.getOrganization().getId(),
                LoginOutcome.INVALID_CREDENTIALS, "Password did not match", command.clientIp(),
                command.userAgent(), false);
        auditService.record(loginAudit(user, AuditEventTypes.LOGIN_FAILURE, false,
                "Sign-in failed: incorrect password (attempt %d of %d)"
                        .formatted(user.getFailedLoginAttempts(),
                                properties.lockout().maxFailedAttempts()),
                command.clientIp(), command.userAgent(), AuditSeverity.INFO));

        if (locked) {
            auditAccountLocked(user, command.clientIp(), command.userAgent());
            // Sessions are revoked on lockout: if the attempts were an attacker, any session they
            // already hold must not outlive the detection.
            refreshTokenService.revokeAllForUser(user.getId(), TokenRevocationReason.ADMIN_REVOKED);
        }
    }

    private void auditAccountLocked(User user, String clientIp, String userAgent) {
        log.warn("Account {} locked until {} after repeated failures", user.getId(),
                user.getLockoutUntil());
        auditService.record(loginAudit(user, AuditEventTypes.ACCOUNT_LOCKED, false,
                "Account locked until %s after %d failed attempts"
                        .formatted(user.getLockoutUntil(), user.getFailedLoginAttempts()),
                clientIp, userAgent, AuditSeverity.CRITICAL));
    }

    private void recordRateLimited(String identifier, AuthCommands.Login command, String reason) {
        loginAttemptService.record(identifier, null, null, LoginOutcome.RATE_LIMITED, reason,
                command.clientIp(), command.userAgent(), false);
        auditService.record(AuditEvent.builder()
                .eventType(AuditEventTypes.LOGIN_BLOCKED_RATE_LIMIT)
                .category(AuditCategory.SECURITY)
                .severity(AuditSeverity.WARNING)
                .actorUsername(identifier)
                .success(false)
                .message(reason)
                .clientIp(command.clientIp())
                .userAgent(command.userAgent())
                .build());
    }

    private AuditEvent loginAudit(User user, String eventType, boolean success, String message,
                                  String clientIp, String userAgent, AuditSeverity severity) {
        return AuditEvent.builder()
                .organizationId(user.getOrganization().getId())
                .actorUserId(user.getId())
                .actorUsername(user.getUsername())
                .eventType(eventType)
                .category(AuditCategory.AUTHENTICATION)
                .severity(severity)
                .resourceType("User")
                .resourceId(user.getId().toString())
                .success(success)
                .message(message)
                .clientIp(clientIp)
                .userAgent(userAgent)
                .metadata(Map.of("tokenType", AuthClaims.TYPE_ACCESS))
                .build();
    }

    private BusinessException invalidCredentials() {
        // One message for every credential failure mode. The specific reason is in login_attempts.
        return new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS,
                "The username, organisation code or password you entered is not correct.");
    }

    private BusinessException rateLimited(RateLimitDecision decision) {
        return new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED,
                "Too many attempts. Please wait %d second(s) before trying again."
                        .formatted(decision.retryAfterSeconds()),
                Map.of("retryAfterSeconds", decision.retryAfterSeconds()));
    }

    private AuthCommands.Login toLogin(AuthCommands.MfaChallenge command, User user) {
        return AuthCommands.Login.builder()
                .identifier(user.getEmail())
                .clientIp(command.clientIp())
                .userAgent(command.userAgent())
                .deviceLabel(command.deviceLabel())
                .build();
    }

    private String normalise(String identifier) {
        return identifier == null ? "" : identifier.trim().toLowerCase(Locale.ROOT);
    }
}

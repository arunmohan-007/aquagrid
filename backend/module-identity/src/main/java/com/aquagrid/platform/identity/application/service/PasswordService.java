package com.aquagrid.platform.identity.application.service;

import com.aquagrid.platform.common.audit.AuditCategory;
import com.aquagrid.platform.common.audit.AuditEvent;
import com.aquagrid.platform.common.audit.AuditEventTypes;
import com.aquagrid.platform.common.audit.AuditService;
import com.aquagrid.platform.common.audit.AuditSeverity;
import com.aquagrid.platform.common.crypto.Hashes;
import com.aquagrid.platform.common.crypto.TokenGenerator;
import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.identity.domain.enums.TokenRevocationReason;
import com.aquagrid.platform.identity.domain.model.PasswordHistoryEntry;
import com.aquagrid.platform.identity.domain.model.PasswordResetToken;
import com.aquagrid.platform.identity.domain.model.User;
import com.aquagrid.platform.identity.domain.policy.PasswordPolicy;
import com.aquagrid.platform.identity.infrastructure.config.IdentityProperties;
import com.aquagrid.platform.identity.infrastructure.persistence.PasswordHistoryRepository;
import com.aquagrid.platform.identity.infrastructure.persistence.PasswordResetTokenRepository;
import com.aquagrid.platform.identity.infrastructure.persistence.UserRepository;
import com.aquagrid.platform.identity.web.dto.AuthResponses;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Password policy, change, and the forgotten-password flow.
 *
 * <p>The forgotten-password endpoint is where most implementations leak: returning "no account with
 * that email" turns the endpoint into a free user-enumeration oracle, which is the reconnaissance
 * step of every credential-stuffing campaign. Here the response is identical — {@code 202 Accepted}
 * with the same body and comparable timing — whether or not the address exists.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordService {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final PasswordHistoryRepository historyRepository;
    private final RefreshTokenService refreshTokenService;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final IdentityProperties properties;
    private final com.aquagrid.platform.security.jwt.JwtProperties jwtProperties;
    private final Clock clock;

    public PasswordPolicy policy() {
        IdentityProperties.Password config = properties.password();
        return new PasswordPolicy(config.minLength(), config.maxLength(), config.requireUppercase(),
                config.requireLowercase(), config.requireDigit(), config.requireSpecial(),
                config.historyDepth(), config.maxAgeDays(), null);
    }

    public AuthResponses.PasswordPolicy describePolicy() {
        PasswordPolicy policy = policy();
        return AuthResponses.PasswordPolicy.builder()
                .minLength(policy.minLength())
                .maxLength(policy.maxLength())
                .requireUppercase(policy.requireUppercase())
                .requireLowercase(policy.requireLowercase())
                .requireDigit(policy.requireDigit())
                .requireSpecial(policy.requireSpecial())
                .historyDepth(policy.historyDepth())
                .rules(policy.describe())
                .build();
    }

    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword,
                               String clientIp) {
        User user = userRepository.findByIdWithAuthorities(userId)
                .orElseThrow(() -> new com.aquagrid.platform.common.error.ResourceNotFoundException(
                        "User", userId));

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            auditService.record(authAudit(user, AuditEventTypes.PASSWORD_CHANGED, false,
                    "Password change rejected: current password did not match", clientIp,
                    AuditSeverity.WARNING));
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS,
                    "Your current password is not correct.");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.PASSWORD_REUSED,
                    "Your new password must be different from your current password.");
        }

        applyNewPassword(user, newPassword, clientIp, TokenRevocationReason.PASSWORD_CHANGED);

        auditService.record(authAudit(user, AuditEventTypes.PASSWORD_CHANGED, true,
                "Password changed by the user", clientIp, AuditSeverity.INFO));
    }

    /**
     * Starts a reset.
     *
     * @return the generated token when a matching, eligible account exists — to be delivered by
     *         email (Module 20). Empty otherwise. The caller must respond identically in both
     *         cases.
     */
    @Transactional
    public java.util.Optional<String> beginReset(String email, String clientIp) {
        var maybeUser = userRepository.findByEmailIgnoreCase(email);
        if (maybeUser.isEmpty()) {
            log.info("Password reset requested for an unknown address (ip={})", clientIp);
            return java.util.Optional.empty();
        }
        User user = maybeUser.get();
        if (!user.getOrganization().isActive()
                || user.getStatus() == com.aquagrid.platform.identity.domain.enums.UserStatus.DISABLED) {
            log.info("Password reset suppressed for ineligible account {}", user.getId());
            return java.util.Optional.empty();
        }

        Instant now = clock.instant();
        // Any previously issued link is invalidated: a user who requests twice because the first
        // email was slow must not leave two live grants outstanding.
        resetTokenRepository.invalidateOutstanding(user.getId(), now);

        String plaintext = TokenGenerator.opaqueToken();
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setTokenHash(Hashes.sha256Hex(plaintext));
        token.setIssuedAt(now);
        token.setExpiresAt(now.plus(jwtProperties.resetTokenTtl()));
        token.setRequestedIp(clientIp);
        resetTokenRepository.save(token);

        auditService.record(authAudit(user, AuditEventTypes.PASSWORD_RESET_REQUESTED, true,
                "Password reset requested", clientIp, AuditSeverity.INFO));

        return java.util.Optional.of(plaintext);
    }

    @Transactional
    public void completeReset(String plaintextToken, String newPassword, String clientIp) {
        Instant now = clock.instant();
        PasswordResetToken token = resetTokenRepository
                .findByTokenHash(Hashes.sha256Hex(plaintextToken))
                .orElseThrow(() -> {
                    log.info("Password reset attempted with an unrecognised token (ip={})", clientIp);
                    return new BusinessException(ErrorCode.PASSWORD_RESET_TOKEN_INVALID);
                });

        if (!token.isUsable(now)) {
            auditService.record(authAudit(token.getUser(),
                    AuditEventTypes.PASSWORD_RESET_TOKEN_INVALID, false,
                    "Password reset attempted with an expired or already-used token", clientIp,
                    AuditSeverity.WARNING));
            throw new BusinessException(ErrorCode.PASSWORD_RESET_TOKEN_INVALID);
        }

        User user = token.getUser();
        applyNewPassword(user, newPassword, clientIp, TokenRevocationReason.PASSWORD_CHANGED);
        token.setUsedAt(now);

        auditService.record(authAudit(user, AuditEventTypes.PASSWORD_RESET_COMPLETED, true,
                "Password reset completed", clientIp, AuditSeverity.INFO));
    }

    /**
     * Validates, hashes and stores a new password, then invalidates everything the old one
     * authorised.
     *
     * <p>Revoking every session is the step that is most often skipped and matters most: a user
     * changing their password after a suspected compromise expects it to evict the intruder, and a
     * password change that leaves the attacker's session alive achieves nothing.
     */
    private void applyNewPassword(User user, String newPassword, String clientIp,
                                  TokenRevocationReason reason) {
        PasswordPolicy policy = policy();
        List<String> violations = policy.validate(newPassword, user.getUsername(), user.getEmail(),
                user.getFullName(), user.getOrganization().getCode(),
                user.getOrganization().getName());
        if (!violations.isEmpty()) {
            throw new BusinessException(ErrorCode.PASSWORD_POLICY_VIOLATION,
                    "The password does not meet the security policy",
                    Map.of("violations", violations));
        }
        assertNotInHistory(user, newPassword, policy.historyDepth());

        Instant now = clock.instant();
        String previousHash = user.getPasswordHash();
        user.applyNewPassword(passwordEncoder.encode(newPassword), now);

        PasswordHistoryEntry historyEntry = new PasswordHistoryEntry();
        historyEntry.setUser(user);
        historyEntry.setPasswordHash(previousHash);
        historyEntry.setCreatedAt(now);
        historyRepository.save(historyEntry);
        historyRepository.trimHistory(user.getId(), policy.historyDepth());

        resetTokenRepository.invalidateOutstanding(user.getId(), now);
        int revoked = refreshTokenService.revokeAllForUser(user.getId(), reason);
        log.info("Password updated for user {}; revoked {} session(s)", user.getId(), revoked);
    }

    private void assertNotInHistory(User user, String candidate, int depth) {
        if (depth <= 0) {
            return;
        }
        boolean reused = historyRepository
                .findRecent(user.getId(), PageRequest.of(0, depth)).stream()
                .anyMatch(entry -> passwordEncoder.matches(candidate, entry.getPasswordHash()));
        if (reused) {
            throw new BusinessException(ErrorCode.PASSWORD_REUSED,
                    "You cannot reuse one of your last %d passwords.".formatted(depth));
        }
    }

    private AuditEvent authAudit(User user, String eventType, boolean success, String message,
                                 String clientIp, AuditSeverity severity) {
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
                .build();
    }
}

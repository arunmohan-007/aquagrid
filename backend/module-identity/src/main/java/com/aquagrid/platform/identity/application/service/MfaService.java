package com.aquagrid.platform.identity.application.service;

import com.aquagrid.platform.common.audit.AuditCategory;
import com.aquagrid.platform.common.audit.AuditEvent;
import com.aquagrid.platform.common.audit.AuditEventTypes;
import com.aquagrid.platform.common.audit.AuditService;
import com.aquagrid.platform.common.audit.AuditSeverity;
import com.aquagrid.platform.common.crypto.CryptoService;
import com.aquagrid.platform.common.crypto.Hashes;
import com.aquagrid.platform.common.crypto.TokenGenerator;
import com.aquagrid.platform.common.crypto.TotpService;
import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.identity.domain.enums.TokenRevocationReason;
import com.aquagrid.platform.identity.domain.model.MfaRecoveryCode;
import com.aquagrid.platform.identity.domain.model.User;
import com.aquagrid.platform.identity.infrastructure.config.IdentityProperties;
import com.aquagrid.platform.identity.infrastructure.persistence.MfaRecoveryCodeRepository;
import com.aquagrid.platform.identity.infrastructure.persistence.UserRepository;
import com.aquagrid.platform.identity.web.dto.AuthResponses;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * TOTP enrolment and verification.
 *
 * <p>Design points that distinguish this from a demo implementation:
 * <ul>
 *   <li><b>The seed is encrypted at rest</b> with AES-256-GCM. A TOTP seed is a permanent
 *       credential — stored in plaintext, a database dump lets an attacker generate valid codes for
 *       every user forever, which is strictly worse than a leaked password hash.</li>
 *   <li><b>Enrolment is two-phase.</b> {@code setup} returns a seed but does not activate anything;
 *       {@code activate} requires a live code first. Without this, a user who scans the QR
 *       incorrectly is locked out of their own account permanently.</li>
 *   <li><b>Recovery codes are single-use and hashed</b>, shown exactly once. They exist so that a
 *       lost phone does not force an administrative MFA reset — itself a social-engineering target.</li>
 *   <li><b>Changing MFA state revokes every session</b>, because a second factor that leaves the
 *       attacker's existing sessions alive protects nothing.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MfaService {

    private final UserRepository userRepository;
    private final MfaRecoveryCodeRepository recoveryCodeRepository;
    private final RefreshTokenService refreshTokenService;
    private final TotpService totpService;
    private final CryptoService cryptoService;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final IdentityProperties properties;
    private final Clock clock;

    /** Phase one: generate a seed and the provisioning URI. Nothing is activated yet. */
    @Transactional
    public AuthResponses.MfaSetup beginEnrolment(UUID userId) {
        User user = requireUser(userId);
        if (user.isMfaEnabled()) {
            throw new BusinessException(ErrorCode.MFA_ALREADY_ENABLED,
                    "Multi-factor authentication is already active. Disable it first to re-enrol.");
        }

        String secret = totpService.generateSecret();
        // Stored encrypted but with mfaEnabled still false, so a half-finished enrolment can never
        // lock the user out and a stale seed is simply overwritten on the next attempt.
        user.setMfaSecret(cryptoService.encrypt(secret));
        user.setMfaConfirmedAt(null);

        return AuthResponses.MfaSetup.builder()
                .secret(secret)
                .provisioningUri(totpService.buildProvisioningUri(
                        properties.mfa().issuer() + " (" + user.getOrganization().getCode() + ")",
                        user.getEmail(), secret))
                .build();
    }

    /** Phase two: prove the authenticator works, then activate and issue recovery codes. */
    @Transactional
    public AuthResponses.MfaActivation activate(UUID userId, String code, String clientIp) {
        User user = requireUser(userId);
        if (user.isMfaEnabled()) {
            throw new BusinessException(ErrorCode.MFA_ALREADY_ENABLED);
        }
        if (user.getMfaSecret() == null) {
            throw new BusinessException(ErrorCode.MFA_NOT_ENROLLED,
                    "Start enrolment before activating multi-factor authentication.");
        }

        String secret = cryptoService.decrypt(user.getMfaSecret());
        if (!totpService.verify(secret, code)) {
            throw new BusinessException(ErrorCode.MFA_CODE_INVALID,
                    "That code is not valid. Check that your device's clock is accurate.");
        }

        Instant now = clock.instant();
        user.enableMfa(user.getMfaSecret(), now);
        List<String> recoveryCodes = regenerateRecoveryCodes(user, now);

        // Any session that existed before the factor was added predates it and must not survive.
        refreshTokenService.revokeAllForUser(user.getId(), TokenRevocationReason.MFA_CHANGED);

        auditService.record(AuditEvent.builder()
                .organizationId(user.getOrganization().getId())
                .actorUserId(user.getId())
                .actorUsername(user.getUsername())
                .eventType(AuditEventTypes.MFA_ENROLLED)
                .category(AuditCategory.SECURITY)
                .severity(AuditSeverity.INFO)
                .resourceType("User")
                .resourceId(user.getId().toString())
                .success(true)
                .message("TOTP multi-factor authentication activated")
                .clientIp(clientIp)
                .build());

        return AuthResponses.MfaActivation.builder()
                .mfaEnabled(true)
                .recoveryCodes(recoveryCodes)
                .build();
    }

    @Transactional
    public void disable(UUID userId, String password, String code, String clientIp) {
        User user = requireUser(userId);
        if (!user.isMfaEnabled()) {
            throw new BusinessException(ErrorCode.MFA_NOT_ENROLLED);
        }
        // Both factors are required to remove a factor: a stolen session alone must not be able to
        // weaken the account's security posture.
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        if (!verifyCode(user, code, clientIp)) {
            throw new BusinessException(ErrorCode.MFA_CODE_INVALID);
        }

        user.disableMfa();
        recoveryCodeRepository.deleteAllForUser(user.getId());
        refreshTokenService.revokeAllForUser(user.getId(), TokenRevocationReason.MFA_CHANGED);

        auditService.record(AuditEvent.builder()
                .organizationId(user.getOrganization().getId())
                .actorUserId(user.getId())
                .actorUsername(user.getUsername())
                .eventType(AuditEventTypes.MFA_DISABLED)
                .category(AuditCategory.SECURITY)
                .severity(AuditSeverity.WARNING)
                .resourceType("User")
                .resourceId(user.getId().toString())
                .success(true)
                .message("Multi-factor authentication disabled")
                .clientIp(clientIp)
                .build());
    }

    /**
     * Verifies a TOTP code, falling back to a single-use recovery code.
     *
     * <p>The recovery code is consumed on use, and its consumption is audited at {@code WARNING}:
     * a legitimate user burns one rarely, so a cluster of them is a signal worth surfacing.
     */
    @Transactional
    public boolean verifyCode(User user, String code, String clientIp) {
        if (user.getMfaSecret() == null) {
            return false;
        }
        String normalised = code == null ? "" : code.trim();
        if (totpService.verify(cryptoService.decrypt(user.getMfaSecret()), normalised)) {
            return true;
        }
        return consumeRecoveryCode(user, normalised, clientIp);
    }

    private boolean consumeRecoveryCode(User user, String code, String clientIp) {
        String candidate = code.toUpperCase(Locale.ROOT).replace(" ", "");
        return recoveryCodeRepository.findUnused(user.getId(), Hashes.sha256Hex(candidate))
                .map(recoveryCode -> {
                    recoveryCode.setUsedAt(clock.instant());
                    recoveryCode.setUsedIp(clientIp);
                    long remaining = recoveryCodeRepository.countUnused(user.getId()) - 1;

                    auditService.record(AuditEvent.builder()
                            .organizationId(user.getOrganization().getId())
                            .actorUserId(user.getId())
                            .actorUsername(user.getUsername())
                            .eventType(AuditEventTypes.MFA_RECOVERY_CODE_USED)
                            .category(AuditCategory.SECURITY)
                            .severity(AuditSeverity.WARNING)
                            .resourceType("User")
                            .resourceId(user.getId().toString())
                            .success(true)
                            .message("A multi-factor recovery code was used")
                            .clientIp(clientIp)
                            .metadata(Map.of("remainingRecoveryCodes", Math.max(0, remaining)))
                            .build());
                    return true;
                })
                .orElse(false);
    }

    private List<String> regenerateRecoveryCodes(User user, Instant now) {
        recoveryCodeRepository.deleteAllForUser(user.getId());
        List<String> plaintext = new ArrayList<>();
        List<MfaRecoveryCode> entities = new ArrayList<>();
        for (int i = 0; i < properties.mfa().recoveryCodes(); i++) {
            String code = TokenGenerator.recoveryCode();
            plaintext.add(code);

            MfaRecoveryCode entity = new MfaRecoveryCode();
            entity.setUser(user);
            entity.setCodeHash(Hashes.sha256Hex(code));
            entity.setCreatedAt(now);
            entities.add(entity);
        }
        recoveryCodeRepository.saveAll(entities);
        return plaintext;
    }

    private User requireUser(UUID userId) {
        return userRepository.findByIdWithAuthorities(userId)
                .orElseThrow(() -> new com.aquagrid.platform.common.error.ResourceNotFoundException(
                        "User", userId));
    }
}

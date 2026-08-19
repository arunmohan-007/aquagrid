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
import com.aquagrid.platform.identity.domain.model.RefreshToken;
import com.aquagrid.platform.identity.domain.model.User;
import com.aquagrid.platform.identity.infrastructure.config.IdentityProperties;
import com.aquagrid.platform.identity.infrastructure.persistence.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Issues, rotates and revokes refresh tokens — that is, owns the session lifecycle.
 *
 * <h2>Why rotation with reuse detection</h2>
 * A long-lived refresh token is the most valuable credential in the system: it converts into access
 * tokens indefinitely. If one leaks — through a compromised device, a proxy log, a backup — a
 * static token gives the attacker permanent access, silently.
 *
 * <p>With rotation, every use invalidates the token and issues a successor in the same family. The
 * legitimate client always holds the newest token. An attacker who steals a copy therefore faces
 * two outcomes, and both are detectable:
 * <ul>
 *   <li>the attacker uses it first — the real user's next refresh presents an already-rotated
 *       token;</li>
 *   <li>the real user uses it first — the attacker presents an already-rotated token.</li>
 * </ul>
 * Either way, an already-rotated token is presented, which cannot happen in normal operation. The
 * response is to revoke the entire family: the attacker is locked out, the user is signed out of
 * that device, and a {@code CRITICAL} audit event is raised.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository repository;
    private final IdentityProperties properties;
    private final AuditService auditService;
    private final Clock clock;

    /** A newly minted token: the plaintext goes to the client, the entity stays in the database. */
    public record IssuedRefreshToken(String plaintext, RefreshToken entity) {
    }

    /** Starts a new token family — one login on one device. */
    @Transactional
    public IssuedRefreshToken issueNewFamily(User user, String clientIp, String userAgent,
                                             String deviceLabel) {
        enforceSessionLimit(user);
        Instant now = clock.instant();
        return persist(user, UUID.randomUUID(), now, clientIp, userAgent, deviceLabel);
    }

    /**
     * Exchanges a presented token for its successor.
     *
     * @throws BusinessException {@code AUTH_REFRESH_TOKEN_REUSED} when the presented token has
     *                           already been rotated or revoked — the family is revoked first
     */
    /**
     * {@code noRollbackFor}: every refusal below revokes something first, and the revocation is the
     * actual security response — the exception is only how it is reported. A default rollback rule
     * would undo the family revocation on reuse, the expiry stamp on an expired token, and the
     * session sweep on a disabled account, in each case leaving a live credential behind.
     *
     * <p>Declared here as well as on the caller, so the guarantee holds if this is ever invoked
     * outside {@code AuthenticationService.refresh} — when this method joins an existing
     * transaction, it is the outermost boundary's rule that governs, and only this annotation makes
     * it safe to be that boundary.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public IssuedRefreshToken rotate(String presentedToken, String clientIp, String userAgent) {
        Instant now = clock.instant();
        RefreshToken existing = repository.findByTokenHash(Hashes.sha256Hex(presentedToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_TOKEN_INVALID,
                        "Your session has expired. Please sign in again."));

        if (existing.isRevoked()) {
            handleReuse(existing, clientIp, userAgent);
        }
        if (existing.isExpired(now)) {
            existing.revoke(now, TokenRevocationReason.EXPIRED);
            throw new BusinessException(ErrorCode.AUTH_TOKEN_EXPIRED,
                    "Your session has expired. Please sign in again.");
        }

        Instant familyStartedAt = existing.getFamilyStartedAt();
        if (familyStartedAt.plus(properties.refreshToken().absoluteTtl()).isBefore(now)) {
            repository.revokeFamily(existing.getFamilyId(), now, TokenRevocationReason.EXPIRED);
            throw new BusinessException(ErrorCode.AUTH_TOKEN_EXPIRED,
                    "Your session has expired. Please sign in again.");
        }

        User user = existing.getUser();
        if (!user.canAuthenticate(now)) {
            repository.revokeAllForUser(user.getId(), now, TokenRevocationReason.USER_DISABLED);
            throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
        }

        IssuedRefreshToken successor = persist(user, existing.getFamilyId(), familyStartedAt, clientIp,
                userAgent, existing.getDeviceLabel());

        existing.setLastUsedAt(now);
        existing.setReplacedById(successor.entity().getId());
        existing.revoke(now, TokenRevocationReason.ROTATED);

        return successor;
    }

    /**
     * Reacts to a token that was already consumed or revoked.
     *
     * <p>The whole family is revoked, not merely the presented token: we cannot tell whether the
     * thief or the legitimate user is holding the current token, so the only safe action is to
     * invalidate every descendant and require a fresh sign-in.
     */
    private void handleReuse(RefreshToken presented, String clientIp, String userAgent) {
        Instant now = clock.instant();
        int revoked = repository.revokeFamily(presented.getFamilyId(), now,
                TokenRevocationReason.REUSE_DETECTED);

        User user = presented.getUser();
        log.warn("Refresh token reuse detected for user {} (family {}); revoked {} token(s)",
                user.getId(), presented.getFamilyId(), revoked);

        auditService.record(AuditEvent.builder()
                .organizationId(user.getOrganization().getId())
                .actorUserId(user.getId())
                .actorUsername(user.getUsername())
                .eventType(AuditEventTypes.TOKEN_REUSE_DETECTED)
                .category(AuditCategory.SECURITY)
                .severity(AuditSeverity.CRITICAL)
                .resourceType("RefreshTokenFamily")
                .resourceId(presented.getFamilyId().toString())
                .success(false)
                .message("Refresh token reuse detected; all sessions in the family were revoked")
                .clientIp(clientIp)
                .userAgent(userAgent)
                .metadata(Map.of(
                        "familyId", presented.getFamilyId().toString(),
                        "revokedTokens", revoked,
                        "originalIssuedAt", presented.getIssuedAt().toString(),
                        "previousRevocationReason",
                        String.valueOf(presented.getRevokedReason())))
                .build());

        throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_REUSED,
                "This session was invalidated for security reasons. Please sign in again.");
    }

    @Transactional
    public void revokeByToken(String presentedToken, TokenRevocationReason reason) {
        repository.findByTokenHash(Hashes.sha256Hex(presentedToken))
                .ifPresent(token -> token.revoke(clock.instant(), reason));
    }

    @Transactional
    public int revokeAllForUser(UUID userId, TokenRevocationReason reason) {
        return repository.revokeAllForUser(userId, clock.instant(), reason);
    }

    @Transactional(readOnly = true)
    public List<RefreshToken> listActiveSessions(UUID userId) {
        return repository.findActiveByUser(userId, clock.instant());
    }

    @Transactional
    public void revokeSession(UUID userId, UUID sessionId) {
        RefreshToken token = repository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new com.aquagrid.platform.common.error.ResourceNotFoundException(
                        "Session", sessionId));
        token.revoke(clock.instant(), TokenRevocationReason.ADMIN_REVOKED);
    }

    private IssuedRefreshToken persist(User user, UUID familyId, Instant familyStartedAt, String clientIp,
                                       String userAgent, String deviceLabel) {
        Instant now = clock.instant();
        String plaintext = TokenGenerator.opaqueToken();

        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setFamilyId(familyId);
        token.setFamilyStartedAt(familyStartedAt);
        token.setTokenHash(Hashes.sha256Hex(plaintext));
        token.setIssuedAt(now);
        token.setExpiresAt(now.plus(properties.refreshToken().ttl()));
        token.setClientIp(clientIp);
        token.setUserAgent(truncate(userAgent, 512));
        token.setDeviceLabel(truncate(deviceLabel, 120));

        return new IssuedRefreshToken(plaintext, repository.saveAndFlush(token));
    }

    /**
     * Caps concurrent sessions per user by revoking the oldest.
     *
     * <p>Bounds the blast radius of a credential compromise and keeps the sessions screen
     * meaningful. Oldest-first rather than refusing the new login: refusing would let an attacker
     * lock a user out of their own account by opening sessions.
     */
    private void enforceSessionLimit(User user) {
        int limit = properties.refreshToken().maxSessionsPerUser();
        List<RefreshToken> active = repository.findActiveByUser(user.getId(), clock.instant());
        if (active.size() < limit) {
            return;
        }
        active.stream()
                .sorted(Comparator.comparing(RefreshToken::getIssuedAt))
                .limit(active.size() - limit + 1L)
                .forEach(token -> token.revoke(clock.instant(), TokenRevocationReason.ADMIN_REVOKED));
        log.info("Session limit ({}) reached for user {}; revoked the oldest session(s)",
                limit, user.getId());
    }

    /**
     * Removes rows for tokens that expired beyond the forensic grace period.
     *
     * <p>Rows are not deleted at expiry: a reuse attempt against a just-expired token must still be
     * recognisable as reuse rather than as an unknown token.
     */
    @Scheduled(cron = "${aquagrid.identity.refresh-token.cleanup-cron:0 30 3 * * *}")
    @Transactional
    public void purgeExpiredTokens() {
        Instant threshold = clock.instant().minus(properties.refreshToken().cleanupGracePeriod());
        int deleted = repository.deleteExpiredBefore(threshold);
        if (deleted > 0) {
            log.info("Purged {} refresh token(s) that expired before {}", deleted, threshold);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}

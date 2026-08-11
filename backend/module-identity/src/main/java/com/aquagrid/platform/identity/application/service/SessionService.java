package com.aquagrid.platform.identity.application.service;

import com.aquagrid.platform.common.audit.AuditCategory;
import com.aquagrid.platform.common.audit.AuditEvent;
import com.aquagrid.platform.common.audit.AuditEventTypes;
import com.aquagrid.platform.common.audit.AuditService;
import com.aquagrid.platform.common.audit.AuditSeverity;
import com.aquagrid.platform.identity.domain.model.RefreshToken;
import com.aquagrid.platform.identity.web.dto.AuthResponses;
import com.aquagrid.platform.security.core.AuthenticatedPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The "where am I signed in" screen.
 *
 * <p>More than a convenience: it is how a user discovers a session they do not recognise and ends
 * it themselves, without a support ticket. The current session is flagged so nobody accidentally
 * revokes the device they are holding, and it is identified by the token family carried in the
 * access token's {@code sid} claim — not by a client-supplied hint.
 */
@Service
@RequiredArgsConstructor
public class SessionService {

    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<AuthResponses.Session> listSessions(AuthenticatedPrincipal principal) {
        return refreshTokenService.listActiveSessions(principal.userId()).stream()
                .map(token -> toDto(token, principal.sessionId()))
                .toList();
    }

    @Transactional
    public void revokeSession(AuthenticatedPrincipal principal, UUID sessionId, String clientIp) {
        refreshTokenService.revokeSession(principal.userId(), sessionId);
        auditService.record(AuditEvent.builder()
                .organizationId(principal.organizationId())
                .actorUserId(principal.userId())
                .actorUsername(principal.username())
                .eventType(AuditEventTypes.SESSION_REVOKED)
                .category(AuditCategory.SECURITY)
                .severity(AuditSeverity.INFO)
                .resourceType("Session")
                .resourceId(sessionId.toString())
                .success(true)
                .message("Session revoked by the user")
                .clientIp(clientIp)
                .build());
    }

    private AuthResponses.Session toDto(RefreshToken token, UUID currentFamilyId) {
        return AuthResponses.Session.builder()
                .id(token.getId())
                .deviceLabel(token.getDeviceLabel())
                .userAgent(token.getUserAgent())
                .clientIp(token.getClientIp())
                .issuedAt(token.getIssuedAt())
                .lastUsedAt(token.getLastUsedAt())
                .expiresAt(token.getExpiresAt())
                .current(token.getFamilyId().equals(currentFamilyId))
                .build();
    }
}

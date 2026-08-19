package com.aquagrid.platform.identity.domain.model;

import com.aquagrid.platform.identity.domain.enums.TokenRevocationReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One issued refresh token — that is, one device session.
 *
 * <p>Only the SHA-256 hash of the token is persisted; the token itself exists solely in the
 * client's cookie. A database dump therefore contains no usable sessions.
 *
 * <p>{@code familyId} groups every token descended from a single login. Rotation creates a
 * successor in the same family and marks the predecessor {@code ROTATED}. If a token that has
 * already been rotated (or revoked) is presented, the only explanation is that a copy leaked, so
 * the entire family is revoked — the legitimate user is signed out of that device and the attacker
 * gains nothing.
 */
@Getter
@Setter
@Entity
@Table(name = "refresh_tokens", schema = "identity")
public class RefreshToken {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    /**
     * When this token's family — one login on one device — was born. Copied unchanged onto every
     * successor produced by rotation, so it lets an absolute session-age cap be enforced from the
     * original login, independent of the sliding per-token {@code expires_at}.
     */
    @Column(name = "family_started_at", nullable = false, updatable = false)
    private Instant familyStartedAt;


    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_reason", length = 30)
    private TokenRevocationReason revokedReason;

    @Column(name = "replaced_by_id")
    private UUID replacedById;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "client_ip", columnDefinition = "inet")
    private String clientIp;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "device_label", length = 120)
    private String deviceLabel;

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isUsable(Instant now) {
        return !isRevoked() && !isExpired(now);
    }

    public void revoke(Instant now, TokenRevocationReason reason) {
        if (revokedAt == null) {
            this.revokedAt = now;
            this.revokedReason = reason;
        }
    }
}

package com.aquagrid.platform.identity.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A pending user invitation.
 *
 * <p>An administrator invites a person who has not yet set a password. The row carries the
 * materialised user attributes (so activation is a single insert, not a read-modify-write on the
 * invitation) and a SHA-256 hash of the opaque token (so a database dump yields no working
 * invitations). It is consumed exactly once: on activation it is marked accepted and never reused.
 *
 * <p>Lifecycle invariants — accepted XOR revoked, never both — are enforced by a CHECK constraint
 * in {@code V1104} and re-asserted by {@link #accept} and {@link #revoke}, because a rule that lives
 * only in the database is a rule a future code path can still violate.
 */
@Getter
@Setter
@Entity
@Table(name = "user_invitations", schema = "identity")
public class UserInvitation {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false, updatable = false)
    private Organization organization;

    @Column(name = "email", nullable = false, length = 320, updatable = false, columnDefinition = "citext")
    private String email;

    @Column(name = "full_name", nullable = false, length = 200, updatable = false)
    private String fullName;

    @Column(name = "username", nullable = false, length = 60, updatable = false, columnDefinition = "citext")
    private String username;

    @Column(name = "job_title", length = 120, updatable = false)
    private String jobTitle;

    @Column(name = "phone", length = 40, updatable = false)
    private String phone;

    
    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    /** Role codes to grant on activation. Stored as JSONB; materialised onto user_roles then. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "role_codes", nullable = false, columnDefinition = "jsonb")
    private Set<String> roleCodes = new LinkedHashSet<>();

    @Column(name = "invited_by", nullable = false, updatable = false)
    private UUID invitedBy;

    @Column(name = "invited_at", nullable = false, updatable = false)
    private Instant invitedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "accepted_by")
    private UUID acceptedBy;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "client_ip", columnDefinition = "inet", updatable = false)
    private String clientIp;

    // --- Lifecycle ------------------------------------------------------------------------------

    public boolean isOutstanding() {
        return acceptedAt == null && revokedAt == null;
    }

    public boolean isExpired(Instant now) {
        return expiresAt.isBefore(now);
    }

    /** True only when the invitation can still be activated. */
    public boolean isActivatable(Instant now) {
        return isOutstanding() && !isExpired(now);
    }

    /**
     * Marks the invitation consumed.
     *
     * @throws IllegalStateException if already consumed — callers must guard with
     *         {@link #isActivatable} first; this is a defence-in-depth assertion, not control flow.
     */
    public void accept(Instant now, UUID activatingUserId) {
        if (!isOutstanding()) {
            throw new IllegalStateException("Invitation " + id + " is no longer outstanding");
        }
        this.acceptedAt = now;
        this.acceptedBy = activatingUserId;
    }

    public void revoke(Instant now, UUID revokedBy) {
        if (!isOutstanding()) {
            throw new IllegalStateException("Invitation " + id + " is no longer outstanding");
        }
        this.revokedAt = now;
        this.revokedBy = revokedBy;
    }
}

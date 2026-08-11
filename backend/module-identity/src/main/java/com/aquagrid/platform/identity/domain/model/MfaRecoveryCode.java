package com.aquagrid.platform.identity.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * A single-use MFA recovery code.
 *
 * <p>Issued as a batch at enrolment, displayed exactly once, and stored hashed. Without these, a
 * lost phone means an administrator must disable MFA for the user — a support path that is itself
 * a social-engineering target.
 */
@Getter
@Setter
@Entity
@Table(name = "mfa_recovery_codes", schema = "identity")
public class MfaRecoveryCode {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    
    @Column(name = "code_hash", nullable = false, length = 64, updatable = false)
    private String codeHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "used_ip", columnDefinition = "inet")
    private String usedIp;

    public boolean isUsed() {
        return usedAt != null;
    }
}

package com.aquagrid.platform.identity.domain.model;

import com.aquagrid.platform.identity.domain.enums.LoginOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One authentication attempt.
 *
 * <p>Deliberately not a {@code @ManyToOne} to {@link User}: an attempt against an identifier that
 * matches no account still has to be recorded, and that is precisely the row an operator wants
 * when investigating enumeration. The user id is a plain nullable column.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "login_attempts", schema = "identity")
public class LoginAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "identifier", nullable = false, length = 320)
    private String identifier;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 30)
    private LoginOutcome outcome;

    @Column(name = "failure_reason", length = 60)
    private String failureReason;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "client_ip", columnDefinition = "inet")
    private String clientIp;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "mfa_used", nullable = false)
    private boolean mfaUsed;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

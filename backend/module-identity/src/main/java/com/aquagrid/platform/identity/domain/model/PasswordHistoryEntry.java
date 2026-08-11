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

import java.time.Instant;
import java.util.UUID;

/**
 * A superseded password hash, retained so that reuse can be refused.
 *
 * <p>Full BCrypt hashes are stored — never a reversible transformation of the password — so the
 * "have you used this before" check is a hash comparison and the history is worth no more to an
 * attacker than the current hash.
 */
@Getter
@Setter
@Entity
@Table(name = "password_history", schema = "identity")
public class PasswordHistoryEntry {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "password_hash", nullable = false, length = 120, updatable = false)
    private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

package com.aquagrid.platform.identity.infrastructure.persistence;

import com.aquagrid.platform.identity.domain.enums.LoginOutcome;
import com.aquagrid.platform.identity.domain.model.LoginAttempt;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Write path for authentication attempts, plus the read queries that back the security
 * screens in Modules 2 and 30.
 *
 * <p>Note that lockout itself does <b>not</b> read from here: the counter lives on the user row,
 * because evaluating a lock by aggregating this table on every sign-in would put a growing scan on
 * the hot authentication path.
 */
@Repository
public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, Long> {

    /** Recent activity for one account — the "was this you?" panel. */
    @Query("SELECT a FROM LoginAttempt a WHERE a.userId = :userId ORDER BY a.createdAt DESC")
    List<LoginAttempt> findRecentForUser(@Param("userId") UUID userId, Pageable pageable);

    /**
     * Distinct accounts targeted from one address.
     *
     * <p>A high count is the signature of credential stuffing: many identifiers, few repeats. It is
     * invisible to per-account lockout, which is exactly why it is measured separately.
     */
    @Query("SELECT COUNT(DISTINCT a.identifier) FROM LoginAttempt a "
            + "WHERE a.clientIp = :clientIp AND a.createdAt > :since")
    long countDistinctIdentifiersFromIp(@Param("clientIp") String clientIp,
                                        @Param("since") Instant since);

    long countByClientIpAndOutcomeNotAndCreatedAtAfter(String clientIp, LoginOutcome outcome,
                                                       Instant since);
}

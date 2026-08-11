package com.aquagrid.platform.identity.infrastructure.persistence;

import com.aquagrid.platform.identity.domain.enums.TokenRevocationReason;
import com.aquagrid.platform.identity.domain.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** Lookup by the indexed hash — the token itself is never stored, so this is the only path in. */
    @Query("SELECT t FROM RefreshToken t JOIN FETCH t.user u JOIN FETCH u.organization "
            + "WHERE t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHash(@Param("tokenHash") String tokenHash);

    @Query("SELECT t FROM RefreshToken t WHERE t.user.id = :userId AND t.revokedAt IS NULL "
            + "AND t.expiresAt > :now ORDER BY t.issuedAt DESC")
    List<RefreshToken> findActiveByUser(@Param("userId") UUID userId, @Param("now") Instant now);

    Optional<RefreshToken> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Revokes every live token in a family. Used on reuse detection, where speed matters more than
     * loading entities: the attacker is holding a valid token right now.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now, t.revokedReason = :reason "
            + "WHERE t.familyId = :familyId AND t.revokedAt IS NULL")
    int revokeFamily(@Param("familyId") UUID familyId,
                     @Param("now") Instant now,
                     @Param("reason") TokenRevocationReason reason);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now, t.revokedReason = :reason "
            + "WHERE t.user.id = :userId AND t.revokedAt IS NULL")
    int revokeAllForUser(@Param("userId") UUID userId,
                         @Param("now") Instant now,
                         @Param("reason") TokenRevocationReason reason);

    /**
     * Deletes tokens that expired long enough ago to be forensically uninteresting.
     *
     * <p>Rows are kept for a grace period rather than deleted at expiry, so that a reuse attempt
     * against a just-expired token is still recognisable as reuse rather than as an unknown token.
     */
    @Modifying
    @Query("DELETE FROM RefreshToken t WHERE t.expiresAt < :threshold")
    int deleteExpiredBefore(@Param("threshold") Instant threshold);
}

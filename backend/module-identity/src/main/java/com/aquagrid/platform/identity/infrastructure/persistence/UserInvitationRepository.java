package com.aquagrid.platform.identity.infrastructure.persistence;

import com.aquagrid.platform.identity.domain.model.UserInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserInvitationRepository extends JpaRepository<UserInvitation, UUID> {

    /**
     * Loads an outstanding invitation by token hash. The {@code acceptedAt IS NULL} predicate is
     * belt-and-braces with the lifecycle CHECK — a consumed token must not be reactivatable even if
     * a future code path forgets to set the timestamp.
     */
    @Query("""
            SELECT i FROM UserInvitation i
            WHERE i.tokenHash = :tokenHash
              AND i.acceptedAt IS NULL
              AND i.revokedAt IS NULL
            """)
    Optional<UserInvitation> findOutstandingByTokenHash(@Param("tokenHash") String tokenHash);

    @Query("""
            SELECT count(i) > 0 FROM UserInvitation i
            WHERE i.organization.id = :organizationId
              AND lower(i.email) = lower(:email)
              AND i.acceptedAt IS NULL
              AND i.revokedAt IS NULL
            """)
    boolean hasOutstandingForEmail(@Param("organizationId") UUID organizationId,
                                   @Param("email") String email);

    /** Counts outstanding invitations for a tenant — for the list view and tenant dashboards. */
    long countByOrganizationIdAndAcceptedAtIsNullAndRevokedAtIsNull(UUID organizationId);

    /**
     * Bulk-expires stale outstanding invitations. Run by a scheduled reaper so the table does not
     * accumulate rows that can never be activated. Returns the count affected, for logging.
     */
    @Modifying
    @Query("""
            UPDATE UserInvitation i
            SET i.revokedAt = :now
            WHERE i.acceptedAt IS NULL AND i.revokedAt IS NULL AND i.expiresAt < :now
            """)
    int reapExpired(@Param("now") Instant now);
}

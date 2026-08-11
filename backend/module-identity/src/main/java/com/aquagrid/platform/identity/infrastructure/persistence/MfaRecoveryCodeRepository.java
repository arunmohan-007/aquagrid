package com.aquagrid.platform.identity.infrastructure.persistence;

import com.aquagrid.platform.identity.domain.model.MfaRecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCode, UUID> {

    @Query("SELECT c FROM MfaRecoveryCode c WHERE c.user.id = :userId AND c.codeHash = :codeHash "
            + "AND c.usedAt IS NULL")
    Optional<MfaRecoveryCode> findUnused(@Param("userId") UUID userId,
                                         @Param("codeHash") String codeHash);

    @Query("SELECT COUNT(c) FROM MfaRecoveryCode c WHERE c.user.id = :userId AND c.usedAt IS NULL")
    long countUnused(@Param("userId") UUID userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM MfaRecoveryCode c WHERE c.user.id = :userId")
    int deleteAllForUser(@Param("userId") UUID userId);
}

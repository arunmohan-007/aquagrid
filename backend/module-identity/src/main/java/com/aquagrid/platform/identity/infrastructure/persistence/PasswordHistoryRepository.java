package com.aquagrid.platform.identity.infrastructure.persistence;

import com.aquagrid.platform.identity.domain.model.PasswordHistoryEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PasswordHistoryRepository extends JpaRepository<PasswordHistoryEntry, UUID> {

    @Query("SELECT h FROM PasswordHistoryEntry h WHERE h.user.id = :userId ORDER BY h.createdAt DESC")
    List<PasswordHistoryEntry> findRecent(@Param("userId") UUID userId, Pageable pageable);

    /** Trims history beyond the configured depth so the table does not grow without bound. */
    @Modifying
    @Query(value = """
            DELETE FROM identity.password_history
            WHERE user_id = :userId
              AND id NOT IN (
                  SELECT id FROM identity.password_history
                  WHERE user_id = :userId
                  ORDER BY created_at DESC
                  LIMIT :keep)
            """, nativeQuery = true)
    int trimHistory(@Param("userId") UUID userId, @Param("keep") int keep);
}

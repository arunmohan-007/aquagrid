package com.aquagrid.platform.iot.receiver.infrastructure.persistence;

import com.aquagrid.platform.iot.receiver.domain.model.ReplayCacheEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface ReplayCacheRepository extends JpaRepository<ReplayCacheEntry, UUID> {

    /**
     * Claims a packet identity, atomically.
     *
     * <p>The whole replay check is this one statement, and it is written as an insert rather than a
     * read-then-write on purpose. "Have I seen this?" followed by "remember it" is a race with a
     * window between the two, and the attack the check exists to stop — the same captured packet
     * delivered twice, fast — is precisely what wins that race. Letting the unique index adjudicate
     * makes the decision atomic: exactly one caller inserts a row.
     *
     * @return 1 when the identity was new and is now claimed, 0 when it was already present — which
     *         is the replay
     */
    @Modifying
    @Query(value = """
            INSERT INTO iot.receiver_replay_cache
                (id, device_id, replay_key, transport, first_seen_at, expires_at, version)
            VALUES (gen_random_uuid(), :deviceId, :replayKey, :transport, :now, :expiresAt, 0)
            ON CONFLICT (device_id, replay_key) DO NOTHING
            """, nativeQuery = true)
    int claim(@Param("deviceId") UUID deviceId,
              @Param("replayKey") String replayKey,
              @Param("transport") String transport,
              @Param("now") Instant now,
              @Param("expiresAt") Instant expiresAt);

    boolean existsByDeviceIdAndReplayKey(UUID deviceId, String replayKey);

    /**
     * Expiry sweep, batched.
     *
     * <p>PostgreSQL has no TTL, so nothing retires these rows but this job — and at fleet scale the
     * table grows by millions a day. Batched for the same reason as the packet-log sweep: an
     * unbounded delete holds a lock long enough to stall ingestion.
     */
    @Modifying
    @Query(value = """
            DELETE FROM iot.receiver_replay_cache
            WHERE id IN (SELECT id FROM iot.receiver_replay_cache
                         WHERE expires_at < :now ORDER BY expires_at LIMIT :batchSize)
            """, nativeQuery = true)
    int deleteExpired(@Param("now") Instant now, @Param("batchSize") int batchSize);
}

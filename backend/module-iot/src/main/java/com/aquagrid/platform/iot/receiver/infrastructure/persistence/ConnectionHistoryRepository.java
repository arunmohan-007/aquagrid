package com.aquagrid.platform.iot.receiver.infrastructure.persistence;

import com.aquagrid.platform.iot.receiver.domain.model.ConnectionHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface ConnectionHistoryRepository extends JpaRepository<ConnectionHistory, UUID> {

    @Query("""
            SELECT h FROM ConnectionHistory h
            WHERE h.organizationId = :organizationId
              AND (cast(:deviceId as uuid) IS NULL OR h.deviceId = :deviceId)
              AND (cast(:transport as string) IS NULL OR h.transport = :transport)
              AND h.occurredAt >= :from
            ORDER BY h.occurredAt DESC
            """)
    Page<ConnectionHistory> search(@Param("organizationId") UUID organizationId,
                                   @Param("deviceId") UUID deviceId,
                                   @Param("transport") String transport,
                                   @Param("from") Instant from,
                                   Pageable pageable);

    /**
     * Reconnects per device in a window — the flapping-link query.
     *
     * <p>Counting {@code CONNECTED} events rather than sessions is what makes it work: a link that
     * reconnects forty times has one open session and forty rows here, and only the second number
     * describes the fault.
     */
    @Query("""
            SELECT h.deviceId, COUNT(h) FROM ConnectionHistory h
            WHERE h.organizationId = :organizationId
              AND h.event = 'CONNECTED'
              AND h.occurredAt >= :from
              AND h.deviceId IS NOT NULL
            GROUP BY h.deviceId
            HAVING COUNT(h) >= :threshold
            ORDER BY COUNT(h) DESC
            """)
    java.util.List<Object[]> findFlappingDevices(@Param("organizationId") UUID organizationId,
                                                 @Param("from") Instant from,
                                                 @Param("threshold") long threshold);

    @Modifying
    @Query("DELETE FROM ConnectionHistory h WHERE h.occurredAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}

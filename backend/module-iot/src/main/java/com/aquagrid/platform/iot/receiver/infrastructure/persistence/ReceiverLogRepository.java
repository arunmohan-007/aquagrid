package com.aquagrid.platform.iot.receiver.infrastructure.persistence;

import com.aquagrid.platform.iot.receiver.domain.model.ReceiverLog;
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
public interface ReceiverLogRepository extends JpaRepository<ReceiverLog, UUID> {

    @Query("""
            SELECT l FROM ReceiverLog l
            WHERE (cast(:transport as string) IS NULL OR l.transport = :transport)
              AND (cast(:eventType as string) IS NULL OR l.eventType = :eventType)
              AND (cast(:severity as string) IS NULL OR l.severity = :severity)
              AND l.occurredAt >= :from
            ORDER BY l.occurredAt DESC
            """)
    Page<ReceiverLog> search(@Param("transport") String transport,
                             @Param("eventType") String eventType,
                             @Param("severity") String severity,
                             @Param("from") Instant from,
                             Pageable pageable);

    @Modifying
    @Query("DELETE FROM ReceiverLog l WHERE l.occurredAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}

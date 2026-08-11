package com.aquagrid.platform.iot.receiver.infrastructure.persistence;

import com.aquagrid.platform.iot.receiver.domain.model.DeadLetterPacket;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface DeadLetterRepository extends JpaRepository<DeadLetterPacket, UUID> {

    @Query("""
            SELECT d FROM DeadLetterPacket d
            WHERE (cast(:organizationId as uuid) IS NULL OR d.organizationId = :organizationId)
              AND (cast(:status as string) IS NULL OR d.status = :status)
              AND (cast(:transport as string) IS NULL OR d.transport = :transport)
            ORDER BY d.receivedAt DESC
            """)
    Page<DeadLetterPacket> search(@Param("organizationId") UUID organizationId,
                                  @Param("status") String status,
                                  @Param("transport") String transport,
                                  Pageable pageable);

    /**
     * The replay batch, oldest first.
     *
     * <p>Order is not cosmetic. These are readings from a device that also kept sending; replaying
     * them out of order can make a cumulative volume series go backwards, which downstream reads as
     * a meter rollover or a reverse-flow event rather than as an artefact of the recovery.
     */
    List<DeadLetterPacket> findTop200ByStatusOrderByReceivedAtAsc(String status);

    long countByStatus(String status);

    long countByStatusAndOrganizationId(String status, UUID organizationId);

    /** Retention: replayed and discarded letters are evidence for a while, then they are clutter. */
    long deleteByStatusInAndReceivedAtLessThan(List<String> statuses, Instant before);
}

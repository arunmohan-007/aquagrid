package com.aquagrid.platform.iot.receiver.infrastructure.persistence;

import com.aquagrid.platform.iot.receiver.domain.model.PacketLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface PacketLogRepository extends JpaRepository<PacketLog, UUID> {

    /**
     * Packet search, with every filter optional.
     *
     * <p>Every optional filter is cast before its {@code IS NULL} test. The datasource runs with
     * {@code stringtype=unspecified} so an untyped null parameter reaches PostgreSQL with no type
     * and {@code ? IS NULL} fails to plan — which would break the unfiltered search, the most
     * common call there is. Same reasoning as {@code DeviceRepository.findForTenant}.
     *
     * <p>{@code organizationId} is <b>not</b> optional. This table holds rows from every tenant and
     * rows belonging to none; without a mandatory scope, one tenant's operator would page through
     * another's traffic. Platform-operator access to unattributed packets is a separate query, so
     * that the broad one has to be asked for deliberately.
     */
    @Query("""
            SELECT p FROM PacketLog p
            WHERE p.organizationId = :organizationId
              AND (cast(:deviceId as uuid) IS NULL OR p.deviceId = :deviceId)
              AND (cast(:transport as string) IS NULL OR p.transport = :transport)
              AND (cast(:status as string) IS NULL OR p.status = :status)
              AND (cast(:errorCode as string) IS NULL OR p.errorCode = :errorCode)
              AND (cast(:from as timestamp) IS NULL OR p.receivedAt >= :from)
              AND (cast(:to as timestamp) IS NULL OR p.receivedAt < :to)
            ORDER BY p.receivedAt DESC
            """)
    Page<PacketLog> search(@Param("organizationId") UUID organizationId,
                           @Param("deviceId") UUID deviceId,
                           @Param("transport") String transport,
                           @Param("status") String status,
                           @Param("errorCode") String errorCode,
                           @Param("from") Instant from,
                           @Param("to") Instant to,
                           Pageable pageable);

    /**
     * Packets that could not be attributed to any tenant.
     *
     * <p>The commissioning queue in practice: a meter installed in the field but never registered
     * lands here on every uplink, and it is the only place that tells an operator it exists.
     */
    @Query("""
            SELECT p FROM PacketLog p
            WHERE p.organizationId IS NULL
              AND (cast(:transport as string) IS NULL OR p.transport = :transport)
              AND p.receivedAt >= :from
            ORDER BY p.receivedAt DESC
            """)
    Page<PacketLog> findUnattributed(@Param("transport") String transport,
                                     @Param("from") Instant from,
                                     Pageable pageable);

    List<PacketLog> findTop50ByDeviceIdOrderByReceivedAtDesc(UUID deviceId);

    long countByReceivedAtGreaterThanEqual(Instant from);

    long countByStatusAndReceivedAtGreaterThanEqual(
            com.aquagrid.platform.iot.receiver.domain.model.ReceptionStatus status, Instant from);

    /**
     * Retention sweep. Deletes by {@code receivedAt} in bounded batches rather than one statement:
     * a single unbounded delete over a table this size takes a lock long enough to stall ingestion,
     * which is the one thing a housekeeping job must never do.
     */
    @Modifying
    @Query(value = """
            DELETE FROM iot.receiver_packet_logs
            WHERE id IN (SELECT id FROM iot.receiver_packet_logs
                         WHERE received_at < :before ORDER BY received_at LIMIT :batchSize)
            """, nativeQuery = true)
    int deleteOlderThan(@Param("before") Instant before, @Param("batchSize") int batchSize);

    /** Frees payload bytes without losing the row — retention for evidence outlives the evidence. */
    @Modifying
    @Query(value = """
            UPDATE iot.receiver_packet_logs SET raw_payload = NULL
            WHERE raw_payload IS NOT NULL AND received_at < :before
            """, nativeQuery = true)
    int purgePayloadsOlderThan(@Param("before") Instant before);
}

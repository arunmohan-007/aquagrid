package com.aquagrid.platform.iot.receiver.infrastructure.persistence;

import com.aquagrid.platform.iot.receiver.domain.model.PacketStatistics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface PacketStatisticsRepository extends JpaRepository<PacketStatistics, UUID> {

    /**
     * Folds one flush of counters into its hourly bucket.
     *
     * <p>An upsert rather than read-modify-write, because every replica flushes into the same
     * bucket. Loading the row, adding to it and saving it would have two instances each read 100,
     * each write 140, and lose 40 packets from the record — silently, and only under the load where
     * the numbers matter. {@code ON CONFLICT DO UPDATE} makes the addition happen inside the
     * database, where the row is locked for the duration.
     *
     * <p>The counters are deltas, not totals: the caller accumulates in memory between flushes, so
     * the write rate is one statement per device per flush interval rather than one per packet.
     */
    @Modifying
    @Query(value = """
            INSERT INTO iot.receiver_packet_statistics
                (id, organization_id, device_id, transport, bucket_start, accepted, duplicates,
                 rejected, bytes_received, total_processing_ms, last_packet_at, version)
            VALUES (gen_random_uuid(), :organizationId, :deviceId, :transport, :bucketStart,
                    :accepted, :duplicates, :rejected, :bytes, :processingMs, :lastPacketAt, 0)
            ON CONFLICT (organization_id, device_id, transport, bucket_start) DO UPDATE SET
                accepted            = iot.receiver_packet_statistics.accepted + EXCLUDED.accepted,
                duplicates          = iot.receiver_packet_statistics.duplicates + EXCLUDED.duplicates,
                rejected            = iot.receiver_packet_statistics.rejected + EXCLUDED.rejected,
                bytes_received      = iot.receiver_packet_statistics.bytes_received + EXCLUDED.bytes_received,
                total_processing_ms = iot.receiver_packet_statistics.total_processing_ms
                                      + EXCLUDED.total_processing_ms,
                last_packet_at      = GREATEST(iot.receiver_packet_statistics.last_packet_at,
                                               EXCLUDED.last_packet_at)
            """, nativeQuery = true)
    void accumulate(@Param("organizationId") UUID organizationId,
                    @Param("deviceId") UUID deviceId,
                    @Param("transport") String transport,
                    @Param("bucketStart") Instant bucketStart,
                    @Param("accepted") long accepted,
                    @Param("duplicates") long duplicates,
                    @Param("rejected") long rejected,
                    @Param("bytes") long bytes,
                    @Param("processingMs") long processingMs,
                    @Param("lastPacketAt") Instant lastPacketAt);

    List<PacketStatistics> findByOrganizationIdAndDeviceIdAndBucketStartGreaterThanEqualOrderByBucketStartAsc(
            UUID organizationId, UUID deviceId, Instant from);

    /** Every device's buckets since a point — the input to the "who is reporting" view. */
    List<PacketStatistics> findByOrganizationIdAndBucketStartGreaterThanEqual(
            UUID organizationId, Instant from);

    /** Devices that have gone quiet: registered, previously reporting, nothing in the window. */
    @Query("""
            SELECT s.deviceId, MAX(s.lastPacketAt) FROM PacketStatistics s
            WHERE s.organizationId = :organizationId
            GROUP BY s.deviceId
            HAVING MAX(s.lastPacketAt) < :silentSince
            """)
    List<Object[]> findSilentDevices(@Param("organizationId") UUID organizationId,
                                     @Param("silentSince") Instant silentSince);

    @Modifying
    @Query("DELETE FROM PacketStatistics s WHERE s.bucketStart < :before")
    int deleteOlderThan(@Param("before") Instant before);
}

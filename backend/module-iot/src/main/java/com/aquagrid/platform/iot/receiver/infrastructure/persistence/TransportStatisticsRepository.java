package com.aquagrid.platform.iot.receiver.infrastructure.persistence;

import com.aquagrid.platform.iot.receiver.domain.model.TransportStatistics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface TransportStatisticsRepository extends JpaRepository<TransportStatistics, UUID> {

    /**
     * Folds one flush of transport counters into its hourly bucket. Same upsert reasoning as
     * {@link PacketStatisticsRepository#accumulate}.
     *
     * <p>The conflict target repeats the {@code COALESCE} from the unique index, and must: a plain
     * three-column key would not dedupe the unattributed rows, because in SQL two NULLs are not
     * equal and every flush of pre-resolution rejections would insert a new bucket instead of
     * adding to the existing one. Those rejections are the rows a transport dashboard most needs —
     * a listener refusing everything is the fault it is there to show.
     */
    @Modifying
    @Query(value = """
            INSERT INTO iot.receiver_transport_statistics
                (id, organization_id, transport, bucket_start, accepted, duplicates, rejected,
                 bytes_received, total_processing_ms, max_processing_ms, last_packet_at, version)
            VALUES (gen_random_uuid(), :organizationId, :transport, :bucketStart, :accepted,
                    :duplicates, :rejected, :bytes, :processingMs, :maxProcessingMs, :lastPacketAt, 0)
            ON CONFLICT (transport, bucket_start,
                         COALESCE(organization_id, '00000000-0000-0000-0000-000000000000'::uuid))
            DO UPDATE SET
                accepted            = iot.receiver_transport_statistics.accepted + EXCLUDED.accepted,
                duplicates          = iot.receiver_transport_statistics.duplicates + EXCLUDED.duplicates,
                rejected            = iot.receiver_transport_statistics.rejected + EXCLUDED.rejected,
                bytes_received      = iot.receiver_transport_statistics.bytes_received
                                      + EXCLUDED.bytes_received,
                total_processing_ms = iot.receiver_transport_statistics.total_processing_ms
                                      + EXCLUDED.total_processing_ms,
                max_processing_ms   = GREATEST(iot.receiver_transport_statistics.max_processing_ms,
                                               EXCLUDED.max_processing_ms),
                last_packet_at      = GREATEST(iot.receiver_transport_statistics.last_packet_at,
                                               EXCLUDED.last_packet_at)
            """, nativeQuery = true)
    void accumulate(@Param("organizationId") UUID organizationId,
                    @Param("transport") String transport,
                    @Param("bucketStart") Instant bucketStart,
                    @Param("accepted") long accepted,
                    @Param("duplicates") long duplicates,
                    @Param("rejected") long rejected,
                    @Param("bytes") long bytes,
                    @Param("processingMs") long processingMs,
                    @Param("maxProcessingMs") long maxProcessingMs,
                    @Param("lastPacketAt") Instant lastPacketAt);

    List<TransportStatistics> findByBucketStartGreaterThanEqualOrderByBucketStartAsc(Instant from);

    @Query("""
            SELECT s FROM TransportStatistics s
            WHERE s.bucketStart >= :from
              AND (cast(:organizationId as uuid) IS NULL OR s.organizationId = :organizationId)
              AND (cast(:transport as string) IS NULL OR s.transport = :transport)
            ORDER BY s.bucketStart ASC
            """)
    List<TransportStatistics> search(@Param("organizationId") UUID organizationId,
                                     @Param("transport") String transport,
                                     @Param("from") Instant from);

    @Modifying
    @Query("DELETE FROM TransportStatistics s WHERE s.bucketStart < :before")
    int deleteOlderThan(@Param("before") Instant before);
}

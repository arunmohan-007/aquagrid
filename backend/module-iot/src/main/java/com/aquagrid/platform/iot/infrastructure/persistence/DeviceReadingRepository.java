package com.aquagrid.platform.iot.infrastructure.persistence;

import com.aquagrid.platform.iot.domain.model.DeviceReading;
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
public interface DeviceReadingRepository extends JpaRepository<DeviceReading, Long> {

    /**
     * The dominant analytical query: the latest readings for one device and metric in a window.
     * Served by the {@code ix_readings_device_metric_time} index in one seek.
     */
    @Query("""
            SELECT r FROM DeviceReading r
            WHERE r.deviceId = :deviceId
              AND r.metric = :metric
              AND r.observedAt BETWEEN :from AND :to
            ORDER BY r.observedAt DESC
            """)
    List<DeviceReading> findSeries(@Param("deviceId") UUID deviceId,
                                   @Param("metric") String metric,
                                   @Param("from") Instant from,
                                   @Param("to") Instant to);

    /**
     * Every reading a device recorded in a window, all metrics.
     *
     * <p>Backs the receiver's per-device feed, which shows what each device sent and when. The
     * readings are grouped back into packets by {@code observedAt}, and that reconstruction is
     * exact rather than approximate: {@code TelemetryIngestService} writes one row per metric from
     * one message and stamps every one of them with that message's {@code observedAt}, so a shared
     * timestamp <em>is</em> the packet boundary.
     *
     * <p>The one case it cannot separate is two genuinely different packets from one device bearing
     * an identical device timestamp. Replay protection already refuses the common form of that (a
     * retransmission), leaving only a device that reports twice with the same clock reading — rare,
     * and it merges rather than loses.
     */
    @Query("""
            SELECT r FROM DeviceReading r
            WHERE r.deviceId = :deviceId
              AND r.observedAt BETWEEN :from AND :to
            ORDER BY r.observedAt DESC
            """)
    List<DeviceReading> findAllInWindow(@Param("deviceId") UUID deviceId,
                                        @Param("from") Instant from,
                                        @Param("to") Instant to);

    /**
     * The most recent reading of each metric a device has reported.
     *
     * <p>What the device's telemetry summary is built from, and the reason it is one query rather
     * than one per metric: the set of metrics a device reports is not known in advance — it is
     * whatever its payload carried, including vendor-specific keys the platform has no catalogue
     * entry for — so the alternative would be a query per metric after a query to discover them.
     *
     * <p>Native, because {@code DISTINCT ON} is PostgreSQL's and JPQL has no equivalent that does
     * not degenerate into a correlated subquery per row. Ordered by {@code (metric, observed_at
     * DESC)} so it walks {@code ix_readings_device_metric_time} — the same index the series query
     * uses — and takes the first row of each metric group without sorting the window.
     *
     * <p>{@code since} bounds it rather than scanning the device's whole history: a metric the
     * device stopped reporting a year ago is not part of its current state, and showing a stale
     * value beside live ones with no indication of age is worse than omitting it.
     */
    @Query(value = """
            SELECT DISTINCT ON (r.metric) r.*
            FROM iot.device_readings r
            WHERE r.device_id = :deviceId
              AND r.observed_at >= :since
            ORDER BY r.metric, r.observed_at DESC
            """, nativeQuery = true)
    List<DeviceReading> findLatestPerMetric(@Param("deviceId") UUID deviceId,
                                            @Param("since") Instant since);

    /**
     * The highest frame counter a device has been recorded at.
     *
     * <p>Exists so the fleet simulator can resume a meter's counter across a restart. A physical
     * meter keeps its frame counter in non-volatile memory because the network rejects replays; a
     * simulated one that restarted from zero would re-send counters the receiver has already
     * claimed, and every packet would be refused as a duplicate for the length of the replay window
     * — the fleet silently mute for a day, looking exactly like a simulator that had stopped.
     */
    @Query("""
            SELECT MAX(r.fCnt) FROM DeviceReading r
            WHERE r.deviceId = :deviceId AND r.observedAt >= :since
            """)
    Integer findLastFrameCounter(@Param("deviceId") UUID deviceId, @Param("since") Instant since);

    /**
     * Readings for a set of devices over a window, in report order.
     *
     * <p>Takes a resolved device set rather than the report's own filters, and that is deliberate.
     * The filters an operator picks — device type, transport — are properties of the <em>device</em>,
     * not the reading, so expressing them here would mean either a join on every page of a
     * multi-hundred-thousand-row scan or a nullable-cast predicate per filter. Resolving the device
     * set once against the small table and passing ids is one indexed scan per page, and it yields
     * the device metadata each exported row has to carry anyway.
     *
     * <p>Ordered by time first, because that is what a timestamped report is read as. The device and
     * metric tie-breakers only make the order total, so paging cannot interleave or repeat a row
     * between chunks — without them, rows sharing an instant could be returned in a different order
     * on each page and an export would silently duplicate some and drop others.
     */
    @Query("""
            SELECT r FROM DeviceReading r
            WHERE r.deviceId IN :deviceIds
              AND r.observedAt BETWEEN :from AND :to
              AND (cast(:metric as string) IS NULL OR r.metric = :metric)
            ORDER BY r.observedAt ASC, r.deviceId ASC, r.metric ASC
            """)
    Page<DeviceReading> findForExport(@Param("deviceIds") java.util.Collection<UUID> deviceIds,
                                      @Param("from") Instant from,
                                      @Param("to") Instant to,
                                      @Param("metric") String metric,
                                      Pageable pageable);

    /** Distinct metrics a device has reported in a window — the axis a series chart offers. */
    @Query("""
            SELECT DISTINCT r.metric FROM DeviceReading r
            WHERE r.deviceId = :deviceId AND r.observedAt >= :since
            ORDER BY r.metric
            """)
    List<String> findMetricsReported(@Param("deviceId") UUID deviceId,
                                     @Param("since") Instant since);
}

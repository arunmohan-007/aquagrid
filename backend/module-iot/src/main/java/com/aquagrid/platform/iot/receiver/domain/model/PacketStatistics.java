package com.aquagrid.platform.iot.receiver.domain.model;

import com.aquagrid.platform.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-device packet counters, rolled up by the hour.
 *
 * <p>The grain is the point. {@link TransportStatistics} answers "is the LoRaWAN path healthy",
 * which is an infrastructure question; this answers "is <em>this meter</em> reporting as often as
 * it should", which is an asset question, and the two are asked by different people from different
 * screens. Neither can be derived from the other: a transport at a 99% success rate hides a single
 * meter failing every packet, and a healthy meter says nothing about the path its neighbours use.
 *
 * <p>Both are aggregates rather than queries over {@link PacketLog} because the underlying question
 * — "how many packets did each device send in each of the last 720 hours" — is a scan of the
 * largest table in the module, run on every dashboard refresh. Pre-aggregating on write turns that
 * into an indexed read, and the counters are cheap because they are accumulated in memory and
 * flushed in batches, not incremented per packet.
 *
 * <p>Micrometer covers the live view; this covers history. Metric backends keep minutes to days at
 * full resolution, and "was this meter reporting reliably last quarter" is a question asked of a
 * database, in a report, long after the scrape has aged out.
 */
@Getter
@Setter
@Entity
@Table(name = "receiver_packet_statistics", schema = "iot")
public class PacketStatistics extends BaseEntity {

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    /** Start of the hour this row aggregates, UTC. */
    @Column(name = "bucket_start", nullable = false)
    private Instant bucketStart;

    @Column(name = "transport", nullable = false, length = 20)
    private String transport;

    @Column(name = "accepted", nullable = false)
    private long accepted;

    @Column(name = "duplicates", nullable = false)
    private long duplicates;

    @Column(name = "rejected", nullable = false)
    private long rejected;

    @Column(name = "bytes_received", nullable = false)
    private long bytesReceived;

    /** Summed, not averaged, so buckets can be merged without weighting. */
    @Column(name = "total_processing_ms", nullable = false)
    private long totalProcessingMs;

    @Column(name = "last_packet_at")
    private Instant lastPacketAt;
}

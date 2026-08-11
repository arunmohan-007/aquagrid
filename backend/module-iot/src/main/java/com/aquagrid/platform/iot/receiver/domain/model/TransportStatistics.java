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
 * Per-transport packet counters, rolled up by the hour. See {@link PacketStatistics} for why the
 * two grains are separate tables fed by one writer.
 *
 * <p>{@code organizationId} is nullable here and not there, and the difference is not an oversight.
 * A packet is counted against a transport the moment it arrives — including the ones refused before
 * any device, and therefore any tenant, was resolved. Those are precisely the packets a transport
 * dashboard must show: a listener taking 10,000 packets an hour and rejecting every one is a
 * misconfigured gateway, and it would be invisible in a view that could only count attributed
 * traffic.
 */
@Getter
@Setter
@Entity
@Table(name = "receiver_transport_statistics", schema = "iot")
public class TransportStatistics extends BaseEntity {

    /** Null for packets refused before the device — and therefore the tenant — could be resolved. */
    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "transport", nullable = false, length = 20)
    private String transport;

    @Column(name = "bucket_start", nullable = false)
    private Instant bucketStart;

    @Column(name = "accepted", nullable = false)
    private long accepted;

    @Column(name = "duplicates", nullable = false)
    private long duplicates;

    @Column(name = "rejected", nullable = false)
    private long rejected;

    @Column(name = "bytes_received", nullable = false)
    private long bytesReceived;

    @Column(name = "total_processing_ms", nullable = false)
    private long totalProcessingMs;

    /** Kept alongside the sum because a mean hides the stall that woke someone up. */
    @Column(name = "max_processing_ms", nullable = false)
    private long maxProcessingMs;

    @Column(name = "last_packet_at")
    private Instant lastPacketAt;
}

package com.aquagrid.platform.iot.receiver.domain.model;

import com.aquagrid.platform.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A packet the receiver could not process but had no right to discard.
 *
 * <p>Not every rejection belongs here, and the distinction is the whole design. A packet from an
 * unregistered device is refused and logged; replaying it later would still find no device, so
 * queueing it would only accumulate garbage. What lands here is a packet that <em>would</em> have
 * been accepted had something else been true: the database was unreachable, the device is
 * registered but was not yet committed, a parser threw on a payload shape it should handle.
 *
 * <p>Those are recoverable, and recovering them matters because the alternative is silent data
 * loss during exactly the incidents when data matters most. A gateway that got no acknowledgement
 * retries for a while and then gives up; the readings it was holding are gone. Storing the packet
 * means a replay after the outage reconstructs the interval instead of leaving a hole in a
 * consumption series that will later be billed.
 *
 * <p>Replay is a privileged action ({@code iot:receiver:replay}) and is recorded: re-injecting a
 * packet writes telemetry the device never re-sent, which is indistinguishable from fabricating it
 * unless who did it and when is on the row.
 */
@Getter
@Setter
@Entity
@Table(name = "receiver_dead_letters", schema = "iot")
public class DeadLetterPacket extends BaseEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_REPLAYED = "REPLAYED";
    public static final String STATUS_DISCARDED = "DISCARDED";

    /** The packet log row this came from, so the two views reconcile. */
    @Column(name = "packet_id", nullable = false)
    private UUID packetId;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "device_id")
    private UUID deviceId;

    @Column(name = "transport", nullable = false, length = 20)
    private String transport;

    @Column(name = "error_code", nullable = false, length = 60)
    private String errorCode;

    @Column(name = "error_detail", length = 500)
    private String errorDetail;

    /**
     * The original bytes. Mandatory here, unlike on the packet log: a dead letter with no payload
     * cannot be replayed, which is the only reason the row exists.
     */
    @Column(name = "payload", nullable = false)
    private byte[] payload;

    @Column(name = "content_type", length = 80)
    private String contentType;

    @Column(name = "source_ip", columnDefinition = "inet")
    private String sourceIp;

    /**
     * The transport attributes and identifier claims the packet arrived with. Without these a
     * replay is not the same packet — an MQTT publish without its topic cannot be resolved.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "envelope", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> envelope = new java.util.HashMap<>();

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts = 1;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "replayed_at")
    private Instant replayedAt;

    @Column(name = "replayed_by")
    private UUID replayedBy;

    @Column(name = "replay_result", length = 300)
    private String replayResult;

    public void recordReplay(UUID actorUserId, Instant at, String result, boolean succeeded) {
        this.attempts++;
        this.replayedAt = at;
        this.replayedBy = actorUserId;
        this.replayResult = result;
        // Only a successful replay retires the row. A failed one stays PENDING so the next attempt
        // — after the underlying fault is fixed — still finds it.
        if (succeeded) {
            this.status = STATUS_REPLAYED;
        }
    }
}

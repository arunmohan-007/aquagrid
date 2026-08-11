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
 * Append-only record of connections opening and closing.
 *
 * <p>{@link CommunicationSession} holds current state and is overwritten; this holds the sequence
 * and is not. The distinction earns its keep in exactly one situation, which is also the most
 * common one: a link that flaps. A session row shows a healthy open connection because the last
 * reconnect succeeded; the history shows forty reconnects in an hour, which is the actual fault and
 * is invisible from state alone.
 *
 * <p>Rejected connection attempts are recorded here too — a device whose credentials have expired
 * never gets a session, so a refusal that left no trace would present to an operator as complete
 * silence from the device.
 */
@Getter
@Setter
@Entity
@Table(name = "receiver_connection_history", schema = "iot")
public class ConnectionHistory extends BaseEntity {

    public static final String CONNECTED = "CONNECTED";
    public static final String DISCONNECTED = "DISCONNECTED";
    public static final String REJECTED = "REJECTED";
    public static final String TIMED_OUT = "TIMED_OUT";
    public static final String ERROR = "ERROR";

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "device_id")
    private UUID deviceId;

    @Column(name = "transport", nullable = false, length = 20)
    private String transport;

    @Column(name = "event", nullable = false, length = 16)
    private String event;

    @Column(name = "remote_address", columnDefinition = "inet")
    private String remoteAddress;

    @Column(name = "reason", length = 300)
    private String reason;

    /** How long the connection had been open when it closed. Null on connect and reject events. */
    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();
}

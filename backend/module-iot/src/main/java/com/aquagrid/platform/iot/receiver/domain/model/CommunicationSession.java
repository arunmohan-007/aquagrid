package com.aquagrid.platform.iot.receiver.domain.model;

import com.aquagrid.platform.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A connection a stateful transport is holding open, or last held open.
 *
 * <p>Only TCP, MQTT and WebSocket produce these. For HTTP, UDP and webhook-delivered LoRaWAN there
 * is no connection to speak of and a session row would be a packet-log row wearing a different
 * name — {@code TransportReceiver.stateful()} is what decides, so no stage has to know which
 * transports are which.
 *
 * <p>Sessions are what make "which devices are connected right now" answerable, and that question
 * has an operational edge to it: a logger whose socket is open but which has sent nothing for an
 * hour is a different fault from one that dropped its socket, and only a session row distinguishes
 * them. {@code lastActivityAt} is therefore updated on every packet even though it costs a write —
 * the alternative is inferring liveness from the packet log, which is a scan of the largest table
 * in the module to answer a question asked on every dashboard refresh.
 *
 * <p>One row per connection, closed in place rather than deleted: the history of a flapping link is
 * in {@link ConnectionHistory}, but the current state belongs here where it can be read by key.
 */
@Getter
@Setter
@Entity
@Table(name = "receiver_sessions", schema = "iot")
public class CommunicationSession extends BaseEntity {

    public static final String STATE_OPEN = "OPEN";
    public static final String STATE_CLOSED = "CLOSED";

    /** Null until a packet on this connection resolves to a device. */
    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "device_id")
    private UUID deviceId;

    @Column(name = "transport", nullable = false, length = 20)
    private String transport;

    /**
     * The transport's own name for the connection — MQTT client id, socket id, WebSocket session
     * id. Unique per transport among open sessions, which is how a reconnect finds and closes the
     * stale row rather than accumulating a second one.
     */
    @Column(name = "session_key", nullable = false, length = 120)
    private String sessionKey;

    @Column(name = "remote_address", columnDefinition = "inet")
    private String remoteAddress;

    @Column(name = "remote_port")
    private Integer remotePort;

    @Column(name = "state", nullable = false, length = 16)
    private String state = STATE_OPEN;

    @Column(name = "protocol_version", length = 20)
    private String protocolVersion;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt = Instant.now();

    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt = Instant.now();

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "close_reason", length = 300)
    private String closeReason;

    @Column(name = "packets_received", nullable = false)
    private long packetsReceived;

    @Column(name = "bytes_received", nullable = false)
    private long bytesReceived;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> attributes = new java.util.HashMap<>();

    public void registerPacket(int bytes, Instant at, UUID resolvedDeviceId, UUID resolvedTenantId) {
        this.packetsReceived++;
        this.bytesReceived += bytes;
        this.lastActivityAt = at;
        // A shared gateway connection carries several devices; the session records the first one it
        // could place rather than flapping between them, and the packet log keeps the per-packet truth.
        if (this.deviceId == null && resolvedDeviceId != null) {
            this.deviceId = resolvedDeviceId;
            this.organizationId = resolvedTenantId;
        }
    }

    public void close(Instant at, String reason) {
        this.state = STATE_CLOSED;
        this.closedAt = at;
        this.closeReason = reason;
    }

    public boolean isOpen() {
        return STATE_OPEN.equals(state);
    }

    public Duration duration() {
        return Duration.between(openedAt, closedAt == null ? Instant.now() : closedAt);
    }
}

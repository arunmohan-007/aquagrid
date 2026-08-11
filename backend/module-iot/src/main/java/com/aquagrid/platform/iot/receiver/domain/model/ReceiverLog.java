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
 * The receiver's own operational diary — distinct from {@link PacketLog}, and deliberately so.
 *
 * <p>A packet log answers "what happened to this reading". This answers "what is the receiver
 * doing": a listener bound or failed to bind, a broker connection dropped, a dead letter was
 * replayed by a named operator, an authentication failure rate crossed its threshold. Those are
 * low-volume, high-value events with a completely different retention profile from per-packet rows,
 * and folding them into one table would either drown them in traffic or hold traffic for as long as
 * incidents need to be retained.
 *
 * <p>The split also matters for who reads them. Packet logs are read per device by support; these
 * are read per deployment by whoever is on call, and are the rows that belong on a status page.
 */
@Getter
@Setter
@Entity
@Table(name = "receiver_logs", schema = "iot")
public class ReceiverLog extends BaseEntity {

    /** Event type names. Additive, never repurposed — dashboards and alert rules match on them. */
    public static final class Events {
        public static final String TRANSPORT_STARTED = "TRANSPORT_STARTED";
        public static final String TRANSPORT_STOPPED = "TRANSPORT_STOPPED";
        public static final String TRANSPORT_FAILED = "TRANSPORT_FAILED";
        public static final String CONNECTION_REJECTED = "CONNECTION_REJECTED";
        public static final String DEAD_LETTER_STORED = "DEAD_LETTER_STORED";
        public static final String DEAD_LETTER_REPLAYED = "DEAD_LETTER_REPLAYED";
        public static final String PACKET_REPLAYED = "PACKET_REPLAYED";
        public static final String AUTHENTICATION_FAILURE_BURST = "AUTHENTICATION_FAILURE_BURST";
        public static final String RATE_LIMIT_TRIPPED = "RATE_LIMIT_TRIPPED";

        private Events() {
        }
    }

    /** Null for deployment-wide events — a listener starting belongs to no tenant. */
    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "transport", length = 20)
    private String transport;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "severity", nullable = false, length = 10)
    private String severity = "INFO";

    @Column(name = "message", length = 1000)
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> details = new java.util.HashMap<>();

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();
}

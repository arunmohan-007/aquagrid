package com.aquagrid.platform.iot.receiver.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One row per packet the receiver took delivery of, whatever became of it.
 *
 * <p>The forensic record. When a meter is disputed, when a municipality asks why a reading is
 * missing, or when an auditor asks what the platform was told and when, this table is the answer —
 * and it has to include the packets that were <em>refused</em>, because "nothing arrived" and
 * "something arrived and we would not take it" are the two hypotheses that need distinguishing and
 * only one of them leaves a reading behind.
 *
 * <p>Deliberately not an {@code AuditableEntity}: there is no author to record and no update to
 * track. Rows are written once by the machine and never modified, so the four who/when audit
 * columns would be four wasted columns on the highest-volume table in the module.
 *
 * <p>{@link Persistable} with {@code isNew() == true} for the same reason. The id is assigned by the
 * receiver — it is the packet id, quoted back to the caller — and Spring Data's default rule
 * ("non-null id means existing") would issue a SELECT before every INSERT, doubling the query count
 * of the hottest write path in the platform to check for a row that by construction cannot exist.
 */
@Getter
@Setter
@Entity
@Table(name = "receiver_packet_logs", schema = "iot")
public class PacketLog implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Null until the device is resolved, and stays null when it never is.
     *
     * <p>An unattributable packet is exactly the one an operator most wants to see — a meter
     * commissioned in the field but never registered produces a stream of them — so the log cannot
     * require a tenant. The consequence is that this table is not tenant-filtered at the database
     * level and its API must scope every query explicitly.
     */
    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "device_id")
    private UUID deviceId;

    @Column(name = "transport", nullable = false, length = 20)
    private String transport;

    @Column(name = "communication_profile", length = 20)
    private String communicationProfile;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    /** The device's own clock, once a parser has read it. Null for undecodable packets. */
    @Column(name = "observed_at")
    private Instant observedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private ReceptionStatus status;

    /** {@link com.aquagrid.platform.common.error.ErrorCode} name; null when accepted. */
    @Column(name = "error_code", length = 60)
    private String errorCode;

    @Column(name = "error_detail", length = 500)
    private String errorDetail;

    @Column(name = "payload_size", nullable = false)
    private int payloadSize;

    @Column(name = "source_ip", columnDefinition = "inet")
    private String sourceIp;

    /** Ties this row to every log line the reception produced. */
    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "processing_time_ms", nullable = false)
    private int processingTimeMs;

    @Column(name = "authentication_scheme", length = 40)
    private String authenticationScheme;

    @Column(name = "principal", length = 120)
    private String principal;

    /** Which {@code DeviceResolutionStrategy} placed the device — or null if none could. */
    @Column(name = "resolution_strategy", length = 60)
    private String resolutionStrategy;

    @Column(name = "parser", length = 60)
    private String parser;

    @Column(name = "content_type", length = 80)
    private String contentType;

    /**
     * The bytes as received.
     *
     * <p>Retention is a policy decision, not a technical one, and it is configurable per outcome:
     * keeping every accepted payload doubles the storage cost of telemetry for information already
     * present in the readings, while discarding the rejected ones destroys the only copy of the
     * evidence needed to work out why they were rejected. The default keeps rejected payloads and
     * drops accepted ones.
     */
    @Column(name = "raw_payload")
    private byte[] rawPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata = new java.util.HashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Override
    public boolean isNew() {
        return true;
    }
}

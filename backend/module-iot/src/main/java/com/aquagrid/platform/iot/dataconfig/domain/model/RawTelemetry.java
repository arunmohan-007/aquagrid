package com.aquagrid.platform.iot.dataconfig.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The complete original payload of one packet.
 *
 * <p>This is the table that makes "accept and permanently preserve everything" true rather than
 * aspirational, and it exists because neither of the two tables that look like it can do the job:
 *
 * <ul>
 *   <li>{@code iot.device_readings} is one row per <em>metric</em>, and a metric is a number. A
 *       string status, an array of sub-meter readings or a nested object produces no row at all, and
 *       the names it does store have already been canonicalised — the vendor's own spelling is gone
 *       by the time a row is written.</li>
 *   <li>{@code iot.receiver_packet_logs} holds the original bytes, and deliberately holds them only
 *       sometimes: rejected payloads by default, accepted ones only when asked for, because "an
 *       accepted payload's information is already in the readings table". That is exactly right for
 *       a forensic log and exactly backwards here — the information is in the readings table only
 *       for the fields somebody configured. It is also {@code BYTEA}, so no query can ask which
 *       payloads carry a {@code powerFactor}.</li>
 * </ul>
 *
 * <p>So: JSONB, always written, and <b>never modified</b>. No normalisation, no canonicalising of
 * keys, no dropping of fields the platform had no use for. This row is the answer to "what did the
 * device actually send", and a row that had been tidied could not answer it.
 *
 * <p>Not a {@code BaseEntity}, and the id is assigned rather than generated: it is the receiver's
 * packet id, so this row, the packet log row and the correlation id in the application log all name
 * the same reception. Nothing ever updates a row here, so there is no version column.
 */
@Getter
@Setter
@Entity
@Table(name = "device_raw_telemetry", schema = "iot")
public class RawTelemetry {

    /** The receiver's packet id. Assigned by the caller — see the class comment. */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Null when the packet could not be attributed to a device, and so to a tenant. */
    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "device_id")
    private UUID deviceId;

    @Column(name = "device_code", length = 60)
    private String deviceCode;

    /**
     * The asset the device is fitted to, denormalised.
     *
     * <p>module-iot does not join {@code gis.assets} — it is the planned first microservice
     * extraction — and this row is written from inside packet reception, where a join is a cost paid
     * per packet.
     */
    @Column(name = "asset_id")
    private UUID assetId;

    @Column(name = "asset_number", length = 80)
    private String assetNumber;

    /** The device's own clock. Null where the payload carried no timestamp or would not decode. */
    @Column(name = "device_timestamp")
    private Instant deviceTimestamp;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    /**
     * The device's registered network — {@code LORAWAN}, {@code NB_IOT}, …
     *
     * <p>Distinct from {@link #connectionMode}, and routinely a different value: a ChirpStack uplink
     * is a LoRaWAN device arriving over an HTTP connection. Collapsing the two would label it one or
     * the other and lose the other.
     */
    @Column(name = "communication_type", length = 20)
    private String communicationType;

    /** The bearer the packet actually arrived on — {@code HTTP}, {@code MQTT}, {@code TCP}, … */
    @Column(name = "connection_mode", nullable = false, length = 20)
    private String connectionMode;

    @Column(name = "message_id", nullable = false, updatable = false)
    private UUID messageId;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "source_ip", columnDefinition = "inet")
    private String sourceIp;

    /**
     * The payload exactly as received.
     *
     * <p>Payloads that are not JSON — a LoRaWAN frame, a raw meter binary — are still one JSONB
     * document: {@code {"encoding":"BASE64","data":"…"}}, with the bytes intact and recoverable.
     * {@link #payloadEncoding} says which case a row is, so nothing has to guess by inspection.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "payload_encoding", nullable = false, length = 10)
    private String payloadEncoding = "JSON";

    @Column(name = "payload_size", nullable = false)
    private int payloadSize;

    /** {@code ACCEPTED}, {@code DUPLICATE} or {@code REJECTED} — mirrors {@code ReceptionStatus}. */
    @Column(name = "processing_status", nullable = false, length = 12)
    private String processingStatus;

    /**
     * Copied from the rejection rather than joined to the packet log.
     *
     * <p>The commonest use of this table is reading a refused payload beside the reason it was
     * refused, and making that a join across the largest table in the module would make the common
     * case the expensive one.
     */
    @Column(name = "processing_error", length = 500)
    private String processingError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Encoding markers, matching the CHECK constraint in V1406. */
    public static final class Encodings {
        public static final String JSON = "JSON";
        public static final String BASE64 = "BASE64";
        public static final String TEXT = "TEXT";

        private Encodings() {
        }
    }
}

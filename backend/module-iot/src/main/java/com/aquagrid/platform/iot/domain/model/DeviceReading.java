package com.aquagrid.platform.iot.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One telemetry reading.
 *
 * <p>{@code BIGSERIAL} rather than UUID: this table is write-heavy, insert-ordered and never
 * addressed by an external client, so index locality matters more than opaque ids. Module 13
 * converts this into a TimescaleDB hypertable; the column shape is hypertable-ready.
 */
@Getter
@Setter
@Entity
@Table(name = "device_readings", schema = "iot")
public class DeviceReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "metric", nullable = false, length = 60)
    private String metric;

    @Column(name = "value")
    private Double value;

    @Column(name = "unit", length = 20)
    private String unit;

    /**
     * What the configured parameter's validation made of this value — a
     * {@code QualityStatus} name.
     *
     * <p>A string rather than the enum, because {@code QualityStatus} belongs to the data
     * configuration module and this entity is read by ingestion, exports, telemetry and reports.
     * Binding the platform's oldest table to a newer module's enum would make it the one thing that
     * has to move first if the IoT tier is ever split.
     *
     * <p>Null on rows written before V1406, which is deliberate: an old row saying nothing is honest,
     * and back-filling one with a verdict nothing evaluated would not be. {@code UNKNOWN} means the
     * parameter is not configured — not a defect, just the state before an administrator has said
     * anything about it.
     */
    @Column(name = "quality", length = 16)
    private String quality;

    /**
     * The parameter definition this reading was judged against.
     *
     * <p>Kept so that widening a range later does not make the historical verdict unreadable: with
     * this id and {@code iot.device_parameter_history}, "this was OUT_OF_RANGE under the definition
     * in force at the time" is still reconstructible.
     */
    @Column(name = "parameter_id")
    private UUID parameterId;

    @Column(name = "rssi", precision = 6, scale = 2)
    private BigDecimal rssi;

    @Column(name = "snr", precision = 5, scale = 2)
    private BigDecimal snr;

    @Column(name = "battery_v", precision = 5, scale = 2)
    private BigDecimal batteryV;

    @Column(name = "f_cnt")
    private Integer fCnt;

    @Column(name = "transport", length = 20)
    private String transport;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "jsonb")
    private Map<String, Object> rawPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata = new java.util.HashMap<>();
}

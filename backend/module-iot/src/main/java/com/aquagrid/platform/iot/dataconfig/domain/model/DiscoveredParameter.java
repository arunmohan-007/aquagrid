package com.aquagrid.platform.iot.dataconfig.domain.model;

import com.aquagrid.platform.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A parameter a device has sent that the catalogue does not describe.
 *
 * <p>Storing an unknown field means nothing is lost. It does not mean anyone finds out. An
 * unconfigured parameter is invisible on every dashboard, absent from every report and outside every
 * alarm rule — indistinguishable, from the operator's chair, from a field the device never sent.
 * This entity is the difference between preserving data and surfacing it.
 *
 * <p>One row per (device, parameter), upserted on every sighting rather than appended. A pump
 * reporting {@code powerFactor} every five minutes would otherwise produce a hundred thousand
 * identical rows a year to convey one fact; the counter and the two timestamps carry the volume
 * instead, and "show me the actual data" goes to {@link RawTelemetry}, which has it.
 *
 * <p>{@code parameterName} is the payload key <em>verbatim</em> — not canonicalised, not
 * lower-cased. The operator has to recognise it in the vendor's documentation, and {@code motorTemp}
 * and {@code motor_temp} are different strings in a payload even where they would end up being the
 * same parameter.
 */
@Getter
@Setter
@Entity
@Table(name = "device_discovered_parameter", schema = "iot")
public class DiscoveredParameter extends BaseEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "device_id", nullable = false, updatable = false)
    private UUID deviceId;

    /**
     * Denormalised from the device row so the discovery list renders without a join, and so the row
     * still reads sensibly after the device has been renamed or re-typed.
     */
    @Column(name = "device_code", length = 60)
    private String deviceCode;

    @Column(name = "device_type", length = 40)
    private String deviceType;

    @Column(name = "parameter_name", nullable = false, length = 120, updatable = false)
    private String parameterName;

    /**
     * What the value looked like, as text.
     *
     * <p>Text rather than typed, because the whole premise of the row is that the platform does not
     * yet know the type — {@link #detectedDataType} is a guess derived from this.
     */
    @Column(name = "sample_value", length = 255)
    private String sampleValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "detected_data_type", length = 20)
    private ParameterDataType detectedDataType;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt = Instant.now();

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt = Instant.now();

    @Column(name = "occurrences", nullable = false)
    private long occurrences = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private DiscoveryStatus status = DiscoveryStatus.PENDING;

    /**
     * The definition this discovery became, once someone configured it.
     *
     * <p>Kept rather than the row being deleted, so the list can show the outcome instead of quietly
     * losing the fact that the field was ever unknown — which is the fact a commissioning review
     * wants to read.
     */
    @Column(name = "parameter_id")
    private UUID parameterId;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}

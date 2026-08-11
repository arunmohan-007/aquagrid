package com.aquagrid.platform.iot.dataconfig.domain.model;

import com.aquagrid.platform.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * One parameter a device is expected to send, and what the platform should do with it.
 *
 * <p>The catalogue row. Everything downstream of ingestion that has an opinion about a reading —
 * its unit, whether it belongs on a dashboard, whether it can raise an alarm, what range is
 * plausible — reads that opinion from here rather than from Java, which is what makes adding a
 * parameter an INSERT instead of a release.
 *
 * <p><b>Never consulted to reject a packet.</b> This is the invariant the whole module rests on, and
 * it is worth stating on the entity because the class reads like validation configuration and half
 * of it is. A reading that fails every rule declared here is still stored; what changes is the
 * {@link QualityStatus} written beside it. Refusing a packet because it carried a field nobody
 * catalogued would discard a measurement that cannot be re-requested, in order to enforce a table an
 * administrator has simply not filled in yet.
 *
 * @see ParameterScope for how a device-type template and a device's own override combine
 */
@Getter
@Setter
@Entity
@Table(name = "device_data_parameter", schema = "iot")
public class DeviceDataParameter extends AuditableEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 12)
    private ParameterScope scope;

    /**
     * The device type this template applies to, when {@code scope} is {@code DEVICE_TYPE}.
     *
     * <p>A value from the same vocabulary {@code iot.devices.device_type} is CHECK-constrained to
     * (V1401), held as a string rather than a foreign key because there is no device-type table:
     * device types are values in this platform, not rows.
     */
    @Column(name = "device_type", length = 40)
    private String deviceType;

    /** The device this override applies to, when {@code scope} is {@code DEVICE}. */
    @Column(name = "device_id")
    private UUID deviceId;

    /** Canonical name, as it lands in {@code iot.device_readings.metric}. */
    @Column(name = "parameter_name", nullable = false, length = 60)
    private String parameterName;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "description", length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_type", nullable = false, length = 20)
    private ParameterDataType dataType;

    /** A code from {@code iot.unit_master}. Null for a parameter that carries no unit. */
    @Column(name = "unit", length = 20)
    private String unit;

    /**
     * Which group an operator reads this in.
     *
     * <p>A {@code MetricCatalog.Category} name. Reused rather than re-invented so that the telemetry
     * screen, which already groups readings by these, can render a configured parameter without
     * knowing this module exists.
     */
    @Column(name = "category", nullable = false, length = 20)
    private String category = "OTHER";

    /**
     * The vendor's spelling in the payload, where it differs from {@code parameterName}.
     *
     * <p>Null means they are the same — the common case, and deliberately not stored as a copy: a
     * copy would need keeping in step by hand on every rename, and the first time it was not, the
     * parameter would silently stop matching anything.
     */
    @Column(name = "payload_key", length = 120)
    private String payloadKey;

    /**
     * A packet without this parameter is incomplete — not invalid.
     *
     * <p>The absence is recorded as a reading with a null value and {@link QualityStatus#MISSING}.
     * The packet is accepted regardless: refusing it would discard the parameters that did arrive in
     * order to complain about the one that did not.
     */
    @Column(name = "is_mandatory", nullable = false)
    private boolean mandatory;

    @Column(name = "dashboard_visible", nullable = false)
    private boolean dashboardVisible = true;

    @Column(name = "use_for_alarm", nullable = false)
    private boolean useForAlarm;

    @Column(name = "use_for_reports", nullable = false)
    private boolean useForReports = true;

    @Column(name = "min_value")
    private Double minValue;

    @Column(name = "max_value")
    private Double maxValue;

    /** Digits after the decimal point. Applied by rounding, never by rejection. */
    @Column(name = "decimal_precision")
    private Integer decimalPrecision;

    @Column(name = "sample_value", length = 255)
    private String sampleValue;

    @Column(name = "default_value", length = 255)
    private String defaultValue;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 100;

    /**
     * The key this parameter is matched by in an incoming payload.
     *
     * <p>Two names for one parameter is a small thing that goes wrong in a specific way, so it is
     * resolved in exactly one place rather than at each of the three call sites that need it.
     */
    public String matchKey() {
        return payloadKey == null || payloadKey.isBlank() ? parameterName : payloadKey;
    }
}

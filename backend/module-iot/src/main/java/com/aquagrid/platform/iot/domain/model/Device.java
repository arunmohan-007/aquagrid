package com.aquagrid.platform.iot.domain.model;

import com.aquagrid.platform.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * A physical telemetry device.
 *
 * <p>Communication-independent by design. Identity ({@code deviceCode}), hardware
 * ({@code manufacturer}, {@code model}, {@code serialNumber}), siting ({@code assetNumber},
 * {@code installationDate}, {@code location}) and lifecycle ({@code status}) are the same fields
 * whatever radio the device carries. {@code transport} names the technology; everything specific
 * to it is declared by {@link CommunicationProfile} and stored in {@code provisioning}.
 *
 * <p>{@code networkAddress} is the one exception, and deliberately so: it holds whichever
 * identifier the chosen technology addresses the device by (DevEUI for LoRaWAN, IMEI for NB-IoT
 * and 4G), derived from the communication block rather than entered on its own. Promoting it to a
 * column keeps uplink resolution a single indexed lookup — see
 * {@link com.aquagrid.platform.iot.application.service.TelemetryIngestService}.
 *
 * <p>Radio-quality columns ({@code batteryV}, {@code rssi}, {@code snr}, {@code lastSeenAt}) are
 * refreshed on every uplink so the device-health dashboard reflects reality without a telemetry query.
 */
@Getter
@Setter
@Entity
@Table(name = "devices", schema = "iot")
public class Device extends AuditableEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    /** Operator-facing identity, unique per tenant. What is printed on the work order. */
    @Column(name = "device_code", nullable = false, length = 60)
    private String deviceCode;

    /**
     * Network-layer address, derived from the communication block. Null while a device is
     * registered but not yet allocated a SIM or radio module — such a device simply cannot be
     * resolved from an uplink until it is.
     */
    @Column(name = "network_address", length = 32)
    private String networkAddress;

    @Column(name = "device_type", nullable = false, length = 40)
    private String deviceType;

    @Column(name = "manufacturer", length = 120)
    private String manufacturer;

    @Column(name = "model", length = 120)
    private String model;

    @Column(name = "serial_number", length = 64)
    private String serialNumber;

    @Column(name = "installation_date")
    private LocalDate installationDate;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "asset_id")
    private UUID assetId;

    /**
     * Asset code of the linked asset, denormalised.
     *
     * <p>module-iot does not join {@code gis.assets}: it is the planned first microservice
     * extraction, and a foreign join would anchor it to the monolith. The cost is that renaming an
     * asset code leaves this stale until the device is next saved.
     */
    @Column(name = "asset_number", length = 80)
    private String assetNumber;

    /** Where the device itself is (EPSG:4326) — often not where its asset is. */
    @Column(name = "location", columnDefinition = "geometry")
    private Point location;

    @Column(name = "transport", nullable = false, length = 20)
    private String transport;

    /**
     * How telemetry reaches the platform — see {@link DeviceProtocol}.
     *
     * <p>Orthogonal to {@code transport}: a LoRaWAN meter whose network server posts uplinks to our
     * webhook has transport LORAWAN and protocol HTTP; a device that publishes onto a broker has
     * protocol MQTT whichever radio (or none) it sits behind.
     */
    @Column(name = "protocol", nullable = false, length = 10)
    private String protocol = DeviceProtocol.HTTP.name();

    /**
     * Whether this device's telemetry is real or simulated — see {@link DeviceSource}.
     *
     * <p>Orthogonal to {@code transport}: a simulated device still declares the network it emulates,
     * and is addressed on it exactly as the device it stands in for would be.
     */
    @Column(name = "source", nullable = false, length = 16)
    private String source = DeviceSource.LIVE.name();

    @Column(name = "status", nullable = false, length = 20)
    private String status = "PROVISIONED";

    @Column(name = "firmware_version", length = 40)
    private String firmwareVersion;

    @Column(name = "battery_v", precision = 5, scale = 2)
    private BigDecimal batteryV;

    @Column(name = "rssi", precision = 6, scale = 2)
    private BigDecimal rssi;

    @Column(name = "snr", precision = 5, scale = 2)
    private BigDecimal snr;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "provisioning", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> provisioning = new java.util.HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> attributes = new java.util.HashMap<>();

    /** Updates the last-known radio state from an uplink. Idempotent across replays. */
    public void registerUplink(java.math.BigDecimal battery, java.math.BigDecimal rssi,
                               java.math.BigDecimal snr, Instant seenAt) {
        this.lastSeenAt = seenAt;
        if (battery != null) this.batteryV = battery;
        if (rssi != null) this.rssi = rssi;
        if (snr != null) this.snr = snr;
    }
}

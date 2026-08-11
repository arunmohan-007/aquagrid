package com.aquagrid.platform.gis.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A storage tank — type-specific extension of {@link Asset}.
 *
 * <p>Shares the asset's identity ({@code asset_id} is both PK and FK to {@code gis.assets}) and
 * holds only the engineering data a tank carries: capacity, live level, elevations. The live level
 * is refreshed by telemetry (Module 13) and rendered as a gauge on the map.
 */
@Getter
@Setter
@Entity
@Table(name = "tanks", schema = "gis")
public class Tank {

    @Id
    @Column(name = "asset_id", nullable = false, updatable = false)
    private UUID assetId;

    @Column(name = "capacity_m3", nullable = false, precision = 10, scale = 2)
    private BigDecimal capacityM3;

    /** Operational heartbeat — updated by telemetry ingest. Nullable until first reading. */
    @Column(name = "current_level_m3", precision = 10, scale = 2)
    private BigDecimal currentLevelM3;

    @Column(name = "base_elevation_m", precision = 8, scale = 2)
    private BigDecimal baseElevationM;

    @Column(name = "overflow_elevation_m", precision = 8, scale = 2)
    private BigDecimal overflowElevationM;

    @Column(name = "inlet_elevation_m", precision = 8, scale = 2)
    private BigDecimal inletElevationM;

    @Column(name = "tank_type", nullable = false, length = 30)
    private String tankType = "ELEVATED";

    @Column(name = "material", length = 40)
    private String material;
}

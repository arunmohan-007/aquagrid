package com.aquagrid.platform.gis.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A raw-water or treated-water reservoir — large impoundment, not a pressurised tank.
 *
 * <p>{@code surfaceAreaM2} matters for water-balance analysis (Module 26): evaporation loss is
 * modelled against surface area, and an unaccounted evaporation term is a classic NRW error source.
 */
@Getter
@Setter
@Entity
@Table(name = "reservoirs", schema = "gis")
public class Reservoir {

    @Id
    @Column(name = "asset_id", nullable = false, updatable = false)
    private UUID assetId;

    @Column(name = "max_capacity_m3", nullable = false, precision = 12, scale = 2)
    private BigDecimal maxCapacityM3;

    @Column(name = "current_volume_m3", precision = 12, scale = 2)
    private BigDecimal currentVolumeM3;

    @Column(name = "source_type", nullable = false, length = 30)
    private String sourceType;

    @Column(name = "surface_area_m2", precision = 10, scale = 2)
    private BigDecimal surfaceAreaM2;

    @Column(name = "max_depth_m", precision = 6, scale = 2)
    private BigDecimal maxDepthM;

    @Column(name = "intake_elevation_m", precision = 8, scale = 2)
    private BigDecimal intakeElevationM;
}

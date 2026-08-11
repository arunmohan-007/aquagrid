package com.aquagrid.platform.gis.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A pump station — one or more pumps with rated curves.
 *
 * <p>{@code pumpCurve} is JSONB because pump curves are vendor-published head/flow point sets;
 * forcing them into fixed columns would freeze the schema to one manufacturer's convention. The
 * curve drives efficiency analysis (Module 28) and the hydraulic model.
 *
 * <p>{@code pumpStates} is the live per-pump operating status (RUNNING/STANDBY/FAULT/OFFLINE),
 * refreshed by SCADA or telemetry.
 */
@Getter
@Setter
@Entity
@Table(name = "pump_stations", schema = "gis")
public class PumpStation {

    @Id
    @Column(name = "asset_id", nullable = false, updatable = false)
    private UUID assetId;

    @Column(name = "pump_count", nullable = false)
    private int pumpCount;

    @Column(name = "rated_flow_lpm", precision = 10, scale = 2)
    private BigDecimal ratedFlowLpm;

    @Column(name = "rated_head_m", precision = 10, scale = 2)
    private BigDecimal ratedHeadM;

    @Column(name = "rated_power_kw", precision = 10, scale = 2)
    private BigDecimal ratedPowerKw;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pump_states", nullable = false, columnDefinition = "jsonb")
    private List<String> pumpStates = new java.util.ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pump_curve", columnDefinition = "jsonb")
    private List<Map<String, Object>> pumpCurve;

    @Column(name = "suction_elevation_m", precision = 8, scale = 2)
    private BigDecimal suctionElevationM;

    @Column(name = "discharge_elevation_m", precision = 8, scale = 2)
    private BigDecimal dischargeElevationM;
}

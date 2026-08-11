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
 * A valve — a network control point.
 *
 * <p>Sits on a {@code network_node} (Module 11); its {@code status} (OPEN/CLOSED) is the boundary
 * the isolation trace halts at. {@code normalState} is the designed-default — most distribution
 * valves are normally-open, PRVs and boundary valves normally-closed — and drives the
 * "return to normal" close-out step after an isolation event.
 */
@Getter
@Setter
@Entity
@Table(name = "valves", schema = "gis")
public class Valve {

    @Id
    @Column(name = "asset_id", nullable = false, updatable = false)
    private UUID assetId;

    @Column(name = "node_id")
    private UUID nodeId;

    @Column(name = "valve_type", nullable = false, length = 30)
    private String valveType = "GATE";

    @Column(name = "diameter_mm", precision = 7, scale = 1)
    private BigDecimal diameterMm;

    /** Current operating state. The isolation-trace boundary. */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "CLOSED";

    /** Designed-default position. Drives return-to-normal. */
    @Column(name = "normal_state", nullable = false, length = 20)
    private String normalState = "OPEN";

    @Column(name = "pressure_setpoint_bar", precision = 5, scale = 2)
    private BigDecimal pressureSetpointBar;

    @Column(name = "turns_to_operate")
    private Integer turnsToOperate;

    @Column(name = "manufacturer", length = 80)
    private String manufacturer;

    @Column(name = "model_number", length = 80)
    private String modelNumber;

    /** Applies a state transition and returns the previous state, for the audit log. */
    public String operate(String toState) {
        String previous = this.status;
        this.status = toState;
        return previous;
    }
}

package com.aquagrid.platform.gis.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One valve operation — an append-only audit entry.
 *
 * <p>{@code BIGSERIAL} like telemetry and login attempts: write-heavy, insert-ordered, never
 * addressed singly by a client. The evidence chain for "was this valve operated correctly?" — a
 * question regulators and incident investigators ask after any supply event.
 */
@Getter
@Setter
@Entity
@Table(name = "valve_operations", schema = "gis")
public class ValveOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "valve_asset_id", nullable = false)
    private UUID valveAssetId;

    @Column(name = "from_state", nullable = false, length = 20)
    private String fromState;

    @Column(name = "to_state", nullable = false, length = 20)
    private String toState;

    @Column(name = "operated_by", nullable = false)
    private UUID operatedBy;

    @Column(name = "operated_at", nullable = false)
    private Instant operatedAt;

    @Column(name = "reason", length = 300)
    private String reason;

    @Column(name = "work_order_id")
    private UUID workOrderId;

    @Column(name = "client_ip", columnDefinition = "inet")
    private String clientIp;
}

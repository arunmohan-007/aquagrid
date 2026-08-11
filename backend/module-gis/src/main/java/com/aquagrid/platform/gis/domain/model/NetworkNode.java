package com.aquagrid.platform.gis.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.locationtech.jts.geom.Point;

import java.time.Instant;
import java.util.UUID;

/**
 * A network junction — a vertex in the pipeline graph.
 *
 * <p>Auto-created when a pipe endpoint snaps to a location with no existing node within tolerance.
 * Carries geometry so traces return spatial results (map highlight, nearest-asset lookup).
 */
@Getter
@Setter
@Entity
@Table(name = "network_nodes", schema = "gis")
public class NetworkNode {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "geom", nullable = false, columnDefinition = "geometry(Point,4326)")
    private Point geom;

    @Column(name = "label", length = 120)
    private String label;

    @Column(name = "node_type", nullable = false, length = 30)
    private String nodeType = "JUNCTION";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

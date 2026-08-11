package com.aquagrid.platform.gis.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.locationtech.jts.geom.LineString;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A pipe — a graph edge in the pipeline network.
 *
 * <p>Extends {@link Asset} (PK = FK to {@code gis.assets}) with the engineering data and the
 * {@code from_node}/{@code to_node} references that make it an edge. The geometry is a LineString
 * (denormalised from the asset, written together) so {@code length_m} can be GENERATED and tracing
 * can read the edge geometry directly.
 */
@Getter
@Setter
@Entity
@Table(name = "pipelines", schema = "gis")
public class Pipeline {

    @Id
    @Column(name = "asset_id", nullable = false, updatable = false)
    private UUID assetId;

    @Column(name = "geom", nullable = false, columnDefinition = "geometry(LineString,4326)")
    private LineString geom;

    @Column(name = "from_node_id")
    private UUID fromNodeId;

    @Column(name = "to_node_id")
    private UUID toNodeId;

    @Column(name = "diameter_mm", precision = 7, scale = 1)
    private BigDecimal diameterMm;

    @Column(name = "material", length = 40)
    private String material;

    /** GENERATED in the DB; read-only here. */
    @Column(name = "length_m", precision = 10, scale = 2, insertable = false, updatable = false)
    private BigDecimal lengthM;

    @Column(name = "flow_direction", nullable = false, length = 20)
    private String flowDirection = "BIDIRECTIONAL";

    @Column(name = "roughness", precision = 4, scale = 3)
    private BigDecimal roughness;

    @Column(name = "pressure_class", precision = 6, scale = 2)
    private BigDecimal pressureClass;
}

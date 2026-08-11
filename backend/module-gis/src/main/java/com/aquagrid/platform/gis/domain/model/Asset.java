package com.aquagrid.platform.gis.domain.model;

import com.aquagrid.platform.common.domain.AuditableEntity;
import com.aquagrid.platform.gis.domain.enums.AssetStatus;
import com.aquagrid.platform.gis.domain.enums.AssetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Geometry;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * A spatial asset: a meter, valve, pipe, tank or any other physical thing with a location.
 *
 * <p>The supertype owns the columns common to every asset type. Type-specific tables (added by
 * later modules) reference this row by id and hold their own columns. This keeps "show every asset
 * in this viewport" a single indexed query while retaining strong typing per type.
 *
 * <p>{@code geom} is the authoritative geometry in EPSG:4326. {@code geom3857} is a database-generated
 * Web-Mercator reprojection used by {@code ST_AsMVT}; it is marked read-only here so Hibernate
 * never attempts to write it (the GENERATED ALWAYS column would reject the insert).
 */
@Getter
@Setter
@Entity
@Table(name = "assets", schema = "gis")
public class Asset extends AuditableEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "asset_code", nullable = false, length = 80)
    private String assetCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false, length = 40, updatable = false)
    private AssetType assetType;

    /**
     * The registry layer this feature belongs to (V1332).
     *
     * <p>Nullable, and it stays that way. Rows written before Layer Management were backfilled from
     * the primary layer of their asset type, but a tenant provisioned after the layer-seeding
     * migrations has no layer rows at all — a gap {@code V1331} already records — so a NOT NULL here
     * would turn that latent hole into a failed insert. Readers resolve a layer's features as "rows
     * claimed by this layer, plus unclaimed rows of its asset type", which is exact for everything
     * written from here on and unchanged for everything before.
     *
     * <p>A plain id rather than a {@code @ManyToOne}: the asset is read in bulk by the tile, export
     * and import paths, and none of them wants a layer entity fetched per row.
     */
    @Column(name = "layer_id")
    private UUID layerId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AssetStatus status = AssetStatus.IN_SERVICE;

    @Column(name = "install_date")
    private LocalDate installDate;

    @Column(name = "decommission_date")
    private LocalDate decommissionDate;

    /**
     * The authoritative geometry, SRID 4326 (lon/lat). Hibernate Spatial maps this to PostGIS
     * {@code geometry}; the concrete JTS type (Point, LineString, MultiPolygon) is set by the
     * caller and respected by the database.
     */
    @Column(name = "geom", nullable = false, columnDefinition = "geometry")
    private Geometry geom;

    /** Read-only generated Web-Mercator geometry. Populated by the database; never written by JPA. */
    @org.hibernate.annotations.ColumnDefault("null")
    @Column(name = "geom_3857", insertable = false, updatable = false, columnDefinition = "geometry")
    private Geometry geom3857;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> attributes = new java.util.HashMap<>();
}

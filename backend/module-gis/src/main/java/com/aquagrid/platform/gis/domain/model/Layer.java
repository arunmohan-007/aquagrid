package com.aquagrid.platform.gis.domain.model;

import com.aquagrid.platform.common.domain.AuditableEntity;
import com.aquagrid.platform.gis.domain.enums.AssetType;
import com.aquagrid.platform.gis.domain.enums.GeometryType;
import com.aquagrid.platform.gis.domain.enums.LayerStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;
import java.util.UUID;

/**
 * A registered GIS layer — the master record for everything the platform draws, catalogues,
 * imports, exports and styles.
 *
 * <p>This row is the join point of three modules and that is the whole architecture in one
 * sentence: Layer Management owns the row, Data Management hangs
 * {@code gis.layer_attribute_master} off its primary key, and Layer Style Management hangs
 * {@code gis.layer_style} off it. None of the three duplicates the others' data, and there is no
 * second table naming the same layers — V1330 explains at length why a {@code layer_master} beside
 * this one would drift from the one the map and the tile endpoint already read.
 *
 * <p>Three field names predate the registry and are kept: {@link #code} is the layer name (the
 * stable machine identifier used as a MapLibre source id and a tile-URL path segment),
 * {@link #title} is the display name, and {@link #visible} is visible-by-default. Renaming them
 * would rewrite tile URLs that browsers have already cached, to say the same thing in different
 * words.
 */
@Getter
@Setter
@Entity
@Table(name = "layers", schema = "gis")
public class Layer extends AuditableEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    /**
     * The layer name: lower-case, digits and hyphens, unique per tenant, immutable.
     *
     * <p>Not editable after creation, and the reason is not tidiness. This string is the
     * {@code source-layer} inside every vector tile the client has cached, the MapLibre source id
     * every render layer references, and the key of every saved import mapping profile. Renaming it
     * would invalidate all three silently — the map would simply stop drawing the layer, with no
     * error anywhere. The display name is what an operator reads, and that is freely editable.
     */
    @Column(name = "code", nullable = false, length = 60, updatable = false)
    private String code;

    /** Display name. The label on the map, the legend, the registry and every list. */
    @Column(name = "title", nullable = false, length = 120)
    private String title;

    /**
     * What the layer is for, in a sentence.
     *
     * <p>Added in V1331 for the Data Management screen. {@code title} is a label — an administrator
     * choosing which layer to add a field to should not have to infer "district metered areas,
     * hydraulic districts defined by the network" from "DMAs".
     */
    @Column(name = "description", length = 300)
    private String description;

    /**
     * How the registry groups the layer: Pipe Network, Point Assets, Facilities, Boundaries, Other.
     *
     * <p>Free text with a served vocabulary rather than an enum, because it is a filing decision
     * with no behaviour attached. A utility that wants a "Sewerage" group should get one by typing
     * it, not by waiting for a release — and nothing in the platform branches on this value.
     */
    @Column(name = "category", length = 60)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false, length = 40, updatable = false)
    private AssetType assetType;

    /**
     * The geometry this layer declares it holds.
     *
     * <p>A declaration checked on write by {@link GeometryType#accepts}, not a column type. Every
     * layer's geometry lives in the single bare {@code geometry} column on {@code gis.assets}, which
     * is what lets this be changed on a live layer without a migration — and what makes checking it
     * in Java the only place it can be checked at all.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "geometry_type", nullable = false, length = 20)
    private GeometryType geometryType = GeometryType.GEOMETRY;

    /** CRS authority, almost always {@code EPSG}. Stored beside the SRID so the pair reads as one. */
    @Column(name = "crs_authority", nullable = false, length = 20)
    private String crsAuthority = "EPSG";

    /**
     * The SRID the layer's geometry is stored in.
     *
     * <p>4326 for everything today, because {@code gis.assets.geom} is 4326 and its generated
     * Web-Mercator twin is what tiles are cut from. Recorded per layer anyway: a layer whose source
     * data arrives in a local grid needs its native CRS written down somewhere the export path can
     * read it, and "the SRID is always 4326" is exactly the assumption that is true until the first
     * state utility hands over a survey in EPSG:32644.
     */
    @Column(name = "srid", nullable = false)
    private int srid = 4326;

    /**
     * Where this layer's features physically live — {@code gis.assets} for every layer today.
     *
     * <p>Recorded rather than assumed. The registry's job is to describe the estate, and a
     * description that hard-codes one table cannot describe an externally-managed table when one
     * arrives. It is never interpolated into SQL: the read path resolves features through
     * {@code AssetRepository} on the strength of {@link #getId()}, so this column cannot become an
     * injection vector no matter what is stored in it.
     */
    @Column(name = "feature_table", nullable = false, length = 120)
    private String featureTable = "gis.assets";

    /** The geometry column within {@link #featureTable}. Same reasoning; never interpolated. */
    @Column(name = "geometry_column", nullable = false, length = 63)
    private String geometryColumn = "geom";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private LayerStatus status = LayerStatus.ACTIVE;

    /** Whether the layer is switched on in the map's layer control when the console opens. */
    @Column(name = "is_visible", nullable = false)
    private boolean visible = true;

    @Column(name = "is_editable", nullable = false)
    private boolean editable = true;

    /** Whether clicking a feature of this layer opens the inspection card. */
    @Column(name = "is_queryable", nullable = false)
    private boolean queryable = true;

    /** Whether the map's search box looks in this layer. */
    @Column(name = "is_searchable", nullable = false)
    private boolean searchable = true;

    @Column(name = "import_enabled", nullable = false)
    private boolean importEnabled = true;

    @Column(name = "export_enabled", nullable = false)
    private boolean exportEnabled = true;

    /**
     * Whether the generic tile endpoint serves this layer.
     *
     * <p>Separate from {@link #visible} because they answer different questions: visible is whether
     * the layer starts switched on, this is whether tiles exist to switch on at all. A layer held
     * for export only — a boundary set used to clip reports and never drawn — sets this false and
     * stops paying for tile generation it has no reader for.
     */
    @Column(name = "vector_tile_enabled", nullable = false)
    private boolean vectorTileEnabled = true;

    @Column(name = "min_zoom", nullable = false)
    private short minZoom = 0;

    @Column(name = "max_zoom", nullable = false)
    private short maxZoom = 24;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /**
     * True for layers the platform's own code names by asset type.
     *
     * <p>The dashboard sums {@code PIPELINE} length, the network trace walks {@code PIPELINE} and
     * {@code VALVE}, the import hub's catalogue targets these types. Their labels, category,
     * visibility, styling, zoom range and flags are the tenant's to change; their code and asset
     * type are not, and they cannot be archived out from under the code that reads them.
     */
    @Column(name = "is_system", nullable = false)
    private boolean system = false;

    /**
     * The pre-Layer-Style-Management style blob, superseded by {@code gis.layer_style}.
     *
     * <p>V1300 added it as a placeholder for "future: SLD/MapLibre style blob" and nothing ever
     * wrote to it. It is left in place rather than dropped because dropping a column is not free —
     * it rewrites the table and cannot be undone by a rollback that only reverses code — and an
     * empty JSONB column costs nothing. Nothing reads it; styles come from
     * {@code gis.layer_style}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "style", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> style = new java.util.HashMap<>();

    /** {@code EPSG:4326} — the CRS as an operator writes it. */
    public String crs() {
        return crsAuthority + ":" + srid;
    }

    /** Whether the map, the tile endpoint and the import hub should offer this layer. */
    public boolean isUsable() {
        return status.isUsable();
    }
}

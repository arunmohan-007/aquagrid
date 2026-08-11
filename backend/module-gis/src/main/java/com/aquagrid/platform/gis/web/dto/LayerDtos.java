package com.aquagrid.platform.gis.web.dto;

import com.aquagrid.platform.gis.application.service.LayerManagementService;
import com.aquagrid.platform.gis.domain.enums.GeometryType;
import com.aquagrid.platform.gis.domain.model.Layer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for the Layer Management API. */
public final class LayerDtos {

    private LayerDtos() {
    }

    // ---- Responses -----------------------------------------------------------------------------

    /**
     * A layer as the registry shows it.
     *
     * <p>Carries the full audit quartet because the grid has columns for them, and the actor as a
     * raw id rather than a name: resolving user names would mean the GIS module reading
     * {@code identity.users}, the cross-module join the architecture forbids. The client already
     * holds the directory it needs.
     */
    @Schema(name = "GisLayer", description = "A registered GIS layer and its configuration")
    public record LayerResponse(
            UUID id,
            @Schema(description = "Layer name — the stable identifier used in tile URLs. Immutable.")
            String code,
            @Schema(description = "Display name") String title,
            String description,
            String category,
            @Schema(description = "Which physical bucket in gis.assets holds this layer's features")
            String assetType,
            String geometryType,
            @Schema(description = "The three-way simplification: POINT, LINE, POLYGON or ANY")
            String geometryFamily,
            @Schema(description = "e.g. EPSG:4326") String crs,
            String crsAuthority,
            int srid,
            @Schema(description = "Where the features live. gis.assets for every layer today.")
            String featureTable,
            String geometryColumn,
            String status,
            boolean visibleByDefault,
            boolean editable,
            boolean queryable,
            boolean searchable,
            boolean importEnabled,
            boolean exportEnabled,
            boolean vectorTileEnabled,
            int minZoom,
            int maxZoom,
            int sortOrder,
            @Schema(description = "True for layers the platform's own code reads by asset type. "
                    + "Their code and asset type are locked and they cannot be archived.")
            boolean system,
            @Schema(description = "Features on this layer, or null when counts were not requested")
            Long featureCount,
            @Schema(description = "[minLon, minLat, maxLon, maxLat] in EPSG:4326, or null when empty")
            double[] extent,
            UUID createdBy,
            Instant createdDate,
            UUID modifiedBy,
            Instant modifiedDate
    ) {
        public static LayerResponse from(LayerManagementService.LayerSummary summary) {
            Layer l = summary.layer();
            return new LayerResponse(
                    l.getId(), l.getCode(), l.getTitle(), l.getDescription(), l.getCategory(),
                    l.getAssetType().name(), l.getGeometryType().name(),
                    l.getGeometryType().family().name(), l.crs(), l.getCrsAuthority(), l.getSrid(),
                    l.getFeatureTable(), l.getGeometryColumn(), l.getStatus().name(),
                    l.isVisible(), l.isEditable(), l.isQueryable(), l.isSearchable(),
                    l.isImportEnabled(), l.isExportEnabled(), l.isVectorTileEnabled(),
                    l.getMinZoom(), l.getMaxZoom(), l.getSortOrder(), l.isSystem(),
                    // -1 is the service's "not asked" marker. Null on the wire, so a client can tell
                    // "we did not count" from "the layer is empty" — which mean very different things
                    // to someone checking whether an import worked.
                    summary.featureCount() < 0 ? null : summary.featureCount(),
                    summary.extent(),
                    l.getCreatedBy(), l.getCreatedAt(), l.getUpdatedBy(), l.getUpdatedAt());
        }
    }

    /** One geometry option, served from the enum the server validates against. */
    @Schema(name = "GeometryTypeOption")
    public record GeometryTypeResponse(
            String value,
            String label,
            String family,
            @Schema(description = "True for POINT, LINESTRING and POLYGON — the simple chooser's set")
            boolean simple,
            boolean multi
    ) {
        public static GeometryTypeResponse from(GeometryType type) {
            return new GeometryTypeResponse(type.name(), label(type), type.family().name(),
                    type.isSimpleChoice(), type.isMulti());
        }

        private static String label(GeometryType type) {
            return switch (type) {
                case POINT -> "Point";
                case MULTIPOINT -> "Multi Point";
                case LINESTRING -> "Line";
                case MULTILINESTRING -> "Multi Line";
                case POLYGON -> "Polygon";
                case MULTIPOLYGON -> "Multi Polygon";
                case GEOMETRY -> "Any geometry";
                case GEOMETRYCOLLECTION -> "Geometry Collection";
            };
        }
    }

    /** One CRS, read from PostGIS's own {@code spatial_ref_sys}. */
    @Schema(name = "CrsOption")
    public record CrsResponse(int srid, String authority, String title, String code) {
        public static CrsResponse from(LayerManagementService.CrsOption option) {
            return new CrsResponse(option.srid(), option.authority(), option.title(),
                    option.authority() + ":" + option.srid());
        }
    }

    /** A layer's live statistics, for the preview panel. */
    @Schema(name = "LayerStatistics")
    public record StatisticsResponse(
            long featureCount,
            String geometryType,
            String crs,
            double[] extent
    ) {
    }

    // ---- Requests ------------------------------------------------------------------------------

    @Schema(name = "CreateLayerRequest")
    public record CreateRequest(
            @Schema(description = "Lower-case letters, numbers and hyphens. Derived from the display "
                    + "name when omitted. Permanent — it appears in tile URLs.")
            @Size(max = 60) String code,
            @NotBlank @Size(max = 120) String title,
            @Size(max = 300) String description,
            @Size(max = 60) String category,
            @Schema(description = "One of the platform's asset types, or omitted for CUSTOM — the "
                    + "right answer for a layer the platform's own code knows nothing about.")
            String assetType,
            @Schema(description = "POINT, LINESTRING, POLYGON and their MULTI forms, or GEOMETRY")
            String geometryType,
            @Size(max = 20) String crsAuthority,
            Integer srid,
            Boolean active,
            Boolean visibleByDefault,
            Boolean editable,
            Boolean queryable,
            Boolean searchable,
            Boolean importEnabled,
            Boolean exportEnabled,
            Boolean vectorTileEnabled,
            @Min(0) @Max(24) Integer minZoom,
            @Min(0) @Max(24) Integer maxZoom,
            Integer sortOrder,
            @Schema(description = "Claim existing unassigned features of this asset type. The import "
                    + "wizard sets this; the registry's create form does not.")
            Boolean claimExistingFeatures
    ) {
    }

    @Schema(name = "UpdateLayerRequest",
            description = "Every field optional; omitting one leaves it unchanged rather than "
                    + "clearing it. Layer name and asset type are absent because both are permanent.")
    public record UpdateRequest(
            @Size(max = 120) String title,
            @Size(max = 300) String description,
            @Size(max = 60) String category,
            String geometryType,
            @Size(max = 20) String crsAuthority,
            Integer srid,
            Boolean visibleByDefault,
            Boolean editable,
            Boolean queryable,
            Boolean searchable,
            Boolean importEnabled,
            Boolean exportEnabled,
            Boolean vectorTileEnabled,
            @Min(0) @Max(24) Integer minZoom,
            @Min(0) @Max(24) Integer maxZoom,
            Integer sortOrder
    ) {
    }

    @Schema(name = "LayerStateChangeRequest")
    public record StateChangeRequest(@Size(max = 500) String reason) {
    }

    /** Values observed in the data, for the categorical style editor. */
    @Schema(name = "FieldValues")
    public record FieldValuesResponse(String fieldName, List<String> values, Double minimum, Double maximum) {
    }
}

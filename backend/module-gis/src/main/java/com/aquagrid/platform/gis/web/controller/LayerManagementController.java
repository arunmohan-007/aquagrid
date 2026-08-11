package com.aquagrid.platform.gis.web.controller;

import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.common.web.ApiPaths;
import com.aquagrid.platform.gis.application.command.LayerCommands;
import com.aquagrid.platform.gis.application.service.LayerManagementService;
import com.aquagrid.platform.gis.application.service.LayerManagementService.LayerQuery;
import com.aquagrid.platform.gis.domain.enums.AssetType;
import com.aquagrid.platform.gis.domain.enums.GeometryType;
import com.aquagrid.platform.gis.domain.enums.LayerStatus;
import com.aquagrid.platform.gis.domain.model.Layer;
import com.aquagrid.platform.gis.web.dto.LayerDtos;
import com.aquagrid.platform.security.core.AuthenticatedPrincipal;
import com.aquagrid.platform.security.core.Permissions;
import com.aquagrid.platform.security.core.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Layer Management — the registry of every GIS layer the platform draws, catalogues and imports to.
 *
 * <p>This API creates layers without creating tables. A new layer is a row in {@code gis.layers}
 * backed by the {@code gis.assets} supertype, which already provides geometry in EPSG:4326, a
 * generated Web-Mercator column, GiST indexes on both, and a GIN-indexed attribute bag. What the
 * brief wanted from a per-layer PostGIS table — a declared geometry type, a declared SRID, a spatial
 * index, validated names, no injection — is all here; what it would have cost is not. Runtime DDL
 * means the web tier holding those rights on its own schema, and it would make a new layer invisible
 * to the tile endpoint, the importer and the register until each was taught to find it.
 *
 * <p>Attributes are not here. {@code POST /data-management/attributes} is the only way to add a
 * field to a layer, before and after this module; the registry links to it rather than reimplementing
 * it.
 */
@Tag(name = "Layer Management",
        description = "The GIS layer registry — what the platform draws, catalogues and imports to")
@RestController
@RequestMapping(value = ApiPaths.LAYERS, produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class LayerManagementController {

    private final LayerManagementService layerService;

    // ---- Registry ------------------------------------------------------------------------------

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.LAYER_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "List the registry",
            description = """
                    Every layer the tenant has, filterable by status, category, geometry and free
                    text. Archived layers are excluded unless `status=ARCHIVED` is asked for
                    explicitly: an archived layer is a decision someone made to retire, and listing
                    it beside live ones is how it gets re-enabled by accident.

                    `withCounts` computes the feature count and extent per row from indexed
                    aggregates — never by fetching features, so a layer with two million service
                    connections costs what one with four tanks costs. Pass false for a picker that
                    only needs names.""")
    public List<LayerDtos.LayerResponse> list(
            @RequestParam(required = false) LayerStatus status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) GeometryType geometryType,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "true") boolean withCounts) {

        UUID orgId = SecurityUtils.requirePrincipal().organizationId();
        return layerService.list(orgId, new LayerQuery(status, category, geometryType, search, withCounts))
                .stream().map(LayerDtos.LayerResponse::from).toList();
    }

    @GetMapping("/{layerId}")
    @PreAuthorize("hasAuthority('" + Permissions.LAYER_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "One layer, with its live feature count and extent")
    public LayerDtos.LayerResponse get(@PathVariable UUID layerId) {
        UUID orgId = SecurityUtils.requirePrincipal().organizationId();
        return LayerDtos.LayerResponse.from(layerService.get(orgId, layerId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.LAYER_MANAGE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a layer",
            description = """
                    Creates a layer that is usable the moment this returns: it appears in the map's
                    layer control, the tile endpoint serves it, Data Management accepts attributes on
                    it and the import hub offers it as a target. No migration, no restart, no code.

                    No table is created. The layer's features live in `gis.assets` alongside every
                    other layer's, separated by `layer_id` and described by the `geometry_type` and
                    `srid` declared here — which is why those two can be corrected later without a
                    migration, and why the layer name can never be.

                    The layer name is permanent because it is the `source-layer` inside every vector
                    tile and the MapLibre source id every render layer references. Derived from the
                    display name when omitted.""")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "409", description = "A layer with that name already exists")
    public LayerDtos.LayerResponse create(@Valid @RequestBody LayerDtos.CreateRequest request) {
        AuthenticatedPrincipal principal = SecurityUtils.requirePrincipal();
        Layer created = layerService.create(principal.organizationId(), principal.userId(),
                principal.username(),
                new LayerCommands.Create(
                        request.code(), request.title(), request.description(), request.category(),
                        parseAssetType(request.assetType()), parseGeometry(request.geometryType()),
                        request.crsAuthority(), request.srid(), request.active(),
                        request.visibleByDefault(), request.editable(), request.queryable(),
                        request.searchable(), request.importEnabled(), request.exportEnabled(),
                        request.vectorTileEnabled(), request.minZoom(), request.maxZoom(),
                        request.sortOrder(), Boolean.TRUE.equals(request.claimExistingFeatures())));
        return LayerDtos.LayerResponse.from(layerService.get(principal.organizationId(), created.getId()));
    }

    @PutMapping("/{layerId}")
    @PreAuthorize("hasAuthority('" + Permissions.LAYER_MANAGE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Edit a layer",
            description = """
                    Every field is optional; omitting one leaves it unchanged rather than clearing
                    it, so a form that renders half the fields cannot blank the other half.

                    The layer name and the asset type are not editable and are not in the body. The
                    name is the `source-layer` inside every cached tile — renaming it would stop the
                    map drawing the layer, with no error anywhere. The asset type decides which
                    physical bucket the features are in, so changing it would orphan them rather than
                    move them.""")
    public LayerDtos.LayerResponse update(@PathVariable UUID layerId,
                                          @Valid @RequestBody LayerDtos.UpdateRequest request) {
        AuthenticatedPrincipal principal = SecurityUtils.requirePrincipal();
        layerService.update(principal.organizationId(), principal.userId(), principal.username(),
                layerId,
                new LayerCommands.Update(
                        request.title(), request.description(), request.category(),
                        parseGeometry(request.geometryType()), request.crsAuthority(), request.srid(),
                        request.visibleByDefault(), request.editable(), request.queryable(),
                        request.searchable(), request.importEnabled(), request.exportEnabled(),
                        request.vectorTileEnabled(), request.minZoom(), request.maxZoom(),
                        request.sortOrder()));
        return LayerDtos.LayerResponse.from(layerService.get(principal.organizationId(), layerId));
    }

    // ---- Lifecycle -----------------------------------------------------------------------------

    @PostMapping("/{layerId}/enable")
    @PreAuthorize("hasAuthority('" + Permissions.LAYER_MANAGE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Return a layer to service")
    public LayerDtos.LayerResponse enable(@PathVariable UUID layerId,
                                          @RequestBody(required = false) LayerDtos.StateChangeRequest request) {
        return changeStatus(layerId, LayerStatus.ACTIVE, request);
    }

    @PostMapping("/{layerId}/disable")
    @PreAuthorize("hasAuthority('" + Permissions.LAYER_MANAGE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Withdraw a layer temporarily",
            description = """
                    Off the map and out of the import hub, still in the registry and still holding
                    every feature — the state for a layer being re-surveyed, whose data is not to be
                    trusted this month but will be next. Nothing is deleted.""")
    public LayerDtos.LayerResponse disable(@PathVariable UUID layerId,
                                           @RequestBody(required = false) LayerDtos.StateChangeRequest request) {
        return changeStatus(layerId, LayerStatus.INACTIVE, request);
    }

    @PostMapping("/{layerId}/archive")
    @PreAuthorize("hasAuthority('" + Permissions.LAYER_MANAGE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Retire a layer",
            description = """
                    Hidden from the registry's ordinary list as well as the map, and shown only when
                    archived layers are asked for. Every feature stays exactly where it was — a
                    layer's features are surveyed geometry that cost a contractor a season, and no
                    button on this screen can remove them.

                    System layers cannot be archived: the dashboard sums the pipeline layer's length
                    and the network trace walks pipelines and valves, so archiving one would leave
                    the platform's own code querying a layer the registry says is gone. Disable it
                    instead.""")
    @ApiResponse(responseCode = "403", description = "The layer is a system layer")
    public LayerDtos.LayerResponse archive(@PathVariable UUID layerId,
                                           @RequestBody(required = false) LayerDtos.StateChangeRequest request) {
        return changeStatus(layerId, LayerStatus.ARCHIVED, request);
    }

    // ---- Statistics and field values -------------------------------------------------------------

    @GetMapping("/{layerId}/statistics")
    @PreAuthorize("hasAuthority('" + Permissions.LAYER_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Feature count and extent",
            description = """
                    Both computed in PostGIS: `COUNT(*)` against a composite index and `ST_Extent`
                    over the GiST-indexed 4326 geometry. Neither loads a feature into application
                    memory, which is what makes them affordable on a layer of any size.""")
    public LayerDtos.StatisticsResponse statistics(@PathVariable UUID layerId) {
        UUID orgId = SecurityUtils.requirePrincipal().organizationId();
        Layer layer = layerService.require(orgId, layerId);
        return new LayerDtos.StatisticsResponse(
                layerService.featureCount(orgId, layer),
                layer.getGeometryType().name(),
                layer.crs(),
                layerService.extent(orgId, layer).orElse(null));
    }

    @GetMapping("/{layerId}/field-values")
    @PreAuthorize("hasAuthority('" + Permissions.LAYER_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "The values a field actually holds",
            description = """
                    Feeds the categorical and graduated style editors, so an administrator
                    classifying on `status` chooses from the values in their own data rather than
                    typing them from memory — which is how a category ends up spelled FAULTY in the
                    style and FAULT in the rows, rendering as a class that matches nothing and looks
                    like a broken renderer.

                    Distinct values are capped; a field with thousands of them is not a category
                    axis. `minimum` and `maximum` are returned as well, from a guarded numeric cast,
                    so the graduated editor can suggest bands over the real range.""")
    public LayerDtos.FieldValuesResponse fieldValues(@PathVariable UUID layerId,
                                                     @RequestParam String fieldName) {
        UUID orgId = SecurityUtils.requirePrincipal().organizationId();
        Layer layer = layerService.require(orgId, layerId);
        double[] range = layerService.numericRange(orgId, layer, fieldName).orElse(null);
        return new LayerDtos.FieldValuesResponse(fieldName,
                layerService.distinctValues(orgId, layer, fieldName),
                range == null ? null : range[0],
                range == null ? null : range[1]);
    }

    // ---- Reference data ------------------------------------------------------------------------

    @GetMapping("/geometry-types")
    @PreAuthorize("hasAuthority('" + Permissions.LAYER_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "The geometry types a layer can declare",
            description = """
                    Served from the enum the server validates against, so the create form cannot
                    offer a type the server would reject — the same rule the device registration form
                    follows for transports.

                    `simple` marks the three an ordinary administrator chooses between: Point, Line,
                    Polygon. The MULTI forms and GEOMETRY are there for the GIS specialist whose
                    deliverables need them.""")
    public List<LayerDtos.GeometryTypeResponse> geometryTypes() {
        return Arrays.stream(GeometryType.values()).map(LayerDtos.GeometryTypeResponse::from).toList();
    }

    @GetMapping("/asset-types")
    @PreAuthorize("hasAuthority('" + Permissions.LAYER_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "The asset types a layer can be backed by",
            description = "CUSTOM is the right answer for a layer the platform's own code knows "
                    + "nothing about; the others tie the layer into the dashboard, the network trace "
                    + "and the typed detail tables that already understand them.")
    public List<LayerDtos.GeometryTypeResponse> assetTypes() {
        return Arrays.stream(AssetType.values())
                .map(t -> new LayerDtos.GeometryTypeResponse(t.name(), humanise(t.name()), "", false, false))
                .toList();
    }

    @GetMapping("/categories")
    @PreAuthorize("hasAuthority('" + Permissions.LAYER_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Layer categories",
            description = "The seeded set merged with whatever this tenant is actually using — a "
                    + "pure 'distinct in use' list could not offer a category to the first layer that "
                    + "would belong to it, and a pure fixed list is the hard-coded vocabulary this "
                    + "module exists to avoid.")
    public List<String> categories() {
        return layerService.categories(SecurityUtils.requirePrincipal().organizationId());
    }

    @GetMapping("/crs")
    @PreAuthorize("hasAuthority('" + Permissions.LAYER_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Coordinate reference systems this database supports",
            description = """
                    Read from PostGIS's own `spatial_ref_sys`, not from a hard-coded pair of EPSG
                    codes. The projections a deployment can honour are already written down there —
                    including any local grid a state utility has added — and a second list in Java
                    could only ever be a subset that goes stale.

                    EPSG:4326 and EPSG:3857 sort first whatever the search says: one is the storage
                    CRS and the other the tile CRS, and a list that buries them under nine thousand
                    alphabetical alternatives is a list that gets scrolled past.""")
    public List<LayerDtos.CrsResponse> crs(@RequestParam(required = false) String search,
                                           @RequestParam(defaultValue = "50") int limit) {
        return layerService.availableCrs(search, limit).stream()
                .map(LayerDtos.CrsResponse::from).toList();
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private LayerDtos.LayerResponse changeStatus(UUID layerId, LayerStatus target,
                                                 LayerDtos.StateChangeRequest request) {
        AuthenticatedPrincipal principal = SecurityUtils.requirePrincipal();
        layerService.changeStatus(principal.organizationId(), principal.userId(), principal.username(),
                layerId, target, request == null ? null : request.reason());
        return LayerDtos.LayerResponse.from(layerService.get(principal.organizationId(), layerId));
    }

    /**
     * Parses an enum from the request, with a message that lists the alternatives.
     *
     * <p>Bound by hand rather than declared as an enum parameter: Spring's own conversion failure
     * produces a 400 whose body names the Java type and the rejected value, which tells an API
     * consumer nothing about what they should have sent.
     */
    private static GeometryType parseGeometry(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return GeometryType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "'" + raw + "' is not a geometry type. One of: "
                            + Arrays.stream(GeometryType.values()).map(Enum::name)
                            .reduce((a, b) -> a + ", " + b).orElse("") + ".");
        }
    }

    private static AssetType parseAssetType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return AssetType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "'" + raw + "' is not an asset type. Use CUSTOM for a layer the platform's own "
                            + "code knows nothing about, or one of: "
                            + Arrays.stream(AssetType.values()).map(Enum::name)
                            .reduce((a, b) -> a + ", " + b).orElse("") + ".");
        }
    }

    /** {@code PUMP_STATION} → "Pump Station", so the picker reads as English. */
    private static String humanise(String constant) {
        StringBuilder out = new StringBuilder();
        for (String part : constant.split("_")) {
            if (part.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(part.charAt(0)).append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }
}

package com.aquagrid.platform.gis.web.controller;

import com.aquagrid.platform.common.web.ApiPaths;
import com.aquagrid.platform.gis.application.service.GisService;
import com.aquagrid.platform.gis.application.service.LayerStyleService;
import com.aquagrid.platform.gis.domain.model.Layer;
import com.aquagrid.platform.gis.domain.tile.TileCoordinate;
import com.aquagrid.platform.gis.web.dto.StyleDtos;
import com.aquagrid.platform.security.core.Permissions;
import com.aquagrid.platform.security.core.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The GIS API: layer catalogue and vector tiles.
 *
 * <p>The tile endpoint is the hot path of the map. It returns Mapbox Vector Tile PBF bytes with a
 * generous cache lifetime: a tile's contents change only when an asset in that tile changes, and
 * stale tiles are far cheaper than recomputing every pan. The cache is keyed by viewport, so the
 * payload is O(viewport) rather than O(network size) — the property designed out from day one to
 * avoid the 12 MB GeoJSON payload incident.
 */
@Tag(name = "GIS", description = "Spatial layers and vector tiles")
@RestController
@RequestMapping(value = ApiPaths.GIS, produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Slf4j
public class GisController {

    /** Registered separately so {@code Content-Type} negotiation is explicit. */
    public static final MediaType MAPBOX_VECTOR_TILE =
            MediaType.parseMediaType("application/vnd.mapbox-vector-tile");

    private final GisService gisService;
    private final LayerStyleService styleService;

    @GetMapping("/layers")
    @PreAuthorize("hasAuthority('" + Permissions.MAP_VIEW + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "List the map layers configured for this tenant",
            description = """
                    Active layers only. A layer disabled or archived in Layer Management keeps every
                    feature it ever held, but it is not something the map should draw or offer to
                    switch on.

                    The flags come from the registry rather than from the client's assumptions:
                    `queryable` decides whether clicking a feature opens the inspection card,
                    `searchable` whether the search box looks in the layer, and the zoom pair whether
                    it draws at all at the current scale.""")
    public List<LayerResponse> layers() {
        UUID orgId = SecurityUtils.requirePrincipal().organizationId();
        return gisService.listLayers(orgId).stream().map(LayerResponse::from).toList();
    }

    @GetMapping("/map-style")
    @PreAuthorize("hasAuthority('" + Permissions.STYLE_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "The map's rendering instructions, composed from each layer's style",
            description = """
                    Returns, for every active tile-enabled layer, a MapLibre vector source and the
                    render layers to add for it — paint, layout, filters, zoom range — plus the
                    legend those layers imply.

                    This is what replaces the hard-coded style table the client used to keep. The map
                    adds what it is given and decides nothing about appearance, so a layer created at
                    runtime draws correctly on the next load and a recoloured one needs no release.
                    The legend is derived from the same expressions as the paint, so it cannot drift
                    from what is drawn.

                    Gated on `gis:style:read` rather than `gis:map:view`, and the seed grants the two
                    together: a viewer without this would get correct geometry with every layer grey.""")
    public List<StyleDtos.ComposedLayerResponse> mapStyle() {
        UUID orgId = SecurityUtils.requirePrincipal().organizationId();
        return styleService.composeMapStyle(orgId, ApiPaths.GIS + "/tiles/{layer}/{z}/{x}/{y}")
                .stream().map(StyleDtos.ComposedLayerResponse::from).toList();
    }

    @GetMapping("/dashboard/network")
    @PreAuthorize("hasAuthority('" + Permissions.MAP_VIEW + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Network and facility totals for the dashboard",
            description = """
                    Pipe-network length (metres, ellipsoidal) and mapped facility counts for the
                    tenant, with a per-panchayat breakdown. Totals are summed from the same rows the
                    breakdown returns, so a headline figure can never contradict the chart under it.
                    The panchayat comes from the asset's imported attributes; assets whose source
                    file carried none are grouped as 'Unassigned' rather than dropped.""")
    public GisService.NetworkSummary networkSummary() {
        UUID orgId = SecurityUtils.requirePrincipal().organizationId();
        return gisService.networkSummary(orgId);
    }

    @GetMapping("/layers/{code}/extent")
    @PreAuthorize("hasAuthority('" + Permissions.MAP_VIEW + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Bounding box of a layer's assets",
            description = """
                    Returns the layer's extent in EPSG:4326 so the client can frame its opening
                    camera on real data instead of a static fallback centre. 204 when the tenant has
                    no assets in that layer — there is nothing to zoom to, and an empty body says so
                    without the client having to interpret a zero-area box.""")
    @ApiResponse(responseCode = "200", description = "Layer extent")
    @ApiResponse(responseCode = "204", description = "Layer is unknown or holds no assets")
    public ResponseEntity<ExtentResponse> layerExtent(
            @PathVariable @Schema(description = "Layer code, matching a gis.layers row") String code) {

        UUID orgId = SecurityUtils.requirePrincipal().organizationId();
        return gisService.layerExtent(orgId, code)
                .map(box -> ResponseEntity.ok(new ExtentResponse(box[0], box[1], box[2], box[3])))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping(value = "/tiles/{layer}/{z}/{x}/{y}", produces = "application/vnd.mapbox-vector-tile")
    @PreAuthorize("hasAuthority('" + Permissions.MAP_VIEW + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Fetch a Mapbox Vector Tile",
            description = """
                    Returns PBF bytes for the requested tile. The geometry is generated in PostGIS via
                    ST_AsMVT from the tenant's assets, clipped to the tile extent. Tiles with no
                    features still return a valid (but empty-layer) MVT and a 200: MapLibre's worker
                    throws "Unable to parse the tile" on a zero-length body, which strands the source
                    in a loading state, so the service guarantees a parseable response.

                    A layer code the tenant has no row for is a 404. A layer that exists with vector
                    tiles switched off is not — it answers an empty tile, because a map loaded before
                    the flag changed keeps requesting a source it already mounted, and a 404 there
                    would raise an operator-facing error banner once per tile per pan.

                    Optional `filter` parameters restrict which features are encoded, server-side, in
                    the form `field:operator:value` — for example `filter=status:EQ:IN_SERVICE` or
                    `filter=diameter_mm:GT:300`. The field must be one the layer's Data Management
                    catalogue declares (or `status`, `asset_type`, `asset_code`, `name`), the operator
                    one of the Layer Style Management comparisons, and the value is always bound. A
                    filter is applied in the query rather than by MapLibre, so a filtered view
                    genuinely transfers fewer bytes.""")
    @ApiResponse(responseCode = "200", description = "Tile bytes (empty-layer MVT when no features)")
    @ApiResponse(responseCode = "400", description = "z/x/y is not a tile, or a filter is not valid")
    @ApiResponse(responseCode = "404", description = "No layer with that code exists for this tenant")
    public ResponseEntity<byte[]> tile(
            @PathVariable @Schema(description = "Layer code, matching a gis.layers row") String layer,
            @PathVariable int z,
            @PathVariable int x,
            @PathVariable int y,
            @RequestParam(required = false) String assetType,
            @RequestParam(name = "filter", required = false)
            @Schema(description = "Repeatable server-side predicate, `field:operator:value`")
            List<String> filters) {

        UUID orgId = SecurityUtils.requirePrincipal().organizationId();
        // Rejected here rather than by ST_TileEnvelope, which validates by raising — a mistyped
        // address would otherwise arrive at the error handler as a database failure and leave as 500.
        TileCoordinate coordinate = TileCoordinate.of(z, x, y);
        GisService.TileResult tile = gisService.getTile(orgId, assetType, layer, coordinate, filters);

        return ResponseEntity.ok()
                .contentType(MAPBOX_VECTOR_TILE)
                /*
                 * Private, not public. The URL carries no tenant — the tenant comes from the bearer
                 * token — so a shared cache keyed on the URL (a CDN, or an nginx proxy_cache in front
                 * of the API) would serve one utility's asset tile to another's operator. `private`
                 * is what confines the reuse to the browser that authenticated for it.
                 *
                 * The lifetime comes from the layer: short for the editable operational layers whose
                 * features staff move during a shift, long for the reference layers that do not
                 * change. See GisTileProperties.
                 */
                .cacheControl(CacheControl.maxAge(tile.cacheSeconds(), TimeUnit.SECONDS).cachePrivate())
                .body(tile.bytes());
    }

    /**
     * Outbound layer descriptor.
     *
     * <p>Extended by Layer Management (V1332) with the flags the map used to assume. {@code visible}
     * keeps its name — it is visible-by-default, and renaming a field the client already reads to
     * say the same thing in different words is not worth a breaking change.
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "Layer")
    public record LayerResponse(
            UUID id,
            String code,
            String title,
            String description,
            String category,
            String assetType,
            String geometryType,
            boolean visible,
            boolean queryable,
            boolean searchable,
            boolean vectorTileEnabled,
            int minZoom,
            int maxZoom,
            int sortOrder
    ) {
        static LayerResponse from(Layer layer) {
            return new LayerResponse(layer.getId(), layer.getCode(), layer.getTitle(),
                    layer.getDescription(), layer.getCategory(), layer.getAssetType().name(),
                    layer.getGeometryType().name(), layer.isVisible(), layer.isQueryable(),
                    layer.isSearchable(), layer.isVectorTileEnabled(),
                    layer.getMinZoom(), layer.getMaxZoom(), layer.getSortOrder());
        }
    }

    /** Layer bounding box in EPSG:4326 (lon/lat), ready for a MapLibre {@code fitBounds}. */
    @Schema(name = "LayerExtent")
    public record ExtentResponse(double minLon, double minLat, double maxLon, double maxLat) {
    }
}

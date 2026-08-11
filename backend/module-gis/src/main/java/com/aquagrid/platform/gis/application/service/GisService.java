package com.aquagrid.platform.gis.application.service;

import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.common.error.ResourceNotFoundException;
import com.aquagrid.platform.gis.api.AttributeDefinition;
import com.aquagrid.platform.gis.domain.enums.AttributeDataType;
import com.aquagrid.platform.gis.domain.enums.LayerStatus;
import com.aquagrid.platform.gis.domain.model.Asset;
import com.aquagrid.platform.gis.domain.model.Layer;
import com.aquagrid.platform.gis.domain.model.LayerStyle;
import com.aquagrid.platform.gis.domain.model.LayerStyleRule;
import com.aquagrid.platform.gis.domain.tile.TileCoordinate;
import com.aquagrid.platform.gis.domain.tile.TileFilter;
import com.aquagrid.platform.gis.infrastructure.config.GisTileProperties;
import com.aquagrid.platform.gis.infrastructure.persistence.AssetRepository;
import com.aquagrid.platform.gis.infrastructure.persistence.LayerRepository;
import com.aquagrid.platform.gis.infrastructure.persistence.LayerStyleRepository;
import com.aquagrid.platform.gis.infrastructure.persistence.LayerStyleRuleRepository;
import com.aquagrid.platform.gis.infrastructure.persistence.LayerTileRepository;
import com.aquagrid.platform.gis.domain.style.SymbolKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/**
 * GIS read-side orchestration: layers, vector tiles and bbox queries.
 *
 * <p>All methods are tenant-scoped: the {@code organizationId} is supplied by the controller from
 * the authenticated principal, never trusted from the request. This is the second line of defence
 * behind the Hibernate tenant filter; spatial data is sensitive (it shows where infrastructure is),
 * so it is checked explicitly here rather than relying on the filter alone.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GisService {

    private final AssetRepository assetRepository;
    private final LayerRepository layerRepository;
    private final LayerTileRepository tileRepository;
    private final LayerStyleRepository styleRepository;
    private final LayerStyleRuleRepository styleRuleRepository;
    private final LayerMetadataService metadataService;
    private final LayerRenderCache renderCache;
    private final GisTileProperties tileProperties;

    /**
     * The layers the map should offer.
     *
     * <p>Active layers only, since Layer Management (V1332) gave a layer a lifecycle. An inactive or
     * archived layer keeps every feature it ever held — withdrawal never deletes — but it is not
     * something the map should draw or offer to switch on, which is the entire meaning of the
     * status. Before the registry, every row in {@code gis.layers} was implicitly active and this
     * returned all of them.
     */
    @Transactional(readOnly = true)
    public List<Layer> listLayers(UUID organizationId) {
        return layerRepository.findByOrganizationIdAndStatusOrderBySortOrderAsc(
                organizationId, LayerStatus.ACTIVE);
    }

    /**
     * Builds a Mapbox Vector Tile for a layer.
     *
     * <p>Scoped by {@code layer_id} rather than by asset type since V1332, with an asset-type
     * fallback for features written before the registry existed. That is what lets two layers over
     * one asset type — a utility splitting domestic and bulk meters — draw different features
     * instead of both drawing all of them.
     *
     * <p>The tile also carries whatever catalogue attributes the layer's style reads. Attribute-based
     * styling is a MapLibre expression evaluated in the tile worker against the tile's own
     * properties, so a style that colours by {@code water_level} works only if {@code water_level} is
     * <em>in</em> the tile; the old fixed five-column projection could serve no classified style at
     * all. Which fields those are is resolved once per tenant and cached — see
     * {@link LayerRenderCache} — because the map issues dozens of tile requests per pan and the
     * answer changes when an administrator edits a style, perhaps weekly.
     *
     * @throws ResourceNotFoundException when the tenant has no layer with that code. A layer that
     *         exists but has vector tiles switched off is not this case — see the body.
     * @return the PBF bytes, never null and never zero-length. A tile with no features is returned
     *         as a valid empty-layer MVT (see {@link #emptyMvt(String)}) rather than an empty body:
     *         MapLibre's worker parses the response arrayBuffer as a Protobuf {@code VectorTile}
     *         unconditionally, a zero-length buffer makes it throw "Unable to parse the tile", and
     *         that strands the vector source in a loading state so {@code map.isStyleLoaded()}
     *         never becomes true. Returning a parseable empty-layer tile lets the client cache
     *         "nothing here" and complete cleanly.
     */
    @Transactional(readOnly = true)
    public TileResult getTile(UUID organizationId, String assetType, String layerName,
                              TileCoordinate coordinate, List<String> rawFilters) {
        LayerRenderCache.RenderPlan plan = renderPlan(organizationId, layerName);
        /*
         * A layer code this tenant has no row for is a 404. Nothing legitimate asks for one: the map
         * builds its sources from the composed style, so every code it requests came from the
         * registry a moment earlier. A request for a code that is not there is a stale bookmark, a
         * hand-written URL or a client bug, and answering it with an empty tile makes all three look
         * like a layer that exists and happens to be empty — which is the version that costs an
         * afternoon to diagnose.
         */
        if (plan == null) {
            throw new ResourceNotFoundException("Layer", layerName);
        }
        /*
         * A layer that exists with vector tiles switched off is different, and answers with a valid
         * empty tile. It is a reachable state rather than a mistake: the flag is composed out of the
         * style, so a map loaded before an administrator switched it off keeps requesting tiles for a
         * source it already mounted, and it will until the operator reloads. MapLibre surfaces a tile
         * 404 through the map's `error` event, which this console turns into an operator-facing
         * banner — one per tile per pan for a change the operator did not make and cannot see. An
         * empty tile is cached as "nothing here" and the layer simply stops drawing, which is what
         * switching its tiles off was asking for.
         */
        if (!plan.tileEnabled()) {
            return new TileResult(emptyMvt(layerName), tileProperties.cacheSecondsFor(plan.editable()));
        }
        /*
         * Filters are resolved against the layer's own catalogue — the whitelist — before any of
         * this reaches SQL, and an unresolvable one is refused rather than dropped. See TileFilter.
         */
        List<TileFilter> filters = TileFilter.parseAll(rawFilters, plan.filterableFields());
        /*
         * The client may still override the type filter. Kept for the register's own callers, which
         * ask for one asset type across whatever layers hold it; the map never passes it.
         */
        String type = assetType != null ? assetType : plan.assetType();

        byte[] tile;
        try {
            tile = tileRepository.buildTile(organizationId, plan.layerId(), type, layerName,
                    coordinate, plan.styledFields(), filters);
        } catch (DataAccessException failure) {
            /*
             * The database's own message names the statement, the schema and often the offending
             * value, so it is logged and not propagated: the correlation id is already in the MDC
             * (CorrelationIdFilter), the client gets the same id back in X-Request-Id, and the two
             * are what joins an operator's report to the cause without the response body carrying
             * anything about the schema.
             */
            log.error("Tile generation failed for layer '{}' at {} (org {}, {} filter(s))",
                    layerName, coordinate, organizationId, filters.size(), failure);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "The tile could not be generated.");
        }
        return new TileResult(
                (tile == null || tile.length == 0) ? emptyMvt(layerName) : tile,
                tileProperties.cacheSecondsFor(plan.editable()));
    }

    /**
     * Unfiltered tile bytes for a layer.
     *
     * <p>The convenience form for callers that want geometry and nothing else — the register's own
     * queries and the tests. The controller uses the full form, because the cache lifetime is part
     * of the answer and it must not re-read the layer row to work it out.
     */
    @Transactional(readOnly = true)
    public byte[] getTile(UUID organizationId, String assetType, String layerName,
                          int z, int x, int y) {
        return getTile(organizationId, assetType, layerName,
                TileCoordinate.of(z, x, y), List.of()).bytes();
    }

    /**
     * A generated tile and how long it may be reused.
     *
     * <p>The lifetime travels with the bytes because it is a property of the layer they came from,
     * and the alternative — the controller re-reading the layer to decide a header — is a second
     * query on the hot path for something this method already had in hand.
     */
    public record TileResult(byte[] bytes, long cacheSeconds) {
    }

    /**
     * Everything the tile query needs about a layer, resolved once and cached until a writer evicts
     * it.
     *
     * <p>An unknown layer code caches nothing and returns null. Caching the absence would mean a
     * layer created a moment later stayed invisible until the next eviction, and the lookup it saves
     * only happens for a code no client has any reason to request twice.
     */
    private LayerRenderCache.RenderPlan renderPlan(UUID organizationId, String layerCode) {
        if (layerCode == null) {
            return null;
        }
        LayerRenderCache.RenderPlan cached = renderCache.get(organizationId, layerCode);
        if (cached != null) {
            return cached;
        }
        Optional<Layer> found = layerRepository.findByOrganizationIdAndCode(organizationId, layerCode);
        if (found.isEmpty()) {
            return null;
        }
        Layer layer = found.get();
        Map<String, AttributeDataType> catalogue = catalogueFieldTypes(organizationId, layer);
        LayerRenderCache.RenderPlan plan = new LayerRenderCache.RenderPlan(
                layer.getId(), layer.getAssetType().name(), layer.getCode(),
                layer.isVectorTileEnabled() && layer.isUsable(),
                layer.isEditable(),
                LayerTileRepository.tileable(styledFieldTypes(organizationId, layer, catalogue)),
                catalogue);
        renderCache.put(organizationId, layerCode, plan);
        return plan;
    }

    /**
     * Every active attribute on the layer, with its declared type.
     *
     * <p>Read once per plan and used twice: to type the fields the style reads, and as the whitelist
     * a request's filter is resolved against. One read rather than two because both answers come
     * from the same catalogue and reading it twice is how they end up disagreeing about whether a
     * field an administrator has just deactivated still exists.
     */
    private Map<String, AttributeDataType> catalogueFieldTypes(UUID organizationId, Layer layer) {
        Map<String, AttributeDataType> types = new LinkedHashMap<>();
        for (AttributeDefinition definition : metadataService.definitionsForLayer(organizationId, layer.getId())) {
            types.put(definition.fieldName(), definition.dataType());
        }
        return types;
    }

    /**
     * The catalogue attributes the layer's default style reads, with their declared types.
     *
     * <p>Only the fields the style actually names — the classification field, the label field and
     * every rule's field — not the whole catalogue. A tile's properties are bytes on the wire for
     * every feature at every zoom, so carrying an attribute nobody styles by is a cost paid on every
     * request for a value nothing reads.
     *
     * <p>The declared type comes with the name because the tile casts numeric attributes: a MapLibre
     * comparison against {@code attributes->>'diameter'} compares a number to a string, which is not
     * an error, just a rule that is never true.
     */
    private Map<String, AttributeDataType> styledFieldTypes(UUID organizationId, Layer layer,
                                                            Map<String, AttributeDataType> catalogue) {
        Optional<LayerStyle> style = styleRepository
                .findByLayerIdAndDefaultStyleTrueAndActiveTrue(layer.getId());
        if (style.isEmpty()) {
            return Map.of();
        }
        Set<String> names = new LinkedHashSet<>();
        if (style.get().getClassifyField() != null) {
            names.add(style.get().getClassifyField());
        }
        Object labelField = style.get().getLabel() == null ? null
                : style.get().getLabel().get(SymbolKeys.LABEL_FIELD);
        if (labelField != null && !labelField.toString().isBlank()) {
            names.add(labelField.toString());
        }
        styleRuleRepository.findActiveForStyles(List.of(style.get().getId()))
                .forEach((LayerStyleRule rule) -> names.add(rule.getFieldName()));
        if (names.isEmpty()) {
            return Map.of();
        }

        Map<String, AttributeDataType> types = new LinkedHashMap<>();
        catalogue.forEach((fieldName, dataType) -> {
            if (names.contains(fieldName)) {
                types.put(fieldName, dataType);
            }
        });
        return types;
    }

    /**
     * Bounding box of a layer's assets as {@code [minLon, minLat, maxLon, maxLat]} in EPSG:4326,
     * or empty when the layer is unknown to this tenant or holds no assets.
     *
     * <p>The map calls this once, for the pipe network, to frame its opening view; Layer Management's
     * preview calls it per layer. Scoped by {@code layer_id} with the same asset-type fallback the
     * tile query uses, so a tenant that splits an asset type across two layers gets each layer's own
     * extent rather than the union of both.
     */
    @Transactional(readOnly = true)
    public Optional<double[]> layerExtent(UUID organizationId, String layerCode) {
        return layerRepository.findByOrganizationIdAndCode(organizationId, layerCode)
                .flatMap(layer -> assetRepository
                        .findExtentForLayer(organizationId, layer.getId(), layer.getAssetType().name())
                        .stream().findFirst()
                        .map(row -> new double[]{
                                ((Number) row[0]).doubleValue(),
                                ((Number) row[1]).doubleValue(),
                                ((Number) row[2]).doubleValue(),
                                ((Number) row[3]).doubleValue(),
                        }));
    }

    /**
     * Minimal valid Mapbox Vector Tile containing a single empty layer with the given name.
     *
     * <p>Hand-rolled Protobuf rather than a dependency: the encoding is tiny and fixed. Layout is
     * {@code Tile{ layers: [ Layer{ version:2, name:<layerName>, extent:4096 } ] }} with zero
     * features, which every MVT consumer (MapLibre, Mapbox, deck.gl) parses as "this layer exists
     * but has nothing in this tile".
     */
    private static byte[] emptyMvt(String layerName) {
        byte[] name = (layerName == null ? "assets" : layerName).getBytes(StandardCharsets.UTF_8);
        // Layer message: version=2 (field 15, varint), name (field 1, length-delimited), extent=4096 (field 5, varint)
        ByteArrayBuilder layer = new ByteArrayBuilder();
        layer.writeVarint((15 << 3) | 0); layer.writeVarint(2);
        layer.writeVarint((1 << 3) | 2); layer.writeVarint(name.length); layer.writeBytes(name);
        layer.writeVarint((5 << 3) | 0); layer.writeVarint(4096);
        byte[] layerBytes = layer.toByteArray();
        // Tile message: layers (field 3, length-delimited message)
        ByteArrayBuilder tile = new ByteArrayBuilder();
        tile.writeVarint((3 << 3) | 2); tile.writeVarint(layerBytes.length); tile.writeBytes(layerBytes);
        return tile.toByteArray();
    }

    /**
     * Minimal growable byte buffer with varint support, local to this helper.
     *
     * <p>{@link ByteArrayOutputStream} already provides the no-throw {@code write(int)} and
     * {@code writeBytes(byte[])} (added in Java 11) this builder relies on; only varint encoding
     * is added on top.
     */
    private static final class ByteArrayBuilder extends ByteArrayOutputStream {
        void writeVarint(int value) {
            while (true) {
                if ((value & ~0x7F) == 0) { write(value); return; }
                write((value & 0x7F) | 0x80); value >>>= 7;
            }
        }
    }

    /**
     * Network and facility totals for the dashboard, broken down by panchayat.
     *
     * <p>The totals are summed from the per-panchayat rows rather than queried separately, so the
     * headline figure and the chart beneath it can never disagree — the failure mode where a
     * dashboard's total and its own breakdown tell different stories.
     */
    @Transactional(readOnly = true)
    public NetworkSummary networkSummary(UUID organizationId) {
        List<PanchayatSummary> rows = assetRepository.findNetworkSummaryByPanchayat(organizationId)
                .stream()
                .map(row -> new PanchayatSummary(
                        (String) row[0],
                        asLong(row[1]),
                        asDouble(row[2]),
                        asLong(row[3]),
                        asLong(row[4]),
                        asLong(row[5])))
                .toList();

        return new NetworkSummary(
                rows.stream().mapToDouble(PanchayatSummary::pipelineLengthM).sum(),
                rows.stream().mapToLong(PanchayatSummary::pipelineCount).sum(),
                rows.stream().mapToLong(PanchayatSummary::tanks).sum(),
                rows.stream().mapToLong(PanchayatSummary::openWells).sum(),
                rows.stream().mapToLong(PanchayatSummary::boreWells).sum(),
                rows);
    }

    /*
     * Native queries hand back whatever numeric type the driver picked for the column — Long for
     * count(), Double or BigDecimal for the sum depending on how Postgres typed the COALESCE. Going
     * through Number rather than casting means a driver or query change cannot turn into a
     * ClassCastException at runtime.
     */
    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0d;
    }

    /** Dashboard totals plus the per-panchayat rows they were summed from. */
    public record NetworkSummary(
            double totalPipelineLengthM,
            long pipelineCount,
            long tanks,
            long openWells,
            long boreWells,
            List<PanchayatSummary> panchayats
    ) {
    }

    /** One panchayat's share of the network and its mapped facilities. */
    public record PanchayatSummary(
            String panchayat,
            long pipelineCount,
            double pipelineLengthM,
            long tanks,
            long openWells,
            long boreWells
    ) {
    }

    @Transactional(readOnly = true)
    public List<Asset> listInBbox(UUID organizationId, String assetType,
                                  double minX, double minY, double maxX, double maxY) {
        return assetRepository.findInBbox(organizationId, assetType, minX, minY, maxX, maxY);
    }
}

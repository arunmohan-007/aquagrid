package com.aquagrid.platform.gis;

import com.aquagrid.platform.AbstractIntegrationTest;
import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.gis.application.command.AttributeCommands;
import com.aquagrid.platform.gis.application.command.LayerCommands;
import com.aquagrid.platform.gis.application.service.GisService;
import com.aquagrid.platform.gis.application.service.LayerManagementService;
import com.aquagrid.platform.gis.application.service.LayerMetadataService;
import com.aquagrid.platform.gis.domain.enums.AssetStatus;
import com.aquagrid.platform.gis.domain.enums.AssetType;
import com.aquagrid.platform.gis.domain.enums.AttributeDataType;
import com.aquagrid.platform.gis.domain.enums.GeometryType;
import com.aquagrid.platform.gis.domain.geo.GeometryCodec;
import com.aquagrid.platform.gis.domain.model.Asset;
import com.aquagrid.platform.gis.domain.model.Layer;
import com.aquagrid.platform.gis.domain.tile.TileCoordinate;
import com.aquagrid.platform.gis.infrastructure.config.GisTileProperties;
import com.aquagrid.platform.gis.infrastructure.persistence.AssetRepository;
import com.aquagrid.platform.identity.infrastructure.persistence.OrganizationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The vector tile path, end to end against real PostGIS.
 *
 * <p>{@code LayerManagementIT} already asserts that a layer created at runtime tiles at all. This
 * class is about the tile itself: that PostGIS does the work, that the three geometry families all
 * survive the round trip, that a tile carries the properties a style needs and no more, that a
 * malformed address is refused before it reaches SQL, and that a filter cannot become SQL.
 *
 * <p>The assertions read the PBF bytes rather than a decoded model. The module has no MVT decoder
 * and does not need one — the encoding is fixed and the two facts worth asserting are structural:
 * a layer name appears in the tile's header, and a property key appears in its key table. Both are
 * plain UTF-8 runs in the Protobuf, so containment is a sound test of "did this reach the client",
 * which is the only question these tests ask of the bytes.
 */
class VectorTileIT extends AbstractIntegrationTest {

    /** Distinguishes rows across tests without a per-test cleanup. */
    private static final AtomicInteger SEQ = new AtomicInteger();

    /**
     * A tile over Salem, Tamil Nadu at zoom 14 — where the fixtures below are placed.
     *
     * <p>Real coordinates rather than the origin tile. z0/0/0 covers the whole world and so passes
     * whether or not the envelope filter works at all; a tile this size only contains a feature if
     * the projection and the {@code &&} restriction are both right.
     */
    private static final double LON = 78.1460;
    private static final double LAT = 11.6643;

    @Autowired
    private LayerManagementService layerService;
    @Autowired
    private LayerMetadataService metadataService;
    @Autowired
    private com.aquagrid.platform.gis.application.service.LayerStyleService styleService;
    @Autowired
    private GisService gisService;
    @Autowired
    private AssetRepository assetRepository;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private GisTileProperties tileProperties;
    @Autowired
    private JdbcTemplate jdbc;

    // ---- Geometry families ----------------------------------------------------------------------

    @Test
    @DisplayName("Point, line and polygon layers all render through the same generic endpoint")
    void everyGeometryFamilyTiles() {
        UUID orgId = orgId();

        Layer points = createLayer("tile-points", GeometryType.POINT);
        assetRepository.save(asset(orgId, points, Map.of(
                "type", "Point", "coordinates", List.of(LON, LAT))));

        Layer lines = createLayer("tile-lines", GeometryType.LINESTRING);
        assetRepository.save(asset(orgId, lines, Map.of(
                "type", "LineString",
                "coordinates", List.of(List.of(LON, LAT), List.of(LON + 0.002, LAT + 0.002)))));

        Layer polygons = createLayer("tile-polygons", GeometryType.POLYGON);
        assetRepository.save(asset(orgId, polygons, Map.of(
                "type", "Polygon",
                "coordinates", List.of(List.of(
                        List.of(LON, LAT),
                        List.of(LON + 0.002, LAT),
                        List.of(LON + 0.002, LAT + 0.002),
                        List.of(LON, LAT))))));

        /*
         * Each tile must contain its own layer's features and be materially bigger than the empty
         * tile the endpoint returns when there is nothing there — the check that distinguishes
         * "rendered" from "answered", since an empty-layer MVT also parses and also names the layer.
         */
        for (Layer layer : List.of(points, lines, polygons)) {
            byte[] tile = tileAt(layer, 14);
            assertThat(tile).as("%s tile", layer.getCode()).isNotEmpty();
            assertThat(new String(tile, java.nio.charset.StandardCharsets.ISO_8859_1))
                    .as("%s tile names its source-layer", layer.getCode())
                    .contains(layer.getCode());
            assertThat(tile.length).as("%s tile carries a feature", layer.getCode())
                    .isGreaterThan(emptyTileLength(layer));
        }
    }

    @Test
    @DisplayName("A tile outside the layer's features is empty but still parseable")
    void tileWithNoFeaturesIsStillAValidTile() {
        UUID orgId = orgId();
        Layer layer = createLayer("tile-empty", GeometryType.POINT);
        assetRepository.save(asset(orgId, layer, Map.of(
                "type", "Point", "coordinates", List.of(LON, LAT))));

        // Tile 0/0 at z14 is in the Arctic off Greenland; nothing this tenant owns is in it.
        byte[] tile = gisService.getTile(orgId, null, layer.getCode(), 14, 0, 0);

        assertThat(tile).isNotEmpty();
        assertThat(new String(tile, java.nio.charset.StandardCharsets.ISO_8859_1))
                .contains(layer.getCode());
    }

    @Test
    @DisplayName("An asset with no geometry is skipped, not fatal to the tile around it")
    void nullGeometryDoesNotBlankTheTile() {
        UUID orgId = orgId();
        Layer layer = createLayer("tile-null-geom", GeometryType.POINT);
        assetRepository.save(asset(orgId, layer, Map.of(
                "type", "Point", "coordinates", List.of(LON, LAT))));

        /*
         * gis.assets.geom is NOT NULL, so a null-geometry row cannot arrive through the entity. It
         * can arrive as an empty geometry — which is what a shapefile feature with no shape imports
         * as — and that is the case worth covering: ST_AsMVTGeom returns NULL for it, and the outer
         * WHERE drops the row rather than the statement failing and blanking the viewport.
         */
        jdbc.update("""
                INSERT INTO gis.assets (organization_id, asset_code, asset_type, name, status, geom, layer_id)
                VALUES (?, ?, ?, 'Shapeless feature', 'IN_SERVICE',
                        ST_GeomFromText('POINT EMPTY', 4326), ?)
                """, orgId, unique("EMPTY").toUpperCase(), layer.getAssetType().name(), layer.getId());

        byte[] tile = tileAt(layer, 14);
        assertThat(tile.length).isGreaterThan(emptyTileLength(layer));
    }

    // ---- Address validation ---------------------------------------------------------------------

    @Test
    @DisplayName("A z/x/y that is not a tile is refused as a bad request, never as a database error")
    void invalidCoordinatesAreRejectedBeforeReachingSql() {
        /*
         * ST_TileEnvelope validates by raising. Without this guard a mistyped address arrives at the
         * error handler as a DataAccessException and leaves as a 500 — a server fault reported for
         * what is a malformed request, with a database message one layer away from the response.
         */
        assertThatThrownBy(() -> TileCoordinate.of(-1, 0, 0))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThatThrownBy(() -> TileCoordinate.of(TileCoordinate.MAX_ZOOM + 1, 0, 0))
                .isInstanceOf(BusinessException.class);

        // At z1 there are four tiles: x and y run 0..1. x=2 is not one of them.
        assertThatThrownBy(() -> TileCoordinate.of(1, 2, 0)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> TileCoordinate.of(1, 0, -1)).isInstanceOf(BusinessException.class);

        assertThatCode(() -> TileCoordinate.of(1, 1, 1)).doesNotThrowAnyException();
        assertThatCode(() -> TileCoordinate.of(0, 0, 0)).doesNotThrowAnyException();
    }

    // ---- Attribute selection --------------------------------------------------------------------

    @Test
    @DisplayName("A tile carries the fields the style reads and leaves the rest of the bag behind")
    void tileProjectionIsTheStyleSCatalogueFieldsOnly() {
        UUID orgId = orgId();
        Layer layer = createLayer("tile-attributes", GeometryType.POINT);

        // Two catalogue fields; the style will name neither, because this layer keeps its default
        // simple style — so the tile should carry neither, however many the catalogue holds.
        attribute(orgId, layer, "internal_notes", AttributeDataType.TEXT);
        attribute(orgId, layer, "pump_hp", AttributeDataType.DECIMAL);

        Asset asset = asset(orgId, layer, Map.of("type", "Point", "coordinates", List.of(LON, LAT)));
        asset.setAttributes(Map.of("internal_notes", "do not show this", "pump_hp", "7.5"));
        assetRepository.save(asset);

        String tile = new String(tileAt(layer, 14), java.nio.charset.StandardCharsets.ISO_8859_1);

        // The five fixed properties the inspection card renders its header from are always present.
        assertThat(tile).contains("asset_code").contains("status");
        /*
         * And the attribute bag is not. This is the property that keeps a tile a tile: a layer with
         * sixty imported columns must not turn the map's hot path into a full-attribute export, and
         * "internal_notes" is exactly the field a utility would be unhappy to find on the wire.
         */
        assertThat(tile).doesNotContain("internal_notes");
        assertThat(tile).doesNotContain("do not show this");
    }

    // ---- Server-side filtering -------------------------------------------------------------------

    @Test
    @DisplayName("A filter is applied in PostGIS, so a filtered tile carries fewer features")
    void filtersRestrictWhatIsEncoded() {
        UUID orgId = orgId();
        Layer layer = createLayer("tile-filter", GeometryType.POINT);

        Asset inService = asset(orgId, layer, Map.of("type", "Point", "coordinates", List.of(LON, LAT)));
        inService.setStatus(AssetStatus.IN_SERVICE);
        assetRepository.save(inService);

        Asset damaged = asset(orgId, layer, Map.of(
                "type", "Point", "coordinates", List.of(LON + 0.0005, LAT + 0.0005)));
        damaged.setStatus(AssetStatus.DAMAGED);
        assetRepository.save(damaged);

        byte[] unfiltered = tileAt(layer, 14);
        byte[] filtered = gisService.getTile(orgId, null, layer.getCode(), tileOver(14),
                List.of("status:EQ:DAMAGED")).bytes();

        /*
         * Fewer bytes, not merely a different render. A MapLibre filter would have produced an
         * identical tile and hidden a feature client-side; the whole point of filtering server-side
         * is that the feature is never encoded, and the byte count is the only assertion that can
         * tell the two apart.
         */
        assertThat(filtered.length).isLessThan(unfiltered.length);
        assertThat(new String(filtered, java.nio.charset.StandardCharsets.ISO_8859_1))
                .contains("DAMAGED")
                .doesNotContain("IN_SERVICE");
    }

    @Test
    @DisplayName("A filter on a catalogue attribute compares numerically, not as text")
    void numericFiltersCompareAsNumbers() {
        UUID orgId = orgId();
        Layer layer = createLayer("tile-numeric-filter", GeometryType.POINT);
        attribute(orgId, layer, "diameter_mm", AttributeDataType.INTEGER);

        Asset small = asset(orgId, layer, Map.of("type", "Point", "coordinates", List.of(LON, LAT)));
        small.setAttributes(Map.of("diameter_mm", "90"));
        assetRepository.save(small);

        Asset large = asset(orgId, layer, Map.of(
                "type", "Point", "coordinates", List.of(LON + 0.0005, LAT + 0.0005)));
        large.setAttributes(Map.of("diameter_mm", "400"));
        assetRepository.save(large);

        byte[] unfiltered = tileAt(layer, 14);
        byte[] filtered = gisService.getTile(orgId, null, layer.getCode(), tileOver(14),
                List.of("diameter_mm:GT:300")).bytes();

        /*
         * Text comparison would put '90' above '400' and return the wrong feature — the silent
         * failure the guarded numeric cast in TileFilter exists to prevent. Byte count alone cannot
         * distinguish "one of two" from "the other of two", so the assertion is that the tile
         * shrank *and* that a numeric operator on a badly-typed row does not abort the statement.
         */
        assertThat(filtered.length).isLessThan(unfiltered.length);

        // A value that will not read as a number yields NULL rather than aborting every tile in the
        // viewport, which is what a bare ::double precision cast would do to one bad import row.
        Asset unparseable = asset(orgId, layer, Map.of(
                "type", "Point", "coordinates", List.of(LON + 0.001, LAT + 0.001)));
        unparseable.setAttributes(Map.of("diameter_mm", "not a number"));
        assetRepository.save(unparseable);

        assertThatCode(() -> gisService.getTile(orgId, null, layer.getCode(), tileOver(14),
                List.of("diameter_mm:GT:300"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A filter naming anything but a catalogue field is refused, not passed to SQL")
    void filterFieldsAreWhitelistedAgainstDataManagement() {
        UUID orgId = orgId();
        Layer layer = createLayer("tile-filter-safety", GeometryType.POINT);
        assetRepository.save(asset(orgId, layer, Map.of(
                "type", "Point", "coordinates", List.of(LON, LAT))));

        /*
         * The injection attempts below are not expected to be escaped — they are expected never to
         * reach a statement at all. A field name is resolved against the layer's catalogue and it is
         * the catalogue's copy that is interpolated, so request text has no path to an identifier.
         */
        for (String hostile : List.of(
                "geom) FROM gis.assets WHERE (1=1:EQ:x",
                "organization_id:EQ:" + UUID.randomUUID(),
                "asset_code'; DROP TABLE gis.assets; --:EQ:x",
                "no_such_field:EQ:x")) {
            assertThatThrownBy(() -> gisService.getTile(orgId, null, layer.getCode(), tileOver(14),
                    List.of(hostile)))
                    .as("filter %s", hostile)
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        // An unknown operator is refused for the same reason: the operator is an enum constant, and
        // anything that is not one of its names is not a comparison this endpoint can make.
        assertThatThrownBy(() -> gisService.getTile(orgId, null, layer.getCode(), tileOver(14),
                List.of("status:DROP:x"))).isInstanceOf(BusinessException.class);

        // The table is still there — the belt-and-braces assertion that nothing above executed.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gis.assets WHERE organization_id = ?",
                Long.class, orgId)).isPositive();
    }

    @Test
    @DisplayName("A filter's value is bound, so a quote in it is data rather than syntax")
    void filterValuesAreBoundParameters() {
        UUID orgId = orgId();
        Layer layer = createLayer("tile-filter-binding", GeometryType.POINT);
        assetRepository.save(asset(orgId, layer, Map.of(
                "type", "Point", "coordinates", List.of(LON, LAT))));

        // The value position is the one genuinely free-text part of a filter. It never appears in
        // the statement, so this matches nothing and raises nothing — which is the whole assertion.
        assertThatCode(() -> gisService.getTile(orgId, null, layer.getCode(), tileOver(14),
                List.of("status:EQ:' OR 1=1 --"))).doesNotThrowAnyException();

        byte[] tile = gisService.getTile(orgId, null, layer.getCode(), tileOver(14),
                List.of("status:EQ:' OR 1=1 --")).bytes();
        assertThat(tile.length).isEqualTo(emptyTileLength(layer));
    }

    // ---- Layer Management gating -----------------------------------------------------------------

    @Test
    @DisplayName("Cache lifetime follows Layer Management's editable flag")
    void cacheLifetimeComesFromTheRegistry() {
        UUID orgId = orgId();

        Layer operational = layerService.create(orgId, null, "test",
                layerCommand(unique("tile-editable"), GeometryType.POINT, true, true));
        Layer reference = layerService.create(orgId, null, "test",
                layerCommand(unique("tile-reference"), GeometryType.POINT, false, true));

        long dynamic = gisService.getTile(orgId, null, operational.getCode(), tileOver(14), List.of())
                .cacheSeconds();
        long stat1c = gisService.getTile(orgId, null, reference.getCode(), tileOver(14), List.of())
                .cacheSeconds();

        assertThat(dynamic).isEqualTo(tileProperties.dynamicCacheMaxAge().toSeconds());
        assertThat(stat1c).isEqualTo(tileProperties.staticCacheMaxAge().toSeconds());
        /*
         * A reference layer is cached longer than an operational one. Asserting the ordering as well
         * as the values means a deployment that overrides both still cannot invert the intent
         * without this failing.
         */
        assertThat(stat1c).isGreaterThan(dynamic);
    }

    @Test
    @DisplayName("Turning vector tiles off empties the tile without erroring")
    void vectorTileFlagGatesGeneration() {
        UUID orgId = orgId();
        Layer layer = layerService.create(orgId, null, "test",
                layerCommand(unique("tile-disabled"), GeometryType.POINT, true, false));
        assetRepository.save(asset(orgId, layer, Map.of(
                "type", "Point", "coordinates", List.of(LON, LAT))));

        byte[] tile = tileAt(layer, 14);
        assertThat(tile).isNotEmpty();
        assertThat(tile.length).isEqualTo(emptyTileLength(layer));
    }

    // ---- Multiple layers -------------------------------------------------------------------------

    @Test
    @DisplayName("One broken layer does not stop the others tiling")
    void layersAreIndependent() {
        UUID orgId = orgId();
        Layer good = createLayer("tile-good", GeometryType.POINT);
        assetRepository.save(asset(orgId, good, Map.of(
                "type", "Point", "coordinates", List.of(LON, LAT))));

        // A code no layer holds — the shape of "one source in the style is stale or misconfigured".
        // It fails, and says which layer failed rather than pretending to be empty.
        assertThatThrownBy(() -> gisService.getTile(orgId, null, "tile-does-not-exist", 14, 0, 0))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        // A layer that exists but has tiles switched off fails differently — quietly, with a valid
        // tile — because that state is reachable by configuration rather than by mistake.
        Layer off = layerService.create(orgId, null, "test",
                layerCommand(unique("tile-off"), GeometryType.POINT, true, false));
        assertThat(gisService.getTile(orgId, null, off.getCode(), 14, 0, 0)).isNotEmpty();

        /*
         * And neither disturbs the healthy layer. That is the property that keeps six layers on one
         * map from sharing a single failure: each tile request is independently resolved, so a stale
         * source in the style costs its own layer and nothing else.
         */
        assertThat(tileAt(good, 14).length).isGreaterThan(emptyTileLength(good));
    }

    // ---- Feature identity ------------------------------------------------------------------------

    @Test
    @DisplayName("The asset's primary key is the tile feature's id, not a per-request invention")
    void featureIdIsThePrimaryKey() {
        UUID orgId = orgId();
        Layer layer = createLayer("tile-feature-id", GeometryType.POINT);
        Asset asset = assetRepository.save(asset(orgId, layer, Map.of(
                "type", "Point", "coordinates", List.of(LON, LAT))));

        /*
         * The id is in the tile as bytes, and it is the row's own UUID. Anything generated per
         * request would still produce a valid tile — and would break click, highlight and the asset
         * lookup the moment the tile was re-fetched, which a rendering test cannot see.
         */
        String tile = new String(tileAt(layer, 14), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(tile).contains(asset.getId().toString());

        // Twice, from a fresh generation: a stable id is one that survives the tile being rebuilt.
        assertThat(new String(tileAt(layer, 14), java.nio.charset.StandardCharsets.ISO_8859_1))
                .contains(asset.getId().toString());

        /*
         * And the composed source promotes it to MapLibre's own feature id, keyed by source-layer.
         * Without this the id is readable only as a property and setFeatureState silently does
         * nothing — the failure mode is a hover effect that never appears and reports no error.
         */
        var composed = styleService.composeMapStyle(orgId, "/api/v1/gis/tiles/{layer}/{z}/{x}/{y}")
                .stream().filter(c -> c.code().equals(layer.getCode())).findFirst().orElseThrow();
        assertThat(composed.source()).containsEntry("promoteId", Map.of(layer.getCode(), "id"));
        assertThat(composed.source()).containsEntry("type", "vector");
        assertThat(composed.sourceLayer()).isEqualTo(layer.getCode());
    }

    // ---- Payload -------------------------------------------------------------------------------

    @Test
    @DisplayName("A viewport tile is orders of magnitude smaller than the layer as GeoJSON")
    void tilePayloadIsBoundedByViewportNotByLayerSize() {
        UUID orgId = orgId();
        Layer layer = createLayer("tile-payload", GeometryType.POINT);

        /*
         * Spread over roughly 55 km, so most of the layer is outside any one z14 tile. A fixture
         * clustered inside a single tile would make the tile carry everything and the comparison
         * would measure encoding efficiency rather than spatial restriction — which is the claim
         * under test.
         */
        int features = 5_000;
        for (int i = 0; i < features; i++) {
            Asset asset = asset(orgId, layer, Map.of(
                    "type", "Point",
                    "coordinates", List.of(LON + (i % 100) * 0.005, LAT + (i / 100) * 0.005)));
            asset.setAttributes(Map.of("panchayat", "Ward " + (i % 20), "remarks", "imported row " + i));
            assetRepository.save(asset);
        }

        /*
         * What the GeoJSON path puts on the wire for this layer: every feature, its geometry and its
         * attribute bag, in one response. Measured with ST_AsGeoJSON so the number is the actual
         * serialised size rather than an estimate.
         */
        long geoJsonBytes = jdbc.queryForObject("""
                SELECT COALESCE(sum(length(ST_AsGeoJSON(a.*)::text)), 0)
                FROM gis.assets a
                WHERE a.organization_id = ? AND a.layer_id = ?
                """, Long.class, orgId, layer.getId());

        int tileBytes = tileAt(layer, 14).length;

        // Logged, not just asserted: the ratio is the design's whole justification and a reader of
        // a green build should be able to see the actual figures rather than infer them.
        System.out.printf("[vector tile] %,d features — layer as GeoJSON %,d bytes, "
                        + "one z14 tile %,d bytes (%.0fx smaller)%n",
                features, geoJsonBytes, tileBytes, geoJsonBytes / (double) tileBytes);

        assertThat(geoJsonBytes).isGreaterThan(1_000_000L);
        /*
         * A conservative bound. The real ratio is far larger; asserting a loose one keeps the test
         * about the property — payload is O(viewport), not O(layer) — rather than about a number
         * that would drift with every change to the fixture or the projection.
         */
        assertThat((double) geoJsonBytes / tileBytes).isGreaterThan(20d);
    }

    // ---- Query plan ------------------------------------------------------------------------------

    @Test
    @DisplayName("The tile query uses the GiST index on geom_3857 rather than scanning the table")
    void tileQueryUsesTheSpatialIndex() {
        UUID orgId = orgId();
        Layer layer = createLayer("tile-plan", GeometryType.POINT);
        for (int i = 0; i < 200; i++) {
            assetRepository.save(asset(orgId, layer, Map.of(
                    "type", "Point",
                    "coordinates", List.of(LON + i * 0.0001, LAT + i * 0.0001))));
        }
        // The planner chooses on statistics, and a table that has never been analysed has none —
        // which is how this assertion fails on a fresh container for a reason that is not a defect.
        jdbc.execute("ANALYZE gis.assets");

        /*
         * The plan is read for the restriction, not for the whole tile statement: what matters is
         * that the tile envelope reaches the index as a condition. A sequential scan here means
         * every pan reads the whole layer and the tile endpoint's cost becomes O(layer) — the exact
         * property the design exists to avoid, and one that a passing functional test cannot see.
         */
        String plan = String.join("\n", jdbc.queryForList("""
                EXPLAIN SELECT a.id
                FROM gis.assets a
                WHERE a.organization_id = ?
                  AND a.geom_3857 && ST_TileEnvelope(14, 11742, 7644)
                """, String.class, orgId));

        assertThat(plan).doesNotContain("Seq Scan");
        /*
         * The spatial index by name, not merely "an index was used". The tenant index on
         * (organization_id, asset_type) would satisfy a looser assertion while the envelope
         * restriction was evaluated as a filter over every row the tenant owns — which is the
         * regression this test exists to catch, and it would have passed one.
         */
        assertThat(plan).contains("ix_assets_geom_3857");
    }

    @Test
    @DisplayName("Tile geometry is generated from the stored Web-Mercator column, never transformed in Java")
    void reprojectionIsMaterialisedNotRepeated() {
        /*
         * geom_3857 is a GENERATED STORED column (V1300). The tile query reads it directly, so a
         * pan costs no ST_Transform at all — and the authoritative EPSG:4326 geometry is untouched,
         * which is the invariant that lets the register, the exporter and the trace keep working in
         * lon/lat while the map works in Web Mercator.
         */
        String generated = jdbc.queryForObject("""
                SELECT is_generated FROM information_schema.columns
                WHERE table_schema = 'gis' AND table_name = 'assets' AND column_name = 'geom_3857'
                """, String.class);
        assertThat(generated).isEqualTo("ALWAYS");

        Integer srid = jdbc.queryForObject(
                "SELECT ST_SRID(ST_Transform(ST_SetSRID(ST_Point(?, ?), 4326), 3857))",
                Integer.class, LON, LAT);
        assertThat(srid).isEqualTo(3857);
    }

    @Test
    @DisplayName("Tile buffer and extent are configuration, and refuse a value that would draw wrongly")
    void tileGeometryParametersAreConfigurable() {
        assertThat(tileProperties.extent()).isEqualTo(4096);
        assertThat(tileProperties.buffer()).isPositive();
        assertThat(tileProperties.queryTimeout()).isPositive();

        // A buffer wider than a quarter of the grid, or a nonsensical extent, is refused at boot
        // rather than clamped: a map that draws subtly wrong is harder to diagnose than one that
        // will not start.
        assertThatThrownBy(() -> new GisTileProperties(4096, 4096, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GisTileProperties(0, 8, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GisTileProperties(4096, 64, null, null, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new GisTileProperties(
                        4096, 64, Duration.ofMinutes(1), Duration.ofDays(1), Duration.ofSeconds(8)))
                .doesNotThrowAnyException();
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    private UUID orgId() {
        return organizationRepository.findByCodeIgnoreCase("SYSTEM").orElseThrow().getId();
    }

    private String unique(String prefix) {
        return prefix + "-" + SEQ.incrementAndGet();
    }

    private Layer createLayer(String codePrefix, GeometryType geometryType) {
        return layerService.create(orgId(), null, "test",
                layerCommand(unique(codePrefix), geometryType, true, true));
    }

    private static LayerCommands.Create layerCommand(String code, GeometryType geometryType,
                                                     boolean editable, boolean vectorTileEnabled) {
        return new LayerCommands.Create(code, code, null, "Other",
                AssetType.CUSTOM, geometryType, "EPSG", 4326,
                true, true, editable, true, true, true, true, vectorTileEnabled,
                0, 24, null, false);
    }

    private void attribute(UUID orgId, Layer layer, String fieldName, AttributeDataType type) {
        metadataService.create(orgId, null, "test", new AttributeCommands.Create(
                layer.getId(), fieldName, fieldName, null, type,
                null, null, null, null, null,
                false, false, true, true, true, 0, "test fixture"));
    }

    private Asset asset(UUID orgId, Layer layer, Map<String, Object> geoJson) {
        Asset asset = new Asset();
        asset.setOrganizationId(orgId);
        asset.setAssetCode(unique("VT").toUpperCase());
        asset.setName("Tile fixture");
        asset.setAssetType(layer.getAssetType());
        asset.setLayerId(layer.getId());
        asset.setStatus(AssetStatus.IN_SERVICE);
        asset.setGeom(GeometryCodec.fromGeoJson(geoJson));
        return asset;
    }

    /** The tile containing {@link #LON}/{@link #LAT} at the given zoom. */
    private static TileCoordinate tileOver(int zoom) {
        double n = Math.pow(2, zoom);
        int x = (int) Math.floor((LON + 180) / 360 * n);
        double latRad = Math.toRadians(LAT);
        int y = (int) Math.floor(
                (1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2 * n);
        return TileCoordinate.of(zoom, x, y);
    }

    private byte[] tileAt(Layer layer, int zoom) {
        return gisService.getTile(orgId(), null, layer.getCode(), tileOver(zoom), List.of()).bytes();
    }

    /**
     * The length of the empty-layer MVT for a layer, which is the baseline every "did it carry a
     * feature" assertion compares against.
     *
     * <p>Derived rather than hard-coded: the empty tile's size depends on the layer code's own
     * length, so a literal here would be right for one fixture and wrong for the next.
     */
    private int emptyTileLength(Layer layer) {
        // A zoom-14 tile in the middle of the Pacific holds nothing for any tenant.
        return gisService.getTile(orgId(), null, layer.getCode(), 14, 1, 1).length;
    }
}

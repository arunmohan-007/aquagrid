package com.aquagrid.platform.gis;

import com.aquagrid.platform.AbstractIntegrationTest;
import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.gis.application.command.AttributeCommands;
import com.aquagrid.platform.gis.application.command.LayerCommands;
import com.aquagrid.platform.gis.application.command.StyleCommands;
import com.aquagrid.platform.gis.application.service.GisService;
import com.aquagrid.platform.gis.application.service.LayerManagementService;
import com.aquagrid.platform.gis.application.service.LayerManagementService.LayerQuery;
import com.aquagrid.platform.gis.application.service.LayerMetadataService;
import com.aquagrid.platform.gis.application.service.LayerStyleService;
import com.aquagrid.platform.gis.domain.enums.AssetStatus;
import com.aquagrid.platform.gis.domain.enums.AssetType;
import com.aquagrid.platform.gis.domain.enums.AttributeDataType;
import com.aquagrid.platform.gis.domain.enums.GeometryType;
import com.aquagrid.platform.gis.domain.enums.LayerStatus;
import com.aquagrid.platform.gis.domain.enums.StyleOperator;
import com.aquagrid.platform.gis.domain.enums.StyleType;
import com.aquagrid.platform.gis.domain.geo.GeometryCodec;
import com.aquagrid.platform.gis.domain.model.Asset;
import com.aquagrid.platform.gis.domain.model.Layer;
import com.aquagrid.platform.gis.domain.model.LayerAttribute;
import com.aquagrid.platform.gis.domain.style.MapLibreStyleComposer;
import com.aquagrid.platform.gis.domain.style.StyleTemplates;
import com.aquagrid.platform.gis.domain.style.SymbolLibrary;
import com.aquagrid.platform.gis.infrastructure.persistence.AssetRepository;
import com.aquagrid.platform.gis.infrastructure.persistence.LayerRepository;
import com.aquagrid.platform.gis.web.dto.StyleDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aquagrid.platform.identity.infrastructure.persistence.OrganizationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Layer Management and Layer Style Management, end to end against real PostGIS.
 *
 * <p>The claim under test is the pair's reason for existing: a layer created at runtime becomes a
 * fully working layer — tiled, counted, framed, styled and importable — with no migration and no
 * code change, and withdrawing one never destroys a feature. Every assertion below corresponds to a
 * way that claim could be quietly false.
 *
 * <p>Runs against the real schema because most of the mechanism is SQL: the layer-scoped count and
 * extent, the {@code layer_id} claim, the partial unique index that guarantees one default style,
 * and the CRS lookup against {@code spatial_ref_sys}.
 */
class LayerManagementIT extends AbstractIntegrationTest {

    /** Distinguishes rows across tests without a per-test cleanup. */
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private LayerManagementService layerService;
    @Autowired
    private LayerStyleService styleService;
    @Autowired
    private LayerMetadataService metadataService;
    @Autowired
    private GisService gisService;
    @Autowired
    private LayerRepository layerRepository;
    @Autowired
    private AssetRepository assetRepository;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SymbolLibrary symbolLibrary;

    private UUID orgId() {
        return organizationRepository.findByCodeIgnoreCase("SYSTEM").orElseThrow().getId();
    }

    private String unique(String prefix) {
        return prefix + "-" + SEQ.incrementAndGet();
    }

    // ---- Registration of the existing estate ----------------------------------------------------

    @Test
    @DisplayName("V1332 registers every existing layer without recreating one")
    void migrationRegistersTheExistingEstate() {
        UUID orgId = orgId();
        List<Layer> layers = layerRepository.findByOrganizationIdOrderBySortOrderAsc(orgId);

        assertThat(layers).isNotEmpty();
        // The registry describes the layers the platform already had; it did not create a second set.
        assertThat(layers).extracting(Layer::getCode)
                .contains("pipelines", "meters", "valves", "tanks", "open-wells", "bore-wells", "dmas");

        for (Layer layer : layers) {
            assertThat(layer.getGeometryType()).as("%s geometry", layer.getCode()).isNotNull();
            assertThat(layer.getSrid()).as("%s srid", layer.getCode()).isEqualTo(4326);
            assertThat(layer.crs()).isEqualTo("EPSG:4326");
            assertThat(layer.getStatus()).isEqualTo(LayerStatus.ACTIVE);
            assertThat(layer.getCategory()).as("%s category", layer.getCode()).isNotBlank();
            // Every layer's features still live in the supertype — nothing was moved or copied.
            assertThat(layer.getFeatureTable()).isEqualTo("gis.assets");
            assertThat(layer.getGeometryColumn()).isEqualTo("geom");
        }

        /*
         * The seeded layers are system layers. That flag is what stops the dashboard's PIPELINE
         * aggregate and the network trace being archived out from under them.
         */
        assertThat(layers.stream().filter(l -> "pipelines".equals(l.getCode())).findFirst().orElseThrow().isSystem())
                .isTrue();
    }

    @Test
    @DisplayName("V1333 migrates the client palette so the map looks unchanged")
    void everyLayerHasADefaultStyle() {
        UUID orgId = orgId();
        for (Layer layer : layerRepository.findByOrganizationIdOrderBySortOrderAsc(orgId)) {
            List<LayerStyleService.StyleDetail> styles = styleService.listForLayer(orgId, layer.getId());
            assertThat(styles).as("%s has a style", layer.getCode()).isNotEmpty();
            assertThat(styles).anyMatch(s -> s.style().isDefaultStyle() && s.style().isActive());
        }

        // The pipeline layer keeps the exact cyan it had in layerStyle.ts, which is what makes this a
        // migration of the appearance rather than a change to it.
        Layer pipelines = layerRepository.findByOrganizationIdAndCode(orgId, "pipelines").orElseThrow();
        var style = styleService.listForLayer(orgId, pipelines.getId()).get(0).style();
        assertThat(style.getSymbol()).containsEntry("lineColor", "#06B6D4");
    }

    // ---- Creating a layer -----------------------------------------------------------------------

    @Test
    @DisplayName("A layer created at runtime is tiled, counted and framed with no migration")
    void createdLayerIsImmediatelyUsable() {
        UUID orgId = orgId();
        String code = unique("street-lights");

        Layer layer = layerService.create(orgId, null, "test", new LayerCommands.Create(
                code, "Street Lights", "Lighting columns", "Other",
                AssetType.CUSTOM, GeometryType.POINT, "EPSG", 4326,
                true, true, true, true, true, true, true, true, 0, 24, null, false));

        assertThat(layer.getCode()).isEqualTo(code);
        assertThat(layer.getAssetType()).isEqualTo(AssetType.CUSTOM);
        assertThat(layer.isSystem()).isFalse();

        // Empty means empty, and the count is a real aggregate rather than a placeholder.
        assertThat(layerService.featureCount(orgId, layer)).isZero();
        assertThat(layerService.extent(orgId, layer)).isEmpty();

        // A feature written against the layer is counted and framed by it, and by no other layer.
        assetRepository.save(pointAsset(orgId, layer, 78.142, 11.6643));
        assertThat(layerService.featureCount(orgId, layer)).isEqualTo(1);
        assertThat(layerService.extent(orgId, layer)).hasValueSatisfying(box ->
                assertThat(box[0]).isCloseTo(78.142, org.assertj.core.data.Offset.offset(1e-6)));

        /*
         * And it tiles. The map needs nothing else: this is the whole "new layers must work without
         * source-code changes" requirement, reduced to one assertion.
         */
        byte[] tile = gisService.getTile(orgId, null, code, 0, 0, 0);
        assertThat(tile).isNotEmpty();
    }

    @Test
    @DisplayName("Two layers over one asset type draw their own features, not each other's")
    void layerScopingSeparatesLayersSharingAnAssetType() {
        UUID orgId = orgId();
        Layer domestic = layerService.create(orgId, null, "test", create(unique("domestic-meters"),
                "Domestic Meters", AssetType.METER, GeometryType.POINT));
        Layer bulk = layerService.create(orgId, null, "test", create(unique("bulk-meters"),
                "Bulk Meters", AssetType.METER, GeometryType.POINT));

        long domesticBefore = layerService.featureCount(orgId, domestic);
        long bulkBefore = layerService.featureCount(orgId, bulk);

        assetRepository.save(pointAsset(orgId, domestic, 78.10, 11.60));
        assetRepository.save(pointAsset(orgId, domestic, 78.11, 11.61));
        assetRepository.save(pointAsset(orgId, bulk, 78.20, 11.70));

        /*
         * The count each layer reports moved by exactly what was written to it. Before V1332 both
         * layers resolved to `asset_type = METER` and each would have reported all three — the
         * failure the layer_id column exists to remove, and the one LayerRepository's own javadoc
         * predicted.
         */
        assertThat(layerService.featureCount(orgId, domestic) - domesticBefore).isEqualTo(2);
        assertThat(layerService.featureCount(orgId, bulk) - bulkBefore).isEqualTo(1);
    }

    @Test
    @DisplayName("A layer name is validated, unique and permanent")
    void layerNamesAreValidatedAndUnique() {
        UUID orgId = orgId();
        String code = unique("valid-code");
        layerService.create(orgId, null, "test", create(code, "Valid", AssetType.CUSTOM, GeometryType.POINT));

        assertThatThrownBy(() -> layerService.create(orgId, null, "test",
                create(code, "Duplicate", AssetType.CUSTOM, GeometryType.POINT)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already exists");

        /*
         * The grammar is narrow because the code becomes a tile-URL path segment and a MapLibre
         * source id. A name that could carry a quote or a slash would eventually be interpolated
         * somewhere that cannot escape it.
         */
        assertThatThrownBy(() -> layerService.create(orgId, null, "test",
                create("Bad Name; DROP TABLE", "Bad", AssetType.CUSTOM, GeometryType.POINT)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not valid");
    }

    @Test
    @DisplayName("An SRID PostGIS does not know is refused at the point it is entered")
    void sridIsCheckedAgainstSpatialRefSys() {
        UUID orgId = orgId();
        assertThatThrownBy(() -> layerService.create(orgId, null, "test",
                new LayerCommands.Create(unique("bad-crs"), "Bad CRS", null, null,
                        AssetType.CUSTOM, GeometryType.POINT, "EPSG", 999_998,
                        true, false, true, true, true, true, true, true, 0, 24, null, false)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("spatial_ref_sys");

        // And the list offered to the client is that same catalogue, with the two that matter first.
        assertThat(layerService.availableCrs(null, 10))
                .extracting(LayerManagementService.CrsOption::srid)
                .startsWith(4326, 3857);
    }

    // ---- Withdrawal never deletes ---------------------------------------------------------------

    @Test
    @DisplayName("Disabling and archiving withdraw the layer and keep every feature")
    void withdrawalNeverDeletesAFeature() {
        UUID orgId = orgId();
        Layer layer = layerService.create(orgId, null, "test",
                create(unique("survey-2024"), "Survey 2024", AssetType.CUSTOM, GeometryType.POINT));
        assetRepository.save(pointAsset(orgId, layer, 78.3, 11.3));
        assertThat(layerService.featureCount(orgId, layer)).isEqualTo(1);

        layerService.changeStatus(orgId, null, "test", layer.getId(), LayerStatus.INACTIVE, "resurvey");
        assertThat(layerService.featureCount(orgId, layer)).isEqualTo(1);
        // Off the map, out of the import hub — but still addressable and still holding its features.
        assertThat(gisService.listLayers(orgId)).extracting(Layer::getId).doesNotContain(layer.getId());

        layerService.changeStatus(orgId, null, "test", layer.getId(), LayerStatus.ARCHIVED, "closed");
        assertThat(layerService.featureCount(orgId, layer)).isEqualTo(1);

        // Archived layers are out of the default list and visible only when asked for.
        assertThat(layerService.list(orgId, new LayerQuery(null, null, null, null, false)))
                .extracting(s -> s.layer().getId()).doesNotContain(layer.getId());
        assertThat(layerService.list(orgId, new LayerQuery(LayerStatus.ARCHIVED, null, null, null, false)))
                .extracting(s -> s.layer().getId()).contains(layer.getId());

        // And it comes back whole.
        layerService.changeStatus(orgId, null, "test", layer.getId(), LayerStatus.ACTIVE, null);
        assertThat(layerService.featureCount(orgId, layer)).isEqualTo(1);
    }

    @Test
    @DisplayName("A system layer cannot be archived out from under the code that reads it")
    void systemLayersCannotBeArchived() {
        UUID orgId = orgId();
        Layer pipelines = layerRepository.findByOrganizationIdAndCode(orgId, "pipelines").orElseThrow();

        assertThatThrownBy(() -> layerService.changeStatus(orgId, null, "test", pipelines.getId(),
                LayerStatus.ARCHIVED, "no"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Disable it instead");

        // Disabling is allowed: that takes it off the map without telling the code it is gone.
        layerService.changeStatus(orgId, null, "test", pipelines.getId(), LayerStatus.INACTIVE, null);
        layerService.changeStatus(orgId, null, "test", pipelines.getId(), LayerStatus.ACTIVE, null);
    }

    // ---- Styles read Data Management's fields ---------------------------------------------------

    @Test
    @DisplayName("A style may only name fields the Data Management catalogue has")
    void stylesAreValidatedAgainstTheAttributeCatalogue() {
        UUID orgId = orgId();
        Layer layer = layerRepository.findByOrganizationIdAndCode(orgId, "valves").orElseThrow();

        assertThatThrownBy(() -> styleService.save(orgId, null, "test", null, new StyleCommands.Save(
                layer.getId(), unique("Invented"), null, StyleType.CATEGORICAL,
                "a_field_nobody_defined", true, false, 0, 24, Map.of(), Map.of(), List.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Data Management");

        // A label naming a field the catalogue does not have is refused the same way.
        assertThatThrownBy(() -> styleService.save(orgId, null, "test", null, new StyleCommands.Save(
                layer.getId(), unique("Labelled"), null, StyleType.SIMPLE, null,
                true, false, 0, 24, Map.of(),
                Map.of("enabled", true, "field", "not_a_field"), List.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not an active field");
    }

    @Test
    @DisplayName("An ordered comparison on a text field is refused rather than silently never matching")
    void operatorsMustSuitTheFieldsDeclaredType() {
        UUID orgId = orgId();
        Layer layer = layerRepository.findByOrganizationIdAndCode(orgId, "meters").orElseThrow();

        assertThatThrownBy(() -> styleService.save(orgId, null, "test", null, new StyleCommands.Save(
                layer.getId(), unique("Bad operator"), null, StyleType.RULE_BASED, null,
                true, false, 0, 24, Map.of(), Map.of(),
                List.of(new StyleCommands.Rule("asset_code", StyleOperator.GT, "5", null, null,
                        "Big", Map.of("fillColor", "#3B82F6"), 10)))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("compare it as text");
    }

    @Test
    @DisplayName("A categorical style composes into a MapLibre match over the styled field")
    void categoricalStyleComposesIntoAMatchExpression() {
        UUID orgId = orgId();
        Layer layer = layerService.create(orgId, null, "test",
                create(unique("hydrant-condition"), "Hydrant Condition", AssetType.CUSTOM, GeometryType.POINT));

        // The field comes from Data Management, exactly as it would for any other consumer.
        LayerAttribute condition = metadataService.create(orgId, null, "test", new AttributeCommands.Create(
                layer.getId(), "condition", "Condition", "Field condition", AttributeDataType.TEXT,
                40, null, null, null, "GOOD", false, false, false, true, true, true, null, null));
        assertThat(condition.getFieldName()).isEqualTo("condition");

        var saved = styleService.save(orgId, null, "test", null, new StyleCommands.Save(
                layer.getId(), "Condition", "By condition", StyleType.CATEGORICAL, "condition",
                true, true, 0, 24,
                Map.of("fillColor", "#B9C2D0"), Map.of("enabled", false),
                List.of(
                        new StyleCommands.Rule("condition", StyleOperator.EQ, "GOOD", null, null,
                                "Good", Map.of("fillColor", "#14B8A6"), 10),
                        new StyleCommands.Rule("condition", StyleOperator.EQ, "FAULT", null, null,
                                "Faulty", Map.of("fillColor", "#EC4899"), 20))));
        assertThat(saved.rules()).hasSize(2);

        List<MapLibreStyleComposer.ComposedLayer> composed =
                styleService.composeMapStyle(orgId, "/api/v1/gis/tiles/{layer}/{z}/{x}/{y}");
        MapLibreStyleComposer.ComposedLayer mine = composed.stream()
                .filter(c -> c.code().equals(layer.getCode())).findFirst().orElseThrow();

        // The tile must carry the field the expression reads, or the style silently draws everything
        // in the fallback colour and nothing reports a problem.
        assertThat(mine.styledFields()).contains("condition");

        Object circleColour = mine.layers().stream()
                .filter(spec -> (mine.sourceId() + "-point").equals(spec.get("id")))
                .findFirst().orElseThrow()
                .get("paint") instanceof Map<?, ?> paint ? paint.get("circle-color") : null;
        assertThat(circleColour).isInstanceOf(List.class);
        assertThat(((List<?>) circleColour).get(0)).isEqualTo("match");
        assertThat(circleColour.toString()).contains("#14B8A6").contains("#EC4899");

        // The legend is a by-product of the same expression, which is why it cannot drift from it.
        assertThat(mine.legend()).extracting(MapLibreStyleComposer.LegendEntry::label)
                .containsExactly("Good", "Faulty", "Other");
    }

    @Test
    @DisplayName("Labels compose into a symbol layer reading a Data Management field")
    void labelsComposeIntoASymbolLayer() {
        UUID orgId = orgId();
        Layer layer = layerService.create(orgId, null, "test",
                create(unique("labelled"), "Labelled", AssetType.CUSTOM, GeometryType.POINT));
        metadataService.create(orgId, null, "test", new AttributeCommands.Create(
                layer.getId(), "asset_id", "Asset Id", null, AttributeDataType.TEXT,
                40, null, null, null, "BW/KDB/23", false, false, false, true, true, true, null, null));

        styleService.save(orgId, null, "test", null, new StyleCommands.Save(
                layer.getId(), "Labelled", null, StyleType.SIMPLE, null, true, true, 0, 24,
                Map.of("fillColor", "#3B82F6"),
                Map.of("enabled", true, "field", "asset_id", "textSize", 12), List.of()));

        MapLibreStyleComposer.ComposedLayer composed =
                styleService.composeMapStyle(orgId, "/api/v1/gis/tiles/{layer}/{z}/{x}/{y}").stream()
                        .filter(c -> c.code().equals(layer.getCode())).findFirst().orElseThrow();

        Map<?, ?> label = composed.layers().stream()
                .filter(spec -> (composed.sourceId() + "-label").equals(spec.get("id")))
                .findFirst().orElseThrow();
        assertThat(label.get("type")).isEqualTo("symbol");

        Map<?, ?> layout = (Map<?, ?>) label.get("layout");
        /*
         * `to-string` around the property, not the property alone: a numeric tile property makes
         * MapLibre reject the whole layer rather than coerce, and that takes the style down with it.
         */
        assertThat(layout.get("text-field").toString()).contains("to-string").contains("asset_id");
        /*
         * The font stack must name something the glyph endpoint actually serves. It cannot be
         * asserted against the endpoint here, but pinning it stops a silent rename: a stack the
         * server 404s produces no error, just a symbol layer that draws nothing — which is exactly
         * how the first spelling of this constant passed a green build.
         */
        assertThat(layout.get("text-font")).isEqualTo(List.of("Noto Sans Regular", "Open Sans Semibold"));

        // And the tile must carry the field being drawn.
        assertThat(composed.styledFields()).contains("asset_id");
    }

    @Test
    @DisplayName("Exactly one active default style per layer, enforced by the database")
    void onlyOneDefaultStylePerLayer() {
        UUID orgId = orgId();
        Layer layer = layerService.create(orgId, null, "test",
                create(unique("default-test"), "Default Test", AssetType.CUSTOM, GeometryType.POINT));

        var first = styleService.save(orgId, null, "test", null, simple(layer.getId(), "First", true));
        var second = styleService.save(orgId, null, "test", null, simple(layer.getId(), "Second", true));

        // Saving a second default demoted the first rather than producing two.
        assertThat(styleService.get(orgId, first.style().getId()).style().isDefaultStyle()).isFalse();
        assertThat(styleService.get(orgId, second.style().getId()).style().isDefaultStyle()).isTrue();

        styleService.makeDefault(orgId, null, "test", first.style().getId());
        assertThat(styleService.get(orgId, second.style().getId()).style().isDefaultStyle()).isFalse();
    }

    @Test
    @DisplayName("Deactivating the default leaves the layer drawn with the built-in symbology")
    void deactivatingTheDefaultDoesNotBlankTheLayer() {
        UUID orgId = orgId();
        Layer layer = layerService.create(orgId, null, "test",
                create(unique("fallback-test"), "Fallback Test", AssetType.CUSTOM, GeometryType.POINT));
        var style = styleService.save(orgId, null, "test", null, simple(layer.getId(), "Only", true));

        styleService.setActive(orgId, null, "test", style.style().getId(), false, "retired");

        List<MapLibreStyleComposer.ComposedLayer> composed =
                styleService.composeMapStyle(orgId, "/api/v1/gis/tiles/{layer}/{z}/{x}/{y}");
        MapLibreStyleComposer.ComposedLayer mine = composed.stream()
                .filter(c -> c.code().equals(layer.getCode())).findFirst().orElseThrow();

        // Still composed, still with render layers — the layer draws, it just draws plainly.
        assertThat(mine.styleId()).isNull();
        assertThat(mine.layers()).isNotEmpty();
    }

    @Test
    @DisplayName("A layer with vector tiles switched off is absent from the composed map style")
    void vectorTileFlagGatesTheComposedStyle() {
        UUID orgId = orgId();
        Layer layer = layerService.create(orgId, null, "test",
                create(unique("export-only"), "Export Only", AssetType.CUSTOM, GeometryType.POLYGON));

        layerService.update(orgId, null, "test", layer.getId(), new LayerCommands.Update(
                null, null, null, null, null, null, null, null, null, null, null, null,
                false, null, null, null));

        assertThat(styleService.composeMapStyle(orgId, "/api/v1/gis/tiles/{layer}/{z}/{x}/{y}"))
                .extracting(MapLibreStyleComposer.ComposedLayer::code)
                .doesNotContain(layer.getCode());

        /*
         * And the tile endpoint answers with a valid empty tile rather than an error. A 404 or a 500
         * on the map's hot path leaves MapLibre retrying and fills the console with red; an empty
         * tile is cached as "nothing here", which is what switching tiles off was asking for.
         */
        assertThat(gisService.getTile(orgId, null, layer.getCode(), 0, 0, 0)).isNotEmpty();
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    private static LayerCommands.Create create(String code, String title, AssetType assetType,
                                               GeometryType geometryType) {
        return new LayerCommands.Create(code, title, null, "Other", assetType, geometryType,
                "EPSG", 4326, true, false, true, true, true, true, true, true, 0, 24, null, false);
    }

    private static StyleCommands.Save simple(UUID layerId, String name, boolean asDefault) {
        return new StyleCommands.Save(layerId, name, null, StyleType.SIMPLE, null,
                true, asDefault, 0, 24, Map.of("fillColor", "#3B82F6"),
                Map.of("enabled", false), List.of());
    }

    private Asset pointAsset(UUID orgId, Layer layer, double lon, double lat) {
        Asset asset = new Asset();
        asset.setOrganizationId(orgId);
        asset.setAssetCode(unique("LM").toUpperCase());
        asset.setName("Test feature");
        asset.setAssetType(layer.getAssetType());
        asset.setLayerId(layer.getId());
        asset.setStatus(AssetStatus.IN_SERVICE);
        asset.setGeom(GeometryCodec.fromGeoJson(Map.of(
                "type", "Point", "coordinates", List.of(lon, lat))));
        return asset;
    }

    @Test
    @DisplayName("An unknown layer code is a 404; a layer with tiles off is a parseable empty tile")
    void unknownLayerIsNotFoundButADisabledLayerStillAnswers() {
        UUID orgId = orgId();

        /*
         * Nothing legitimate asks for a code the registry does not hold — the map builds its sources
         * from the composed style — so this is a stale bookmark or a client bug, and it says so.
         * This used to answer an empty tile, which made a typo indistinguishable from a layer that
         * exists and happens to be empty.
         */
        assertThatThrownBy(() -> gisService.getTile(orgId, null, "no-such-layer", 0, 0, 0))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        /*
         * A layer that exists with tiles switched off is the opposite case and keeps answering. It is
         * reachable rather than mistaken: a map loaded before the flag changed goes on requesting a
         * source it already mounted, and MapLibre's worker parses every tile response as a Protobuf
         * VectorTile unconditionally — a zero-length body makes it throw and strands the source in a
         * loading state forever.
         */
        Layer disabled = layerService.create(orgId, null, "test", new LayerCommands.Create(
                unique("tiles-off"), "Tiles Off", null, "Other",
                AssetType.CUSTOM, GeometryType.POINT, "EPSG", 4326,
                true, true, true, true, true, true, true, false, 0, 24, null, false));
        assertThat(gisService.getTile(orgId, null, disabled.getCode(), 0, 0, 0)).isNotEmpty();
    }

    @Test
    @DisplayName("Data Management still owns attributes; the registry only links to it")
    void theRegistryDoesNotManageAttributes() {
        UUID orgId = orgId();
        Layer layer = layerService.create(orgId, null, "test",
                create(unique("attr-owner"), "Attribute Owner", AssetType.CUSTOM, GeometryType.POINT));

        /*
         * A new layer starts with no attributes of its own: the V1331 seed ran against the layers
         * that existed then, and Layer Management deliberately creates none. Fields arrive through
         * Data Management and only through it, which is why the registry's button navigates there
         * rather than opening an editor of its own.
         */
        assertThat(metadataService.definitionsForLayer(orgId, layer.getId())).isEmpty();

        metadataService.create(orgId, null, "test", new AttributeCommands.Create(
                layer.getId(), "pole_number", "Pole Number", null, AttributeDataType.TEXT,
                40, null, null, null, "P-1", false, false, false, true, true, true, null, null));

        assertThat(metadataService.definitionsForLayer(orgId, layer.getId()))
                .extracting(com.aquagrid.platform.gis.api.AttributeDefinition::fieldName)
                .containsExactly("pole_number");

        // And that field is immediately available to a style, with no second catalogue in between.
        assertThat(styleService.catalogue(orgId, layer.getId())).containsKey("pole_number");
    }

    @Test
    @DisplayName("Creating a layer with claimExistingFeatures adopts the unassigned rows of its type")
    void createWithClaimAdoptsUnassignedFeatures() {
        UUID orgId = orgId();

        /*
         * A row written before any layer existed for its type — the shape of every row backfilled by
         * V1332, and of anything an import lands for a tenant that has no layer row yet. It carries
         * no layer id and is visible only through the asset-type fallback.
         */
        Layer holder = layerService.create(orgId, null, "test",
                create(unique("claim-holder"), "Claim Holder", AssetType.HYDRANT, GeometryType.POINT));
        Asset unclaimed = pointAsset(orgId, holder, 78.4, 11.4);
        unclaimed.setAssetType(AssetType.HYDRANT);
        unclaimed.setLayerId(null);
        assetRepository.save(unclaimed);

        // The import wizard's "create a new layer" path asks for the claim; the registry's own create
        // form does not, because adopting an unrelated backlog on a button press is a surprise.
        Layer claiming = layerService.create(orgId, null, "test", new LayerCommands.Create(
                unique("claim-test"), "Claim Test", null, "Other",
                AssetType.HYDRANT, GeometryType.POINT, "EPSG", 4326,
                true, false, true, true, true, true, true, true, 0, 24, null, true));

        assertThat(assetRepository.findById(unclaimed.getId()).orElseThrow().getLayerId())
                .isEqualTo(claiming.getId());
    }

    @Test
    @DisplayName("Errors from a bad style name and a bad zoom range say what is wrong")
    void validationMessagesAreActionable() {
        UUID orgId = orgId();
        Layer layer = layerRepository.findByOrganizationIdAndCode(orgId, "tanks").orElseThrow();

        assertThatThrownBy(() -> styleService.save(orgId, null, "test", null, new StyleCommands.Save(
                layer.getId(), unique("Zoomy"), null, StyleType.SIMPLE, null, true, false,
                20, 5, Map.of(), Map.of(), List.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("can never draw");

        assertThatThrownBy(() -> styleService.save(orgId, null, "test", null, new StyleCommands.Save(
                layer.getId(), unique("Colourful"), null, StyleType.SIMPLE, null, true, false,
                0, 24, Map.of("fillColor", "not-a-colour"), Map.of(), List.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not a colour");
    }

    @Test
    @DisplayName("A layer's own extent frames it, independently of the tenant's other layers")
    void extentIsPerLayer() {
        UUID orgId = orgId();
        Layer near = layerService.create(orgId, null, "test",
                create(unique("near"), "Near", AssetType.CUSTOM, GeometryType.POINT));
        Layer far = layerService.create(orgId, null, "test",
                create(unique("far"), "Far", AssetType.CUSTOM, GeometryType.POINT));

        assetRepository.save(pointAsset(orgId, near, 78.0, 11.0));
        assetRepository.save(pointAsset(orgId, far, 80.0, 13.0));

        double[] nearBox = layerService.extent(orgId, near).orElseThrow();
        assertThat(nearBox[2]).isLessThan(79.0);

        // The GIS read path resolves the same box by code, which is what the map's opening camera uses.
        assertThat(gisService.layerExtent(orgId, near.getCode())).isPresent();
    }

    @Test
    @DisplayName("Every style template composes and saves without an edit")
    void templatesProduceStylesTheServerAccepts() {
        UUID orgId = orgId();
        Layer layer = layerService.create(orgId, null, "test",
                create(unique("templated"), "Templated", AssetType.CUSTOM, GeometryType.GEOMETRY));
        // A field for the classified templates to classify on, from Data Management as always.
        metadataService.create(orgId, null, "test", new AttributeCommands.Create(
                layer.getId(), "status", "Status", null, AttributeDataType.TEXT,
                20, null, null, null, "IN_SERVICE", false, false, false, true, true, true, null, null));

        List<StyleTemplates.Template> templates = StyleTemplates.forGeometry(GeometryType.GEOMETRY);
        assertThat(templates).isNotEmpty();

        for (StyleTemplates.Template template : templates) {
            /*
             * The point of the test: a template is only useful if it produces a style the server
             * accepts, unedited. Anything the templates put in a symbol has to survive
             * validatedSymbol — a colour it does not recognise, an opacity above 1, a line cap it
             * does not know — and a classified template's seeds have to survive the rule validation
             * with the field and operator they declare.
             */
            List<StyleCommands.Rule> rules = new java.util.ArrayList<>();
            int order = 0;
            for (StyleTemplates.RuleSeed seed : template.ruleSeeds()) {
                order += 10;
                rules.add(new StyleCommands.Rule(
                        "status", seed.operator(), seed.value(),
                        // A graduated seed carries a lower bound only; the editor collects the upper.
                        seed.operator() == StyleOperator.BETWEEN ? seed.value() : null,
                        null, seed.label(), seed.symbol(), order));
            }
            /*
             * Graduated templates classify on a numeric field, and `status` is TEXT — the server is
             * right to refuse that, and the editor's field picker only offers numeric fields for a
             * graduated style. Skipping it here tests the templates rather than re-testing the
             * refusal, which operatorsMustSuitTheFieldsDeclaredType already covers.
             */
            if (template.styleType() == StyleType.GRADUATED) {
                continue;
            }

            var saved = styleService.save(orgId, null, "test", null, new StyleCommands.Save(
                    layer.getId(), template.name() + " " + unique(""), template.description(),
                    template.styleType(), template.styleType().requiresClassifyField() ? "status" : null,
                    true, false, 0, 24, template.symbol(), template.label(), rules));

            assertThat(saved.style().getSymbol()).as("%s symbol survived validation", template.id())
                    .isNotEmpty();
            assertThat(saved.rules()).as("%s rules", template.id()).hasSize(rules.size());
        }
    }

    @Test
    @DisplayName("The vocabulary and templates serialise to bodies the editor can render")
    void referenceBodiesSerialise() throws Exception {
        /*
         * The style editor renders nothing at all without these two: the style-type dropdown, the
         * operator list and every symbol control are built from them, and a failure reads on screen
         * as "this product has no style options" rather than as a broken call. That is exactly how a
         * stale classpath presented itself once — the endpoints 500'd on a NoClassDefFoundError and
         * the page rendered an empty form — so both bodies are asserted to be complete, not merely
         * non-null.
         */
        String vocabulary = objectMapper.writeValueAsString(
                StyleDtos.VocabularyResponse.build(symbolLibrary.all()));
        assertThat(vocabulary).contains("SIMPLE", "CATEGORICAL", "GRADUATED", "RULE_BASED")
                .contains("BETWEEN", "IS_NOT_NULL")
                .contains("fillColor", "lineWidth", "dashPattern", "iconSize")
                .contains("diamond", "hexagon");

        String templates = objectMapper.writeValueAsString(
                StyleTemplates.all().stream().map(StyleDtos.TemplateResponse::from).toList());
        assertThat(templates).contains("Operational point", "Distribution main", "Zone boundary",
                "By status", "Labelled");

        // Every template's symbol carries a value for every key, so applying one leaves no control
        // showing an unstated default the renderer supplied rather than the administrator.
        assertThat(StyleTemplates.all()).allSatisfy(template ->
                assertThat(template.symbol()).as("%s symbol", template.id())
                        .containsKeys("fillColor", "lineColor", "lineWidth", "size", "opacity"));

        // And none ships labels enabled without a field — the one combination the server refuses.
        assertThat(StyleTemplates.all()).allSatisfy(template ->
                assertThat(template.label().get("enabled")).as("%s labels", template.id())
                        .isEqualTo(false));
    }

    @Test
    @DisplayName("Templates are filtered to the layer's geometry")
    void templatesAreFilteredByGeometry() {
        // A dashed-boundary template is no use on a point layer, and a point-size template is no use
        // on a line layer. A mixed-geometry layer gets everything, because it may hold everything.
        assertThat(StyleTemplates.forGeometry(GeometryType.POINT))
                .allMatch(t -> t.families().contains(GeometryType.Family.POINT));
        assertThat(StyleTemplates.forGeometry(GeometryType.MULTILINESTRING))
                .allMatch(t -> t.families().contains(GeometryType.Family.LINE));
        assertThat(StyleTemplates.forGeometry(GeometryType.GEOMETRY))
                .hasSameSizeAs(StyleTemplates.all());

        // Every template names a field only as a suggestion; none of them can force one into a style.
        assertThat(StyleTemplates.all())
                .filteredOn(t -> t.styleType().requiresClassifyField())
                .allSatisfy(t -> assertThat(t.ruleSeeds()).isNotEmpty());
    }

    @Test
    @DisplayName("Reference data is served from the enums the server validates against")
    void referenceDataMatchesTheServersOwnVocabulary() {
        assertThat(GeometryType.values()).hasSize(8);
        assertThat(GeometryType.POINT.accepts("MultiPoint")).isTrue();
        // The tolerance is deliberate: contractors deliver Polygon and MultiPolygon interchangeably.
        assertThat(GeometryType.POLYGON.accepts("MultiPolygon")).isTrue();
        // What it still catches is the mistake that matters — the wrong layer entirely.
        assertThat(GeometryType.POINT.accepts("LineString")).isFalse();
        assertThat(GeometryType.GEOMETRY.accepts("MultiPolygon")).isTrue();

        assertThat(layerService.categories(orgId())).contains("Pipe Network", "Facilities", "Boundaries");
    }

    @Test
    @DisplayName("ErrorCode contract: a missing layer is a 404, a system layer archive a 403")
    void errorCodesMatchTheHttpContract() {
        UUID orgId = orgId();
        assertThatThrownBy(() -> layerService.require(orgId, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        Layer pipelines = layerRepository.findByOrganizationIdAndCode(orgId, "pipelines").orElseThrow();
        assertThatThrownBy(() -> layerService.changeStatus(orgId, null, "test", pipelines.getId(),
                LayerStatus.ARCHIVED, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.OPERATION_NOT_PERMITTED);
    }
}

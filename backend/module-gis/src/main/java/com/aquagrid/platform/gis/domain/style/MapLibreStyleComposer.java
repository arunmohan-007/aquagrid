package com.aquagrid.platform.gis.domain.style;

import com.aquagrid.platform.gis.domain.enums.AttributeDataType;
import com.aquagrid.platform.gis.domain.enums.GeometryType;
import com.aquagrid.platform.gis.domain.enums.StyleOperator;
import com.aquagrid.platform.gis.domain.enums.StyleType;
import com.aquagrid.platform.gis.domain.model.Layer;
import com.aquagrid.platform.gis.domain.model.LayerStyle;
import com.aquagrid.platform.gis.domain.model.LayerStyleRule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns a layer and its style into the MapLibre layer specifications the map applies verbatim.
 *
 * <p>This class is the reason nothing about a layer's appearance is decided in JavaScript. Before
 * it, {@code MapView.syncLayers} built five hard-coded render layers per catalogue entry and read
 * their colours from a hard-coded table keyed by layer code — so a layer created at runtime rendered
 * grey until someone shipped a release. Now the client adds a source and adds whatever layers this
 * hands it, and a new layer or a recoloured one is a database change.
 *
 * <p>The composed output keeps the render-layer id convention the client already used
 * ({@code assets-<code>-fill}, {@code -line-casing}, {@code -line}, {@code -point-halo},
 * {@code -point}, and now {@code -label}). That is not nostalgia: those ids are what
 * {@code pickFeature} queries and what {@code removeAssetLayer} tears down, and changing them would
 * have broken inspection and layer toggling for a cosmetic gain.
 *
 * <h2>Why the expressions are built here and not in the client</h2>
 * A classified style has to be expressed twice — once on the map and once in the preview that shows
 * an administrator what they are about to save. Two compilers of the same rules is two chances to
 * disagree, and the disagreement always surfaces as "the preview lied". One compiler, on the server,
 * feeding both, cannot.
 */
public final class MapLibreStyleComposer {

    /**
     * The line casing colour: a near-black wider line drawn under the coloured one.
     *
     * <p>Not configurable, and deliberately so. It is not a colour choice, it is the standard "tube"
     * technique that keeps a thin coloured line legible over dark cartography and bright imagery
     * alike; exposing it would let an administrator set it to the fill colour and quietly lose the
     * legibility the layer depends on at satellite zoom.
     */
    private static final String CASING_COLOUR = "#05070D";

    /**
     * The font stack for labels.
     *
     * <p>The name must exist on the glyph endpoint the base map style points at
     * ({@code /basemap/fonts}, proxied to MapLibre's own font server). That endpoint serves
     * {@code Noto Sans Regular} and {@code Open Sans Semibold} and nothing else — a stack naming
     * anything else answers 404, and MapLibre does not report that as an error: the symbol layer
     * simply draws nothing, which is indistinguishable from labels being switched off. Which is
     * exactly how the first spelling of this constant got through a green build.
     *
     * <p>Regular first, semibold as the fallback MapLibre composites from when a glyph is missing.
     * Regular is the right weight for a dense map: bold labels over a network read as emphasis
     * nobody intended.
     */
    private static final List<String> LABEL_FONT = List.of("Noto Sans Regular", "Open Sans Semibold");

    private MapLibreStyleComposer() {
    }

    /**
     * The complete rendering instruction for one layer.
     *
     * @param sourceId    the MapLibre source id, {@code assets-<code>}
     * @param source      the vector source specification, ready for {@code map.addSource}
     * @param layers      the render layers, in draw order, ready for {@code map.addLayer}
     * @param legend      what the layer contributes to the legend, already resolved from the rules
     * @param styledFields the catalogue fields the expressions read, which the tile must therefore
     *                    carry. Returned so the client and the tile endpoint agree without either
     *                    inferring it from the other.
     * @param requiredIcons the library and uploaded icon ids the composed {@code icon-image}
     *                    expressions reference — {@code lib-water}, {@code sym-<uuid>} — so the
     *                    client can fetch and register them with {@code map.addImage} before the
     *                    layers referencing them are added. MapLibre draws nothing for a missing
     *                    image and reports no error, so this is the difference between an icon that
     *                    fails to appear and one that predictably will.
     */
    public record ComposedLayer(
            String layerId,
            String code,
            String title,
            String category,
            String sourceId,
            Map<String, Object> source,
            String sourceLayer,
            boolean visibleByDefault,
            boolean queryable,
            int minZoom,
            int maxZoom,
            String styleId,
            String styleName,
            List<Map<String, Object>> layers,
            List<LegendEntry> legend,
            List<String> styledFields,
            List<String> requiredIcons
    ) {
    }

    /**
     * One swatch in the legend.
     *
     * <p>Composed from the same rules that produced the paint, which is the whole point: a legend
     * that maintains its own copy of the palette is a legend that eventually lies, and this one
     * cannot because it is a by-product of the expression rather than a parallel description of it.
     */
    public record LegendEntry(String label, String colour, String shape) {
    }

    /**
     * Composes one layer's rendering instruction.
     *
     * @param style null when the layer has no active default style — the layer still renders, with
     *              the platform's fallback symbology, because a layer that draws nothing because
     *              nobody has styled it yet is a worse answer than a grey one
     * @param rules the style's active rules, already ordered by {@code sortOrder}. Order is part of
     *              the contract: MapLibre's {@code case} is first-match.
     * @param fieldTypes the declared type of every active attribute on the layer, from Data
     *                   Management. Used to decide whether a rule's operand is emitted as a number
     *                   or a string — {@code ['==', ['get','diameter'], '150']} never matches a
     *                   numeric tile property, and that is a silent no-match rather than an error.
     */
    public static ComposedLayer compose(Layer layer, LayerStyle style, List<LayerStyleRule> rules,
                                        Map<String, AttributeDataType> fieldTypes,
                                        String tileUrlTemplate) {

        Map<String, Object> symbol = style == null ? Map.of() : nullSafe(style.getSymbol());
        Map<String, Object> label = style == null ? Map.of() : nullSafe(style.getLabel());
        StyleType type = style == null ? StyleType.SIMPLE : style.getStyleType();
        List<LayerStyleRule> ordered = rules == null ? List.of() : rules.stream()
                .filter(LayerStyleRule::isActive)
                .sorted(Comparator.comparingInt(LayerStyleRule::getSortOrder)
                        .thenComparing(r -> r.getId() == null ? "" : r.getId().toString()))
                .toList();

        String sourceId = "assets-" + layer.getCode();
        int minZoom = effectiveMin(layer, style);
        int maxZoom = effectiveMax(layer, style);

        List<Map<String, Object>> renderLayers = new ArrayList<>();
        GeometryType.Family family = layer.getGeometryType().family();

        /*
         * Polygons, then lines, then points, then labels. The order is the draw order and it is
         * load-bearing: a meter drawn before the DMA it sits inside disappears under the zone's
         * fill, and a label drawn under a line is unreadable. This is the same order the hard-coded
         * client implementation used, preserved because it was right.
         */
        if (family == GeometryType.Family.POLYGON || family == GeometryType.Family.ANY) {
            renderLayers.add(fillLayer(sourceId, layer, symbol, type, ordered, fieldTypes, minZoom, maxZoom));
        }
        if (family == GeometryType.Family.LINE || family == GeometryType.Family.POLYGON
                || family == GeometryType.Family.ANY) {
            renderLayers.add(lineCasingLayer(sourceId, layer, symbol, minZoom, maxZoom));
            renderLayers.add(lineLayer(sourceId, layer, symbol, type, ordered, fieldTypes, minZoom, maxZoom));
        }
        if (family == GeometryType.Family.POINT || family == GeometryType.Family.ANY) {
            renderLayers.add(pointHaloLayer(sourceId, layer, symbol, minZoom, maxZoom));
            renderLayers.add(pointLayer(sourceId, layer, symbol, type, ordered, fieldTypes, minZoom, maxZoom));
        }
        Map<String, Object> labelLayer = labelLayer(sourceId, layer, label, symbol, type, ordered,
                fieldTypes, minZoom, maxZoom);
        if (labelLayer != null) {
            renderLayers.add(labelLayer);
        }

        return new ComposedLayer(
                layer.getId().toString(),
                layer.getCode(),
                layer.getTitle(),
                layer.getCategory(),
                sourceId,
                Map.of(
                        "type", "vector",
                        "tiles", List.of(tileUrlTemplate),
                        "minzoom", 0,
                        // The source's maxzoom is the deepest zoom tiles are *cut* at, not the
                        // deepest they are drawn at: MapLibre overzooms beyond it by scaling the
                        // last tile, which is what keeps a street-level view from going blank.
                        "maxzoom", 20,
                        /*
                         * The asset's primary key, promoted from a tile property to the feature's
                         * own id.
                         *
                         * ST_AsMVT can stamp a feature id itself, but only a uint64, and an asset is
                         * keyed by UUID — hashing it down would make the id stable only until two
                         * assets collided, which is precisely the kind of defect that appears once
                         * a tenant is large and never in a test. `promoteId` keeps the real key and
                         * moves it to where MapLibre looks for one, so `feature.id` is the asset id
                         * and `setFeatureState` addresses a feature by it. Without this the id is
                         * readable only as a property, and every hover or selection effect has to be
                         * a filter expression and a style recompilation instead of a state flag.
                         *
                         * Keyed by source-layer because a vector source may carry several; this one
                         * carries the layer's own code, the same name ST_AsMVT is given.
                         */
                        "promoteId", Map.of(layer.getCode(), "id")),
                layer.getCode(),
                layer.isVisible(),
                layer.isQueryable(),
                minZoom,
                maxZoom,
                style == null ? null : style.getId().toString(),
                style == null ? null : style.getName(),
                renderLayers,
                legendFor(layer, symbol, type, ordered),
                styledFieldNames(style, ordered),
                requiredIconNames(symbol, ordered));
    }

    /**
     * The library or uploaded icon ids the composed layer needs registered before it draws.
     *
     * <p>Only {@code lib-} and {@code sym-} ids — the two the client cannot draw itself. The seven
     * built-in shapes ({@code circle}, {@code diamond}…) are generated on the client at style load
     * regardless of whether any layer uses them, so they need no entry here; naming them would just
     * be a list the client already has.
     */
    private static List<String> requiredIconNames(Map<String, Object> symbol, List<LayerStyleRule> rules) {
        List<String> icons = new ArrayList<>();
        collectIcon(symbol, icons);
        rules.forEach(rule -> collectIcon(nullSafe(rule.getSymbol()), icons));
        return icons.stream().distinct().toList();
    }

    private static void collectIcon(Map<String, Object> symbol, List<String> out) {
        Object icon = symbol.get(SymbolKeys.ICON);
        if (icon == null) {
            return;
        }
        String value = icon.toString();
        if (value.startsWith("lib-") || value.startsWith("sym-")) {
            out.add(value);
        }
    }

    // ---- Render layers -------------------------------------------------------------------------

    private static Map<String, Object> fillLayer(String sourceId, Layer layer, Map<String, Object> symbol,
                                                 StyleType type, List<LayerStyleRule> rules,
                                                 Map<String, AttributeDataType> fieldTypes,
                                                 int minZoom, int maxZoom) {
        Map<String, Object> paint = new LinkedHashMap<>();
        paint.put("fill-color", value(SymbolKeys.FILL_COLOR, symbol, "#B9C2D0", type, rules, fieldTypes));
        paint.put("fill-opacity", number(SymbolKeys.FILL_OPACITY, symbol, 0.14));
        paint.put("fill-outline-color", value(SymbolKeys.OUTLINE_COLOR, symbol, "#CBD5E1", type, rules, fieldTypes));
        return renderLayer(sourceId + "-fill", "fill", sourceId, layer.getCode(),
                geometryFilter("Polygon"), null, paint, minZoom, maxZoom);
    }

    /**
     * The dark casing under a coloured line.
     *
     * <p>Never classified, and that is intentional: the casing exists to separate the line from
     * whatever is beneath it, so varying it by attribute would defeat the one thing it does. Its
     * width tracks the styled line width so a thickened line keeps its outline proportional.
     */
    private static Map<String, Object> lineCasingLayer(String sourceId, Layer layer,
                                                       Map<String, Object> symbol,
                                                       int minZoom, int maxZoom) {
        double width = number(SymbolKeys.LINE_WIDTH, symbol, 3.0);
        Map<String, Object> paint = new LinkedHashMap<>();
        paint.put("line-color", CASING_COLOUR);
        paint.put("line-width", zoomRamp(width * 1.7));
        return renderLayer(sourceId + "-line-casing", "line", sourceId, layer.getCode(),
                lineOrPolygonFilter(),
                Map.of("line-cap", string(SymbolKeys.LINE_CAP, symbol, "round"),
                        "line-join", string(SymbolKeys.LINE_JOIN, symbol, "round")),
                paint, minZoom, maxZoom);
    }

    private static Map<String, Object> lineLayer(String sourceId, Layer layer, Map<String, Object> symbol,
                                                 StyleType type, List<LayerStyleRule> rules,
                                                 Map<String, AttributeDataType> fieldTypes,
                                                 int minZoom, int maxZoom) {
        Map<String, Object> paint = new LinkedHashMap<>();
        paint.put("line-color", value(SymbolKeys.LINE_COLOR, symbol, "#B9C2D0", type, rules, fieldTypes));
        paint.put("line-width", zoomRamp(number(SymbolKeys.LINE_WIDTH, symbol, 3.0)));
        paint.put("line-opacity", number(SymbolKeys.LINE_OPACITY, symbol, 1.0));
        List<Double> dash = dashPattern(symbol);
        if (dash != null) {
            paint.put("line-dasharray", dash);
        }
        return renderLayer(sourceId + "-line", "line", sourceId, layer.getCode(),
                lineOrPolygonFilter(),
                Map.of("line-cap", string(SymbolKeys.LINE_CAP, symbol, "round"),
                        "line-join", string(SymbolKeys.LINE_JOIN, symbol, "round")),
                paint, minZoom, maxZoom);
    }

    /** The soft halo beneath a point; see {@link SymbolKeys#GLOW_COLOR} for why it exists. */
    private static Map<String, Object> pointHaloLayer(String sourceId, Layer layer,
                                                      Map<String, Object> symbol,
                                                      int minZoom, int maxZoom) {
        double size = number(SymbolKeys.SIZE, symbol, 5.0);
        Map<String, Object> paint = new LinkedHashMap<>();
        paint.put("circle-color", string(SymbolKeys.GLOW_COLOR, symbol,
                string(SymbolKeys.FILL_COLOR, symbol, "#CBD5E1")));
        paint.put("circle-opacity", 0.35);
        paint.put("circle-radius", zoomRamp(size * 2.0));
        paint.put("circle-blur", 0.7);
        return renderLayer(sourceId + "-point-halo", "circle", sourceId, layer.getCode(),
                geometryFilter("Point"), null, paint, minZoom, maxZoom);
    }

    /**
     * The point itself — a circle, or a symbol when the style asks for an icon.
     *
     * <p>Icon names are shapes the client registers with {@code map.addImage} at style load rather
     * than sprites fetched from a sprite sheet. The base map styles here are raster and carry no
     * sprite at all, so an icon style built the conventional way would silently draw nothing; the
     * shapes are registered as SDF images instead, which is also what lets {@code icon-color} tint
     * them from the same classified expression the circle renderer uses.
     */
    private static Map<String, Object> pointLayer(String sourceId, Layer layer, Map<String, Object> symbol,
                                                  StyleType type, List<LayerStyleRule> rules,
                                                  Map<String, AttributeDataType> fieldTypes,
                                                  int minZoom, int maxZoom) {
        boolean icon = "icon".equals(string(SymbolKeys.RENDER_MODE, symbol, "circle"));
        Object colour = value(SymbolKeys.FILL_COLOR, symbol, "#B9C2D0", type, rules, fieldTypes);

        if (icon) {
            Map<String, Object> layout = new LinkedHashMap<>();
            layout.put("icon-image", "ag-" + string(SymbolKeys.ICON, symbol, "circle"));
            layout.put("icon-size", number(SymbolKeys.ICON_SIZE, symbol, 1.0));
            // A utility map shows every asset it was asked to show. Letting MapLibre drop colliding
            // icons would hide assets in exactly the dense areas an operator is investigating.
            layout.put("icon-allow-overlap", true);
            layout.put("icon-ignore-placement", true);
            Map<String, Object> paint = new LinkedHashMap<>();
            paint.put("icon-color", colour);
            paint.put("icon-opacity", number(SymbolKeys.OPACITY, symbol, 1.0));
            paint.put("icon-halo-color", string(SymbolKeys.STROKE_COLOR, symbol, "rgba(255,255,255,0.9)"));
            paint.put("icon-halo-width", number(SymbolKeys.STROKE_WIDTH, symbol, 1.5));
            return renderLayer(sourceId + "-point", "symbol", sourceId, layer.getCode(),
                    geometryFilter("Point"), layout, paint, minZoom, maxZoom);
        }

        Map<String, Object> paint = new LinkedHashMap<>();
        paint.put("circle-color", colour);
        paint.put("circle-radius", zoomRamp(number(SymbolKeys.SIZE, symbol, 5.0)));
        paint.put("circle-opacity", number(SymbolKeys.OPACITY, symbol, 1.0));
        paint.put("circle-stroke-width", number(SymbolKeys.STROKE_WIDTH, symbol, 1.5));
        paint.put("circle-stroke-color", string(SymbolKeys.STROKE_COLOR, symbol, "rgba(255,255,255,0.9)"));
        return renderLayer(sourceId + "-point", "circle", sourceId, layer.getCode(),
                geometryFilter("Point"), null, paint, minZoom, maxZoom);
    }

    /**
     * The label layer, or null when labels are off or no field is chosen.
     *
     * <p>Null rather than a layer with an empty {@code text-field}: MapLibre would happily add the
     * layer, allocate glyph atlases for it and place nothing, which costs work on every frame to
     * draw nothing at all.
     *
     * <p>The label's zoom window is its own, clamped inside the style's — a label that appeared at a
     * zoom the geometry it names does not is a label floating over empty space.
     */
    private static Map<String, Object> labelLayer(String sourceId, Layer layer, Map<String, Object> label,
                                                  Map<String, Object> symbol, StyleType type,
                                                  List<LayerStyleRule> rules,
                                                  Map<String, AttributeDataType> fieldTypes,
                                                  int minZoom, int maxZoom) {
        if (!bool(SymbolKeys.LABEL_ENABLED, label, false)) {
            return null;
        }
        String field = string(SymbolKeys.LABEL_FIELD, label, null);
        if (field == null || field.isBlank()) {
            return null;
        }

        int labelMin = clamp((int) number(SymbolKeys.LABEL_MIN_ZOOM, label, minZoom), minZoom, maxZoom);
        int labelMax = clamp((int) number(SymbolKeys.LABEL_MAX_ZOOM, label, maxZoom), labelMin, maxZoom);

        Map<String, Object> layout = new LinkedHashMap<>();
        /*
         * `to-string` around the property, not the property alone. `text-field` requires a string
         * and a numeric tile property (an asset number, a diameter) arrives as a number — MapLibre
         * rejects the layer outright rather than coercing, which takes the whole style down with it.
         */
        layout.put("text-field", List.of("to-string", List.of("get", field)));
        layout.put("text-font", LABEL_FONT);
        layout.put("text-size", number(SymbolKeys.LABEL_TEXT_SIZE, label, 11.0));
        layout.put("text-anchor", "top");
        // Offset below the symbol so the label does not sit on top of the asset it names.
        layout.put("text-offset", List.of(0, 0.8));
        layout.put("text-allow-overlap", false);
        // Dropping a colliding label is right where dropping a colliding asset was not: an unreadable
        // pile of overlapping text tells an operator less than a thinned-out set of legible ones.
        layout.put("text-optional", true);

        Map<String, Object> paint = new LinkedHashMap<>();
        paint.put("text-color", string(SymbolKeys.LABEL_TEXT_COLOR, label, "#E8EDF5"));
        paint.put("text-halo-color", string(SymbolKeys.LABEL_HALO_COLOR, label, "#05070D"));
        paint.put("text-halo-width", number(SymbolKeys.LABEL_HALO_WIDTH, label, 1.2));

        return renderLayer(sourceId + "-label", "symbol", sourceId, layer.getCode(),
                null, layout, paint, labelMin, labelMax);
    }

    private static Map<String, Object> renderLayer(String id, String type, String sourceId,
                                                   String sourceLayer, Object filter,
                                                   Map<String, Object> layout, Map<String, Object> paint,
                                                   int minZoom, int maxZoom) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("id", id);
        spec.put("type", type);
        spec.put("source", sourceId);
        spec.put("source-layer", sourceLayer);
        if (filter != null) {
            spec.put("filter", filter);
        }
        if (layout != null && !layout.isEmpty()) {
            spec.put("layout", layout);
        }
        spec.put("paint", paint);
        // MapLibre's own bounds. Emitting minzoom 0 / maxzoom 24 explicitly is harmless and makes
        // the served style self-describing rather than relying on the client's defaults.
        spec.put("minzoom", minZoom);
        spec.put("maxzoom", maxZoom);
        return spec;
    }

    // ---- Expressions ---------------------------------------------------------------------------

    /**
     * The value of one paint property: a literal for a simple style, an expression for a classified
     * one.
     *
     * <p>Only properties a rule actually overrides become expressions. A categorical style that
     * varies colour and nothing else yields one expression and five literals, rather than five
     * expressions the renderer has to evaluate per feature per frame to arrive at the same constant.
     */
    private static Object value(String key, Map<String, Object> base, Object fallback,
                                StyleType type, List<LayerStyleRule> rules,
                                Map<String, AttributeDataType> fieldTypes) {
        Object literal = base.containsKey(key) ? base.get(key) : fallback;
        if (!type.isClassified() || rules.isEmpty()) {
            return literal;
        }
        List<LayerStyleRule> overriding = rules.stream()
                .filter(r -> nullSafe(r.getSymbol()).containsKey(key))
                .toList();
        if (overriding.isEmpty()) {
            return literal;
        }
        return switch (type) {
            case CATEGORICAL -> matchExpression(overriding, key, literal, fieldTypes);
            case GRADUATED -> stepExpression(overriding, key, literal, fieldTypes);
            case RULE_BASED, SIMPLE -> caseExpression(overriding, key, literal, fieldTypes);
        };
    }

    /**
     * {@code ["match", ["get", field], label, output, …, fallback]} — exact-value dispatch.
     *
     * <p>Falls back to a {@code case} when any rule uses an operator {@code match} cannot express.
     * {@code match} compares for equality and nothing else, so a categorical style someone has
     * edited into using {@code >} would otherwise compose into an expression that throws in the tile
     * worker. Degrading to the general form is better than refusing to draw.
     */
    private static Object matchExpression(List<LayerStyleRule> rules, String key, Object fallback,
                                          Map<String, AttributeDataType> fieldTypes) {
        boolean expressible = rules.stream()
                .allMatch(r -> r.getOperator() == StyleOperator.EQ || r.getOperator() == StyleOperator.IN);
        if (!expressible) {
            return caseExpression(rules, key, fallback, fieldTypes);
        }

        String field = rules.get(0).getFieldName();
        // One `match` reads one input. Rules naming different fields cannot share it, and the
        // general form is the only honest translation.
        if (!rules.stream().allMatch(r -> field.equals(r.getFieldName()))) {
            return caseExpression(rules, key, fallback, fieldTypes);
        }

        List<Object> expression = new ArrayList<>();
        expression.add("match");
        expression.add(List.of("get", field));
        for (LayerStyleRule rule : rules) {
            Object labels = rule.getOperator() == StyleOperator.IN
                    ? rule.getValueList().stream().map(v -> operand(v, field, fieldTypes)).toList()
                    : operand(rule.getValue1(), field, fieldTypes);
            expression.add(labels);
            expression.add(nullSafe(rule.getSymbol()).get(key));
        }
        expression.add(fallback);
        return expression;
    }

    /**
     * {@code ["step", ["get", field], first, stop, output, …]} — ascending numeric bands.
     *
     * <p>{@code step} requires strictly ascending stops, so the bands are sorted by lower bound here
     * rather than trusted in entry order. The first band's symbol is also the output below the first
     * stop, and the last band's extends above its own upper bound: {@code step} has no notion of an
     * upper limit, and the alternative — leaving values above the top band unstyled — would mean the
     * highest readings, the ones an operator most needs to see, silently reverting to the base
     * colour.
     *
     * <p>Degrades to a {@code case} when the bands are not simple ascending {@code BETWEEN}s, for the
     * same reason {@code match} does.
     */
    private static Object stepExpression(List<LayerStyleRule> rules, String key, Object fallback,
                                         Map<String, AttributeDataType> fieldTypes) {
        String field = rules.get(0).getFieldName();
        boolean expressible = rules.stream().allMatch(r ->
                r.getOperator() == StyleOperator.BETWEEN
                        && field.equals(r.getFieldName())
                        && asNumber(r.getValue1()) != null);
        if (!expressible) {
            return caseExpression(rules, key, fallback, fieldTypes);
        }

        List<LayerStyleRule> sorted = rules.stream()
                .sorted(Comparator.comparingDouble(r -> asNumber(r.getValue1())))
                .toList();

        List<Object> expression = new ArrayList<>();
        expression.add("step");
        expression.add(List.of("get", field));
        expression.add(nullSafe(sorted.get(0).getSymbol()).get(key));
        double previousStop = Double.NEGATIVE_INFINITY;
        for (int i = 1; i < sorted.size(); i++) {
            double stop = asNumber(sorted.get(i).getValue1());
            // Duplicate or descending stops make MapLibre reject the whole expression. Nudging a
            // repeated bound keeps the style renderable; the band it creates is empty, which is what
            // an administrator who entered two bands starting at the same number asked for.
            if (stop <= previousStop) {
                stop = Math.nextUp(previousStop);
            }
            expression.add(stop);
            expression.add(nullSafe(sorted.get(i).getSymbol()).get(key));
            previousStop = stop;
        }
        return expression;
    }

    /** {@code ["case", condition, output, …, fallback]} — the general, first-match form. */
    private static Object caseExpression(List<LayerStyleRule> rules, String key, Object fallback,
                                         Map<String, AttributeDataType> fieldTypes) {
        List<Object> expression = new ArrayList<>();
        expression.add("case");
        for (LayerStyleRule rule : rules) {
            expression.add(condition(rule, fieldTypes));
            expression.add(nullSafe(rule.getSymbol()).get(key));
        }
        expression.add(fallback);
        return expression;
    }

    /** One rule as a MapLibre boolean expression. */
    private static Object condition(LayerStyleRule rule, Map<String, AttributeDataType> fieldTypes) {
        String field = rule.getFieldName();
        List<Object> get = List.of("get", field);
        return switch (rule.getOperator()) {
            case EQ -> List.of("==", get, operand(rule.getValue1(), field, fieldTypes));
            case NEQ -> List.of("!=", get, operand(rule.getValue1(), field, fieldTypes));
            case LT -> List.of("<", get, operand(rule.getValue1(), field, fieldTypes));
            case LTE -> List.of("<=", get, operand(rule.getValue1(), field, fieldTypes));
            case GT -> List.of(">", get, operand(rule.getValue1(), field, fieldTypes));
            case GTE -> List.of(">=", get, operand(rule.getValue1(), field, fieldTypes));
            case IN -> List.of("in", get,
                    List.of("literal", rule.getValueList() == null ? List.of()
                            : rule.getValueList().stream().map(v -> operand(v, field, fieldTypes)).toList()));
            case BETWEEN -> List.of("all",
                    List.of(">=", get, operand(rule.getValue1(), field, fieldTypes)),
                    List.of("<=", get, operand(rule.getValue2(), field, fieldTypes)));
            /*
             * Absence rather than a null comparison. ST_AsMVT omits a NULL property from the tile
             * entirely rather than encoding a null, so "is null" in the database is "has no such
             * property" in the tile — and MapLibre's `==` against null is not a comparison it
             * supports anyway. `has` is the exact translation, not an approximation.
             */
            case IS_NULL -> List.of("!", List.of("has", field));
            case IS_NOT_NULL -> List.of("has", field);
        };
    }

    /**
     * A rule's operand, typed from the attribute catalogue.
     *
     * <p>The tile carries a numeric attribute as a number (see {@code LayerTileRepository}), and
     * {@code ['==', ['get','diameter'], '150']} compares a number to a string: not an error, just a
     * comparison that is never true. The style then renders as though the rule were not there, which
     * is the hardest kind of bug to see. Reading the declared type from Data Management is what
     * closes that gap — and it is another place the module has one field list rather than two.
     */
    private static Object operand(String raw, String field, Map<String, AttributeDataType> fieldTypes) {
        if (raw == null) {
            return null;
        }
        AttributeDataType type = fieldTypes == null ? null : fieldTypes.get(field);
        if (type != null && type.isNumeric()) {
            Double number = asNumber(raw);
            if (number != null) {
                return number;
            }
        }
        if (type == AttributeDataType.BOOLEAN) {
            return Boolean.parseBoolean(raw) || "1".equals(raw) || "Y".equalsIgnoreCase(raw);
        }
        return raw;
    }

    // ---- Legend --------------------------------------------------------------------------------

    /**
     * The legend, derived from the same rules that produced the paint.
     *
     * <p>A by-product of the expression rather than a parallel description of it, so it cannot drift
     * from what is drawn — which is exactly what the client-side palette it replaces eventually did.
     */
    private static List<LegendEntry> legendFor(Layer layer, Map<String, Object> symbol,
                                               StyleType type, List<LayerStyleRule> rules) {
        String shape = shapeFor(layer, symbol);
        if (!type.isClassified() || rules.isEmpty()) {
            return List.of(new LegendEntry(layer.getTitle(),
                    string(SymbolKeys.FILL_COLOR, symbol,
                            string(SymbolKeys.LINE_COLOR, symbol, "#B9C2D0")),
                    shape));
        }
        List<LegendEntry> entries = new ArrayList<>();
        for (LayerStyleRule rule : rules) {
            Map<String, Object> ruleSymbol = nullSafe(rule.getSymbol());
            Object colour = ruleSymbol.getOrDefault(SymbolKeys.FILL_COLOR,
                    ruleSymbol.get(SymbolKeys.LINE_COLOR));
            if (colour == null) {
                continue;
            }
            entries.add(new LegendEntry(
                    rule.getLabel() != null ? rule.getLabel() : describe(rule),
                    colour.toString(), shape));
        }
        // Unclassified features still draw, in the base colour, so the legend has to say so — a
        // legend that lists only the classes leaves an operator with a colour on the map and no
        // entry to look it up by.
        entries.add(new LegendEntry("Other",
                string(SymbolKeys.FILL_COLOR, symbol,
                        string(SymbolKeys.LINE_COLOR, symbol, "#B9C2D0")), shape));
        return entries;
    }

    /** {@code diameter BETWEEN 100 AND 200} — the legend caption for a rule with no label. */
    private static String describe(LayerStyleRule rule) {
        StyleOperator op = rule.getOperator();
        return switch (op.arity()) {
            case NONE -> rule.getFieldName() + " " + op.symbol();
            case ONE -> rule.getFieldName() + " " + op.symbol() + " " + rule.getValue1();
            case TWO -> rule.getFieldName() + " " + op.symbol() + " " + rule.getValue1()
                        + " – " + rule.getValue2();
            case LIST -> rule.getFieldName() + " " + op.symbol() + " ("
                         + String.join(", ", rule.getValueList() == null ? List.of() : rule.getValueList())
                         + ")";
        };
    }

    /** The legend swatch's shape, so it matches what the map paints rather than merely labelling it. */
    private static String shapeFor(Layer layer, Map<String, Object> symbol) {
        if ("icon".equals(string(SymbolKeys.RENDER_MODE, symbol, "circle"))) {
            return string(SymbolKeys.ICON, symbol, "circle");
        }
        return switch (layer.getGeometryType().family()) {
            case LINE -> "line";
            case POLYGON -> "fill";
            case POINT, ANY -> "circle";
        };
    }

    // ---- Helpers -------------------------------------------------------------------------------

    /**
     * The catalogue fields the composed expressions read.
     *
     * <p>Handed back so the tile endpoint knows which attributes to put in the tile and the client
     * knows which ones matter, without either inferring it from the other — the inference being
     * exactly where a style that reads {@code water_level} and a tile that does not carry it would
     * quietly diverge, producing a map that draws every feature in the fallback colour and reports
     * nothing wrong.
     */
    private static List<String> styledFieldNames(LayerStyle style, List<LayerStyleRule> rules) {
        List<String> fields = new ArrayList<>();
        if (style != null) {
            if (style.getClassifyField() != null) {
                fields.add(style.getClassifyField());
            }
            Object labelField = nullSafe(style.getLabel()).get(SymbolKeys.LABEL_FIELD);
            if (labelField != null && !labelField.toString().isBlank()) {
                fields.add(labelField.toString());
            }
        }
        rules.forEach(rule -> fields.add(rule.getFieldName()));
        return fields.stream().filter(Objects::nonNull).distinct().toList();
    }

    /**
     * A width or radius that grows with zoom, anchored on the configured value at z14.
     *
     * <p>A fixed pixel width is wrong at both ends of the range: legible at street scale is a smear
     * at district scale, and vice versa. The ramp is the same shape the hard-coded client renderer
     * used, now derived from the configured value rather than from three constants.
     */
    private static List<Object> zoomRamp(double atZoom14) {
        return List.of("interpolate", List.of("linear"), List.of("zoom"),
                8, round(atZoom14 * 0.45),
                14, round(atZoom14),
                18, round(atZoom14 * 1.9));
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static Object geometryFilter(String geometryType) {
        return List.of("==", List.of("geometry-type"), geometryType);
    }

    /**
     * Lines are drawn for polygons too, which is how a boundary gets a crisp edge over a soft fill.
     * MapLibre draws a {@code line} layer along a polygon's rings, so this is one filter rather than
     * a second layer.
     */
    private static Object lineOrPolygonFilter() {
        return List.of("any", geometryFilter("LineString"), geometryFilter("Polygon"));
    }

    private static List<Double> dashPattern(Map<String, Object> symbol) {
        Object raw = symbol.get(SymbolKeys.DASH_PATTERN);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        List<Double> dash = list.stream()
                .filter(Number.class::isInstance)
                .map(v -> ((Number) v).doubleValue())
                .filter(v -> v > 0)
                .toList();
        // A dash array of one element, or of zeros, is a line MapLibre refuses to draw at all.
        return dash.size() >= 2 ? dash : null;
    }

    private static int effectiveMin(Layer layer, LayerStyle style) {
        return style == null ? layer.getMinZoom() : Math.max(layer.getMinZoom(), style.getMinZoom());
    }

    private static int effectiveMax(Layer layer, LayerStyle style) {
        int max = style == null ? layer.getMaxZoom() : Math.min(layer.getMaxZoom(), style.getMaxZoom());
        // A window the style narrowed past the layer's floor would be empty; the layer's own range
        // wins, because the layer is the thing that exists.
        return Math.max(max, effectiveMin(layer, style));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nullSafe(Map<String, Object> map) {
        return map == null ? (Map<String, Object>) (Map<?, ?>) Map.of() : map;
    }

    private static String string(String key, Map<String, Object> source, String fallback) {
        Object value = source.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private static double number(String key, Map<String, Object> source, double fallback) {
        Object value = source.get(key);
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        Double parsed = value == null ? null : asNumber(value.toString());
        return parsed == null ? fallback : parsed;
    }

    private static boolean bool(String key, Map<String, Object> source, boolean fallback) {
        Object value = source.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private static Double asNumber(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Double.valueOf(raw.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}

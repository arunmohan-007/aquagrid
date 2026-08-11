package com.aquagrid.platform.gis.domain.style;

import com.aquagrid.platform.gis.domain.enums.GeometryType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AquaGrid's symbology vocabulary — the keys a stored style document may contain.
 *
 * <p>Deliberately <em>not</em> MapLibre's spelling. Storing raw MapLibre paint would weld the
 * database to one renderer, and — worse — would let the style editor persist arbitrary expressions
 * that nothing validated, so a malformed one would surface as a thrown exception inside the tile
 * worker and a blank map. This vocabulary is small, flat, typed and closed;
 * {@link MapLibreStyleComposer} is the single place that translates it, so swapping renderers is a
 * composer rather than a data migration.
 *
 * <p>All three geometry families' keys live in one document rather than three, because a
 * {@link GeometryType#GEOMETRY} layer genuinely carries all three at once — the facility layers hold
 * footprints and locations together, depending on how each project was surveyed, and a style able to
 * describe only one of them would leave the other unpainted.
 */
public final class SymbolKeys {

    // ---- Point ---------------------------------------------------------------------------------
    /** {@code circle} or {@code icon}. Which of the two point families the composer emits. */
    public static final String RENDER_MODE = "renderMode";
    public static final String SIZE = "size";
    public static final String FILL_COLOR = "fillColor";
    public static final String STROKE_COLOR = "strokeColor";
    public static final String STROKE_WIDTH = "strokeWidth";
    public static final String OPACITY = "opacity";
    /**
     * The soft halo colour beneath a point.
     *
     * <p>Carried over from the client palette it replaces, where it earned its keep: a point in one
     * hard colour is legible on dark cartography or on bright satellite imagery, rarely both, and a
     * low-opacity blurred halo buys the second without spending a second hard colour on it.
     */
    public static final String GLOW_COLOR = "glowColor";
    /** Sprite name for {@code renderMode: icon}. */
    public static final String ICON = "icon";
    public static final String ICON_SIZE = "iconSize";

    // ---- Line ----------------------------------------------------------------------------------
    public static final String LINE_COLOR = "lineColor";
    public static final String LINE_WIDTH = "lineWidth";
    public static final String LINE_OPACITY = "lineOpacity";
    /** Dash array in line widths, e.g. {@code [2, 1.5]}. Empty or absent means a solid line. */
    public static final String DASH_PATTERN = "dashPattern";
    /** {@code butt}, {@code round} or {@code square}. */
    public static final String LINE_CAP = "lineCap";
    /** {@code bevel}, {@code round} or {@code miter}. */
    public static final String LINE_JOIN = "lineJoin";

    // ---- Polygon -------------------------------------------------------------------------------
    public static final String FILL_OPACITY = "fillOpacity";
    public static final String OUTLINE_COLOR = "outlineColor";
    public static final String OUTLINE_WIDTH = "outlineWidth";
    public static final String OUTLINE_OPACITY = "outlineOpacity";

    // ---- Labels --------------------------------------------------------------------------------
    public static final String LABEL_ENABLED = "enabled";
    /** The Data Management field whose value is drawn. Validated against the attribute catalogue. */
    public static final String LABEL_FIELD = "field";
    public static final String LABEL_TEXT_SIZE = "textSize";
    public static final String LABEL_TEXT_COLOR = "textColor";
    public static final String LABEL_HALO_COLOR = "haloColor";
    public static final String LABEL_HALO_WIDTH = "haloWidth";
    public static final String LABEL_MIN_ZOOM = "minZoom";
    public static final String LABEL_MAX_ZOOM = "maxZoom";

    private static final Set<String> POINT_KEYS = Set.of(
            RENDER_MODE, SIZE, FILL_COLOR, STROKE_COLOR, STROKE_WIDTH, OPACITY, GLOW_COLOR,
            ICON, ICON_SIZE);
    private static final Set<String> LINE_KEYS = Set.of(
            LINE_COLOR, LINE_WIDTH, LINE_OPACITY, DASH_PATTERN, LINE_CAP, LINE_JOIN);
    private static final Set<String> POLYGON_KEYS = Set.of(
            FILL_COLOR, FILL_OPACITY, OUTLINE_COLOR, OUTLINE_WIDTH, OUTLINE_OPACITY, DASH_PATTERN);

    private static final Set<String> LABEL_KEYS = Set.of(
            LABEL_ENABLED, LABEL_FIELD, LABEL_TEXT_SIZE, LABEL_TEXT_COLOR, LABEL_HALO_COLOR,
            LABEL_HALO_WIDTH, LABEL_MIN_ZOOM, LABEL_MAX_ZOOM);

    private SymbolKeys() {
    }

    /** Every symbol key, across all three geometry families. */
    public static Set<String> all() {
        return Set.copyOf(concat(POINT_KEYS, LINE_KEYS, POLYGON_KEYS));
    }

    public static Set<String> labelKeys() {
        return LABEL_KEYS;
    }

    /**
     * The keys that mean something for a layer of this geometry.
     *
     * <p>Drives the style editor's form — which is the point of serving it rather than hard-coding a
     * parallel list in the client, the same rule the device registration form follows for transports.
     * A {@link GeometryType#GEOMETRY} layer gets all of them, because it may hold all of them.
     */
    public static Set<String> forGeometry(GeometryType geometryType) {
        return switch (geometryType.family()) {
            case POINT -> POINT_KEYS;
            case LINE -> LINE_KEYS;
            case POLYGON -> POLYGON_KEYS;
            case ANY -> all();
        };
    }

    /** Keys whose value is a colour, for the editor and for validation. */
    public static Set<String> colourKeys() {
        return Set.of(FILL_COLOR, STROKE_COLOR, GLOW_COLOR, LINE_COLOR, OUTLINE_COLOR,
                LABEL_TEXT_COLOR, LABEL_HALO_COLOR);
    }

    /** Keys whose value is a 0–1 opacity. Anything outside that range is a mistake worth refusing. */
    public static Set<String> opacityKeys() {
        return Set.of(OPACITY, LINE_OPACITY, FILL_OPACITY, OUTLINE_OPACITY);
    }

    /** Keys whose value is a non-negative size in pixels. */
    public static Set<String> sizeKeys() {
        return Set.of(SIZE, STROKE_WIDTH, ICON_SIZE, LINE_WIDTH, OUTLINE_WIDTH,
                LABEL_TEXT_SIZE, LABEL_HALO_WIDTH);
    }

    /** Enumerated keys and their permitted values, so neither the form nor the server invents one. */
    public static Map<String, List<String>> enumeratedKeys() {
        return Map.of(
                RENDER_MODE, List.of("circle", "icon"),
                LINE_CAP, List.of("butt", "round", "square"),
                LINE_JOIN, List.of("bevel", "round", "miter"));
    }

    private static Set<String> concat(Set<String> a, Set<String> b, Set<String> c) {
        java.util.Set<String> out = new java.util.HashSet<>(a);
        out.addAll(b);
        out.addAll(c);
        return out;
    }
}

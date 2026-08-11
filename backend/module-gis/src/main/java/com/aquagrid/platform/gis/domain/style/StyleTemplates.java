package com.aquagrid.platform.gis.domain.style;

import com.aquagrid.platform.gis.domain.enums.GeometryType;
import com.aquagrid.platform.gis.domain.enums.StyleOperator;
import com.aquagrid.platform.gis.domain.enums.StyleType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Starting points for a new style.
 *
 * <p>A blank style editor asks an administrator to choose a fill colour, a stroke colour, a stroke
 * width, an opacity, a halo and a zoom range before they can see anything — for a decision they
 * usually describe as "like the mains, but red". Templates are that sentence: a named, complete,
 * immediately-renderable symbol they can adjust rather than assemble.
 *
 * <p>Served rather than hard-coded in the client, for the reason the whole module exists: a template
 * is only useful if it produces a style the server would accept, and the only way to guarantee that
 * is for the two to come from the same place. Every symbol below goes through
 * {@code LayerStyleService.validatedSymbol} on save exactly as a hand-built one does.
 *
 * <p>These are seeds, not a palette consulted at render time. Once applied, the values are the
 * administrator's and nothing here is read again — which is what makes a hard-coded list acceptable
 * here and not in {@code layerStyle.ts}, where it was a lookup no administrator could reach.
 *
 * <p>Saturated red, amber and orange appear only where the platform already reserves them: alarm and
 * fault classes. A decorative template must not spend them, or an operator loses the one thing
 * colour has to carry.
 */
public final class StyleTemplates {

    private StyleTemplates() {
    }

    /**
     * One template.
     *
     * @param families    which geometry families it suits. A template is offered when the layer's
     *                    family is listed, or when the layer is {@code ANY} — a mixed-geometry layer
     *                    can use any of them, because it may hold any of them.
     * @param ruleSeeds   for classified templates: the classes to create once the administrator has
     *                    chosen a field. Empty for single-symbol templates. Values are suggestions
     *                    the editor pre-fills, not constraints — a tenant whose fault state is
     *                    spelt {@code FAULTY} edits one box rather than starting over.
     * @param suggestedField the field to classify on, pre-selected if the layer's catalogue has it.
     *                    Null when the template does not classify.
     * @param labelField  the field a labelling template wants drawn, again only if the catalogue has
     *                    it. Separate from {@code suggestedField} because a style can classify on one
     *                    field and label with another, and because a template that conflated them
     *                    would have to guess which one an administrator meant.
     *
     *                    <p>Every template's {@code label} ships with {@code enabled: false}, even
     *                    the labelling one. A template must be savable exactly as it arrives — that
     *                    is the property that makes it a starting point rather than a form with a
     *                    hidden error in it — and labels enabled without a field is the one thing
     *                    {@code LayerStyleService} refuses outright. The client switches them on when
     *                    it has resolved {@code labelField} against the layer's catalogue, and leaves
     *                    them off, with the appearance pre-filled, when it cannot.
     */
    public record Template(
            String id,
            String name,
            String description,
            List<GeometryType.Family> families,
            StyleType styleType,
            String suggestedField,
            String labelField,
            Map<String, Object> symbol,
            Map<String, Object> label,
            List<RuleSeed> ruleSeeds
    ) {
    }

    /** One class of a classified template, before a field is chosen for it. */
    public record RuleSeed(String label, StyleOperator operator, String value, Map<String, Object> symbol) {
    }

    /**
     * Templates applicable to a layer's geometry.
     *
     * @param geometryType the layer's declared geometry. A {@code GEOMETRY} layer gets everything,
     *                     because it may genuinely hold points, lines and polygons at once — the
     *                     facility layers do.
     */
    public static List<Template> forGeometry(GeometryType geometryType) {
        GeometryType.Family family = geometryType.family();
        return all().stream()
                .filter(t -> family == GeometryType.Family.ANY || t.families().contains(family))
                .toList();
    }

    public static List<Template> all() {
        return List.of(
                // ---- Point ---------------------------------------------------------------------
                template("point-operational", "Operational point",
                        "The platform's standard point: a solid dot with a white stroke and a soft "
                                + "halo, legible on dark cartography and on satellite imagery alike.",
                        List.of(GeometryType.Family.POINT), StyleType.SIMPLE, null,
                        point("#3B82F6", "#93C5FD", 5, 1.5)),

                template("point-small", "Fine dot",
                        "A smaller, quieter point for a dense layer — service connections and "
                                + "household meters, where the standard size becomes a solid mass.",
                        List.of(GeometryType.Family.POINT), StyleType.SIMPLE, null,
                        point("#14B8A6", "#5EEAD4", 2.5, 1.0)),

                template("point-emphasis", "Emphasised point",
                        "Larger, with a heavier halo. For the handful of assets an operator is "
                                + "actually sent to — pump houses, bulk meters.",
                        List.of(GeometryType.Family.POINT), StyleType.SIMPLE, null,
                        point("#6366F1", "#A5B4FC", 9, 2.5)),

                template("point-icon", "Icon marker",
                        "A shaped marker rather than a circle. The shape is drawn by the map at "
                                + "runtime, so it needs no sprite sheet and still takes its colour "
                                + "from the style — including a classified one.",
                        List.of(GeometryType.Family.POINT), StyleType.SIMPLE, null,
                        icon("diamond", "#8B5CF6", 1.0)),

                // ---- Line ----------------------------------------------------------------------
                template("line-distribution", "Distribution main",
                        "A thin cyan line over a dark casing — the everyday network line, and what "
                                + "the pipe layer already looks like.",
                        List.of(GeometryType.Family.LINE), StyleType.SIMPLE, null,
                        line("#06B6D4", 3, null)),

                template("line-transmission", "Transmission main",
                        "Heavier, for trunk mains that should read above the distribution network "
                                + "at district zoom.",
                        List.of(GeometryType.Family.LINE), StyleType.SIMPLE, null,
                        line("#0EA5E9", 6, null)),

                template("line-proposed", "Proposed / abandoned",
                        "Dashed. The convention every utility drawing already uses for a reach that "
                                + "is planned, disused or unverified, so it needs no legend entry to "
                                + "be understood.",
                        List.of(GeometryType.Family.LINE), StyleType.SIMPLE, null,
                        line("#94A3B8", 2.5, List.of(2.0, 1.5))),

                // ---- Polygon -------------------------------------------------------------------
                template("polygon-zone", "Zone boundary",
                        "A soft fill under a crisp outline, so the network inside the zone stays "
                                + "visible. The right default for DMAs and panchayat boundaries.",
                        List.of(GeometryType.Family.POLYGON), StyleType.SIMPLE, null,
                        polygon("#64748B", "#94A3B8", 0.14, 1.5, null)),

                template("polygon-outline", "Outline only",
                        "No fill at all. For a boundary layer drawn over imagery, where any fill "
                                + "obscures the thing the operator opened the imagery to see.",
                        List.of(GeometryType.Family.POLYGON), StyleType.SIMPLE, null,
                        polygon("#8B5CF6", "#C4B5FD", 0.0, 2.5, null)),

                template("polygon-hatched", "Dashed boundary",
                        "A dashed outline over a faint fill — for an administrative area that is "
                                + "context rather than an operational feature.",
                        List.of(GeometryType.Family.POLYGON), StyleType.SIMPLE, null,
                        polygon("#0EA5E9", "#7DD3FC", 0.08, 2.0, List.of(3.0, 2.0))),

                // ---- Classified ----------------------------------------------------------------
                /*
                 * These carry rule seeds rather than finished rules: the field is the administrator's
                 * to choose, because a layer's condition field might be `status`, `condition` or
                 * `asset_state` depending on whose survey it came from. The editor pre-selects
                 * `suggestedField` when the layer's Data Management catalogue actually has it, and
                 * asks otherwise — it never invents a field, which is the rule the whole module runs on.
                 */
                new Template("status-condition", "By status",
                        "Green, grey and red by a status field — the classification most operational "
                                + "layers end up wanting. Red is spent here deliberately: this is the "
                                + "fault state, which is what the platform reserves red for.",
                        List.of(GeometryType.Family.POINT, GeometryType.Family.LINE,
                                GeometryType.Family.POLYGON),
                        StyleType.CATEGORICAL, "status", null,
                        neutralBase(), labelsOff(),
                        List.of(
                                new RuleSeed("In service", StyleOperator.EQ, "IN_SERVICE", colour("#14B8A6")),
                                new RuleSeed("Out of service", StyleOperator.EQ, "OUT_OF_SERVICE", colour("#64748B")),
                                new RuleSeed("Damaged", StyleOperator.EQ, "DAMAGED", colour("#EF4444")))),

                new Template("graduated-quartiles", "By range",
                        "Four ascending bands over a numeric field — diameter, water level, pressure. "
                                + "Use 'From data' in the rule builder to set the bounds from what "
                                + "the layer actually holds rather than from a guess.",
                        List.of(GeometryType.Family.POINT, GeometryType.Family.LINE,
                                GeometryType.Family.POLYGON),
                        StyleType.GRADUATED, null, null,
                        neutralBase(), labelsOff(),
                        List.of(
                                new RuleSeed("Lowest", StyleOperator.BETWEEN, "0", colour("#0EA5E9")),
                                new RuleSeed("Low", StyleOperator.BETWEEN, "25", colour("#06B6D4")),
                                new RuleSeed("High", StyleOperator.BETWEEN, "50", colour("#14B8A6")),
                                new RuleSeed("Highest", StyleOperator.BETWEEN, "75", colour("#8B5CF6")))),

                new Template("labelled", "Labelled",
                        "The standard symbol with label styling ready — size, colour, halo and a "
                                + "sensible zoom floor. Labels switch on as soon as a field is chosen; "
                                + "the layer's own catalogue says which fields there are.",
                        List.of(GeometryType.Family.POINT, GeometryType.Family.LINE,
                                GeometryType.Family.POLYGON),
                        StyleType.SIMPLE, null, "name",
                        point("#3B82F6", "#93C5FD", 5, 1.5),
                        labelAppearance(), List.of()));
    }

    // ---- Symbol builders ---------------------------------------------------------------------

    private static Template template(String id, String name, String description,
                                     List<GeometryType.Family> families, StyleType styleType,
                                     String suggestedField, Map<String, Object> symbol) {
        return new Template(id, name, description, families, styleType, suggestedField, null,
                symbol, labelsOff(), List.of());
    }

    private static Template template(String id, String name, String description,
                                     List<GeometryType.Family> families, StyleType styleType,
                                     String suggestedField, Map<String, Object> symbol,
                                     Map<String, Object> label, List<RuleSeed> seeds) {
        return new Template(id, name, description, families, styleType, suggestedField, null,
                symbol, label, seeds);
    }

    private static Map<String, Object> point(String colour, String glow, double size, double stroke) {
        Map<String, Object> symbol = base(colour, glow);
        symbol.put(SymbolKeys.RENDER_MODE, "circle");
        symbol.put(SymbolKeys.SIZE, size);
        symbol.put(SymbolKeys.STROKE_WIDTH, stroke);
        return symbol;
    }

    private static Map<String, Object> icon(String shape, String colour, double iconSize) {
        Map<String, Object> symbol = base(colour, colour);
        symbol.put(SymbolKeys.RENDER_MODE, "icon");
        symbol.put(SymbolKeys.ICON, shape);
        symbol.put(SymbolKeys.ICON_SIZE, iconSize);
        return symbol;
    }

    private static Map<String, Object> line(String colour, double width, List<Double> dash) {
        Map<String, Object> symbol = base(colour, colour);
        symbol.put(SymbolKeys.LINE_WIDTH, width);
        if (dash != null) {
            symbol.put(SymbolKeys.DASH_PATTERN, dash);
        }
        return symbol;
    }

    private static Map<String, Object> polygon(String colour, String outline, double fillOpacity,
                                               double outlineWidth, List<Double> dash) {
        Map<String, Object> symbol = base(colour, outline);
        symbol.put(SymbolKeys.FILL_OPACITY, fillOpacity);
        symbol.put(SymbolKeys.OUTLINE_COLOR, outline);
        symbol.put(SymbolKeys.OUTLINE_WIDTH, outlineWidth);
        if (dash != null) {
            symbol.put(SymbolKeys.DASH_PATTERN, dash);
        }
        return symbol;
    }

    /**
     * Every key a symbol can carry, filled with sensible values.
     *
     * <p>Complete rather than minimal on purpose: applying a template must leave the editor showing
     * a value in every box, so an administrator can see and adjust the whole symbol instead of
     * discovering that half of it is an unstated default the renderer supplied.
     */
    private static Map<String, Object> base(String colour, String glow) {
        Map<String, Object> symbol = new LinkedHashMap<>();
        symbol.put(SymbolKeys.RENDER_MODE, "circle");
        symbol.put(SymbolKeys.FILL_COLOR, colour);
        symbol.put(SymbolKeys.GLOW_COLOR, glow);
        symbol.put(SymbolKeys.STROKE_COLOR, "rgba(255,255,255,0.9)");
        symbol.put(SymbolKeys.STROKE_WIDTH, 1.5);
        symbol.put(SymbolKeys.SIZE, 5);
        symbol.put(SymbolKeys.OPACITY, 1.0);
        symbol.put(SymbolKeys.ICON, "circle");
        symbol.put(SymbolKeys.ICON_SIZE, 1.0);
        symbol.put(SymbolKeys.LINE_COLOR, colour);
        symbol.put(SymbolKeys.LINE_WIDTH, 3);
        symbol.put(SymbolKeys.LINE_OPACITY, 1.0);
        symbol.put(SymbolKeys.LINE_CAP, "round");
        symbol.put(SymbolKeys.LINE_JOIN, "round");
        symbol.put(SymbolKeys.FILL_OPACITY, 0.14);
        symbol.put(SymbolKeys.OUTLINE_COLOR, glow);
        symbol.put(SymbolKeys.OUTLINE_WIDTH, 1.5);
        symbol.put(SymbolKeys.OUTLINE_OPACITY, 1.0);
        return symbol;
    }

    private static Map<String, Object> colour(String fill) {
        Map<String, Object> symbol = new LinkedHashMap<>();
        // A class overrides colour and nothing else, so a later change to the base width or size
        // reaches every class rather than only the ones that happened not to restate it.
        symbol.put(SymbolKeys.FILL_COLOR, fill);
        symbol.put(SymbolKeys.LINE_COLOR, fill);
        return symbol;
    }

    private static Map<String, Object> neutralBase() {
        return base("#B9C2D0", "#CBD5E1");
    }

    private static Map<String, Object> labelsOff() {
        Map<String, Object> label = new LinkedHashMap<>();
        label.put(SymbolKeys.LABEL_ENABLED, false);
        return label;
    }

    /**
     * Label appearance, with labels still off.
     *
     * <p>Off, deliberately. A template has to be savable exactly as it arrives, and "labels enabled,
     * no field" is the single combination {@code LayerStyleService} refuses — so shipping it enabled
     * would make the one template about labels the one template that cannot be saved. The appearance
     * is filled in regardless, so switching them on is one click rather than six decisions.
     */
    private static Map<String, Object> labelAppearance() {
        Map<String, Object> label = new LinkedHashMap<>();
        label.put(SymbolKeys.LABEL_ENABLED, false);
        label.put(SymbolKeys.LABEL_TEXT_SIZE, 11);
        label.put(SymbolKeys.LABEL_TEXT_COLOR, "#E8EDF5");
        label.put(SymbolKeys.LABEL_HALO_COLOR, "#05070D");
        label.put(SymbolKeys.LABEL_HALO_WIDTH, 1.2);
        // From z14: a label at district zoom is a label on top of every other label.
        label.put(SymbolKeys.LABEL_MIN_ZOOM, 14);
        label.put(SymbolKeys.LABEL_MAX_ZOOM, 24);
        return label;
    }
}

package com.aquagrid.platform.gis.web.dto;

import com.aquagrid.platform.gis.application.service.LayerStyleService;
import com.aquagrid.platform.gis.domain.enums.GeometryType;
import com.aquagrid.platform.gis.domain.enums.StyleOperator;
import com.aquagrid.platform.gis.domain.enums.StyleType;
import com.aquagrid.platform.gis.domain.model.LayerStyle;
import com.aquagrid.platform.gis.domain.model.LayerStyleRule;
import com.aquagrid.platform.gis.domain.style.MapLibreStyleComposer;
import com.aquagrid.platform.gis.domain.style.StyleTemplates;
import com.aquagrid.platform.gis.domain.style.SymbolLibrary;
import com.aquagrid.platform.gis.domain.style.SymbolKeys;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request and response shapes for the Layer Style Management API. */
public final class StyleDtos {

    private StyleDtos() {
    }

    // ---- Responses -----------------------------------------------------------------------------

    @Schema(name = "LayerStyle", description = "One way of drawing a layer, with its rules")
    public record StyleResponse(
            UUID id,
            UUID layerId,
            String name,
            String description,
            String styleType,
            String classifyField,
            boolean active,
            boolean defaultStyle,
            int minZoom,
            int maxZoom,
            @Schema(description = "AquaGrid's symbology vocabulary, not raw MapLibre paint")
            Map<String, Object> symbol,
            Map<String, Object> label,
            List<RuleResponse> rules,
            UUID createdBy,
            Instant createdDate,
            UUID modifiedBy,
            Instant modifiedDate
    ) {
        public static StyleResponse from(LayerStyleService.StyleDetail detail) {
            LayerStyle s = detail.style();
            return new StyleResponse(s.getId(), s.getLayerId(), s.getName(), s.getDescription(),
                    s.getStyleType().name(), s.getClassifyField(), s.isActive(), s.isDefaultStyle(),
                    s.getMinZoom(), s.getMaxZoom(), s.getSymbol(), s.getLabel(),
                    detail.rules().stream().map(RuleResponse::from).toList(),
                    s.getCreatedBy(), s.getCreatedAt(), s.getUpdatedBy(), s.getUpdatedAt());
        }
    }

    @Schema(name = "LayerStyleRule")
    public record RuleResponse(
            UUID id,
            String fieldName,
            String operator,
            String value1,
            String value2,
            List<String> valueList,
            String label,
            Map<String, Object> symbol,
            int sortOrder,
            boolean active
    ) {
        public static RuleResponse from(LayerStyleRule r) {
            return new RuleResponse(r.getId(), r.getFieldName(), r.getOperator().name(),
                    r.getValue1(), r.getValue2(), r.getValueList(), r.getLabel(), r.getSymbol(),
                    r.getSortOrder(), r.isActive());
        }
    }

    /**
     * A layer's complete rendering instruction, ready for MapLibre.
     *
     * <p>The client adds {@code source} under {@code sourceId} and then adds every entry of
     * {@code layers} verbatim. It makes no decision about appearance, which is the whole point of
     * composing server-side: a new layer or a recoloured one is a database change, not a release.
     */
    @Schema(name = "ComposedMapLayer",
            description = "MapLibre source and layer specifications, composed from the layer's style")
    public record ComposedLayerResponse(
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
            List<LegendResponse> legend,
            @Schema(description = "Catalogue fields the expressions read, and which the tile carries")
            List<String> styledFields,
            @Schema(description = "Library or uploaded icon ids this layer needs registered before "
                    + "it draws, e.g. lib-water, sym-<uuid>")
            List<String> requiredIcons
    ) {
        public static ComposedLayerResponse from(MapLibreStyleComposer.ComposedLayer c) {
            return new ComposedLayerResponse(c.layerId(), c.code(), c.title(), c.category(),
                    c.sourceId(), c.source(), c.sourceLayer(), c.visibleByDefault(), c.queryable(),
                    c.minZoom(), c.maxZoom(), c.styleId(), c.styleName(), c.layers(),
                    c.legend().stream().map(LegendResponse::from).toList(),
                    c.styledFields(), c.requiredIcons());
        }
    }

    @Schema(name = "LegendEntry")
    public record LegendResponse(String label, String colour, String shape) {
        public static LegendResponse from(MapLibreStyleComposer.LegendEntry e) {
            return new LegendResponse(e.label(), e.colour(), e.shape());
        }
    }

    /**
     * The style editor's vocabulary, served rather than hard-coded in the client.
     *
     * <p>The same rule the device registration form follows for transports: the form renders the
     * fields the server validates against, so it can never offer a value the server would reject.
     */
    @Schema(name = "StyleVocabulary")
    public record VocabularyResponse(
            List<OptionResponse> styleTypes,
            List<OperatorResponse> operators,
            @Schema(description = "Symbol keys grouped by the geometry family they apply to")
            Map<String, List<String>> symbolKeys,
            List<String> labelKeys,
            Map<String, List<String>> enumeratedKeys,
            @Schema(description = "Built-in shapes the client draws at runtime with no download — "
                    + "circle, square, diamond and the like. Registered as SDF images, so a "
                    + "classified colour tints them.")
            List<String> icons,
            @Schema(description = "The free, open-licensed icon library (Mapbox Maki — CC0 — and "
                    + "Google Material Symbols — Apache-2.0), vendored so it needs no key and no "
                    + "network access to render. Also SDF, also tintable.")
            List<LibrarySymbolResponse> libraryIcons
    ) {
        public static VocabularyResponse build(List<SymbolLibrary.LibrarySymbol> library) {
            return new VocabularyResponse(
                    java.util.Arrays.stream(StyleType.values())
                            .map(t -> new OptionResponse(t.name(), styleTypeLabel(t), styleTypeHint(t)))
                            .toList(),
                    java.util.Arrays.stream(StyleOperator.values())
                            .map(o -> new OperatorResponse(o.name(), o.symbol(), o.arity().name(), o.isOrdered()))
                            .toList(),
                    Map.of(
                            "POINT", List.copyOf(SymbolKeys.forGeometry(GeometryType.POINT)),
                            "LINE", List.copyOf(SymbolKeys.forGeometry(GeometryType.LINESTRING)),
                            "POLYGON", List.copyOf(SymbolKeys.forGeometry(GeometryType.POLYGON)),
                            "ANY", List.copyOf(SymbolKeys.all())),
                    List.copyOf(SymbolKeys.labelKeys()),
                    SymbolKeys.enumeratedKeys(),
                    List.of("circle", "square", "diamond", "triangle", "hexagon", "star", "pin"),
                    library.stream()
                            .sorted(java.util.Comparator.comparing(SymbolLibrary.LibrarySymbol::set)
                                    .thenComparing(SymbolLibrary.LibrarySymbol::name))
                            .map(LibrarySymbolResponse::from)
                            .toList());
        }

        private static String styleTypeLabel(StyleType type) {
            return switch (type) {
                case SIMPLE -> "Single symbol";
                case CATEGORICAL -> "By category";
                case GRADUATED -> "By range";
                case RULE_BASED -> "By rule";
            };
        }

        private static String styleTypeHint(StyleType type) {
            return switch (type) {
                case SIMPLE -> "One symbol for every feature.";
                case CATEGORICAL -> "A symbol per value of a field — status ACTIVE green, FAULT red.";
                case GRADUATED -> "A symbol per numeric band — water level 0–20, 20–50, 50–100.";
                case RULE_BASED -> "Your own conditions, first match wins.";
            };
        }
    }

    @Schema(name = "StyleOption")
    public record OptionResponse(String value, String label, String hint) {
    }

    /**
     * One starting point for a new style.
     *
     * <p>Served rather than kept in the client so that a template can only ever produce a style the
     * server would accept — the two come from the same definitions, and the symbol here goes through
     * the same validation on save as a hand-built one.
     */
    @Schema(name = "StyleTemplate")
    public record TemplateResponse(
            String id,
            String name,
            String description,
            List<String> families,
            String styleType,
            @Schema(description = "Field to classify on, pre-selected if the layer's catalogue has "
                    + "it. Never invented.")
            String suggestedField,
            @Schema(description = "Field a labelling template wants drawn. Labels ship off; the "
                    + "client switches them on once it has resolved this against the catalogue.")
            String labelField,
            Map<String, Object> symbol,
            Map<String, Object> label,
            List<TemplateRuleResponse> ruleSeeds
    ) {
        public static TemplateResponse from(StyleTemplates.Template t) {
            return new TemplateResponse(t.id(), t.name(), t.description(),
                    t.families().stream().map(Enum::name).toList(),
                    t.styleType().name(), t.suggestedField(), t.labelField(), t.symbol(), t.label(),
                    t.ruleSeeds().stream().map(TemplateRuleResponse::from).toList());
        }
    }

    /** One class of a classified template, before a field has been chosen for it. */
    @Schema(name = "StyleTemplateRule")
    public record TemplateRuleResponse(String label, String operator, String value,
                                       Map<String, Object> symbol) {
        static TemplateRuleResponse from(StyleTemplates.RuleSeed seed) {
            return new TemplateRuleResponse(seed.label(), seed.operator().name(), seed.value(),
                    seed.symbol());
        }
    }

    /** One icon from the built-in Maki/Material library. */
    @Schema(name = "LibrarySymbol")
    public record LibrarySymbolResponse(
            @Schema(description = "What a style's icon property stores: lib-<id>") String iconName,
            String name,
            @Schema(description = "MAKI or MATERIAL") String set,
            @Schema(description = "Where to fetch the SVG bytes") String contentUrl
    ) {
        static LibrarySymbolResponse from(SymbolLibrary.LibrarySymbol s) {
            return new LibrarySymbolResponse(s.iconName(), s.name(), s.set(),
                    com.aquagrid.platform.common.web.ApiPaths.LAYER_STYLES + "/library-icons/" + s.id()
                            + "/content");
        }
    }

    @Schema(name = "StyleOperatorOption")
    public record OperatorResponse(
            String value,
            String symbol,
            @Schema(description = "NONE, ONE, TWO or LIST — how many operands the form must collect")
            String arity,
            @Schema(description = "True when the operator compares magnitudes and needs a numeric "
                    + "or date field")
            boolean ordered
    ) {
    }

    // ---- Requests ------------------------------------------------------------------------------

    @Schema(name = "SaveStyleRequest",
            description = "The whole style, rules included. Rules replace the style's existing ones "
                    + "wholesale — a rule dropped from the editor must disappear, and a merge would "
                    + "keep drawing a class the administrator deleted.")
    public record SaveRequest(
            @NotNull UUID layerId,
            @Size(max = 120) String name,
            @Size(max = 500) String description,
            String styleType,
            @Size(max = 63) String classifyField,
            Boolean active,
            Boolean defaultStyle,
            @Min(0) @Max(24) Integer minZoom,
            @Min(0) @Max(24) Integer maxZoom,
            Map<String, Object> symbol,
            Map<String, Object> label,
            List<RuleRequest> rules
    ) {
    }

    @Schema(name = "StyleRuleRequest")
    public record RuleRequest(
            @Size(max = 63) String fieldName,
            String operator,
            @Size(max = 255) String value1,
            @Size(max = 255) String value2,
            List<String> valueList,
            @Size(max = 120) String label,
            Map<String, Object> symbol,
            Integer sortOrder
    ) {
    }

    @Schema(name = "StyleStateChangeRequest")
    public record StateChangeRequest(@Size(max = 500) String reason) {
    }
}

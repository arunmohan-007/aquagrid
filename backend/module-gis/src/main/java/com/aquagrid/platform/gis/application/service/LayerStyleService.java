package com.aquagrid.platform.gis.application.service;

import com.aquagrid.platform.common.audit.AuditCategory;
import com.aquagrid.platform.common.audit.AuditEvent;
import com.aquagrid.platform.common.audit.AuditEventTypes;
import com.aquagrid.platform.common.audit.AuditService;
import com.aquagrid.platform.common.audit.AuditSeverity;
import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.gis.api.AttributeDefinition;
import com.aquagrid.platform.gis.application.command.StyleCommands;
import com.aquagrid.platform.gis.domain.enums.AttributeDataType;
import com.aquagrid.platform.gis.domain.enums.StyleOperator;
import com.aquagrid.platform.gis.domain.enums.StyleType;
import com.aquagrid.platform.gis.domain.model.Layer;
import com.aquagrid.platform.gis.domain.model.LayerStyle;
import com.aquagrid.platform.gis.domain.model.LayerStyleRule;
import com.aquagrid.platform.gis.domain.style.MapLibreStyleComposer;
import com.aquagrid.platform.gis.domain.style.SymbolKeys;
import com.aquagrid.platform.gis.domain.style.SymbolLibrary;
import com.aquagrid.platform.gis.infrastructure.persistence.LayerStyleRepository;
import com.aquagrid.platform.gis.infrastructure.persistence.LayerStyleRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Layer Style Management — the styles' only writer, and the source of the map's rendering
 * instructions.
 *
 * <p>Two things make this module worth having rather than a colour picker on the layer row.
 *
 * <p><b>Fields come from Data Management, always.</b> Every field a style names — the label field,
 * the classification field, every rule's field — is validated against
 * {@code gis.layer_attribute_master} for that layer. There is no second attribute list anywhere in
 * this service, so a field retired in Data Management immediately stops being offerable here, and a
 * style that names one is refused with a message that says which field and where to find it. The
 * declared data type is used as well as the name: an ordered comparison on a TEXT field, or a
 * numeric band on a field that holds words, is refused rather than composed into an expression that
 * silently never matches.
 *
 * <p><b>The server composes the MapLibre expressions.</b> A classified style has to be expressed
 * twice — on the map and in the preview an administrator checks before saving — and two compilers of
 * the same rules is two chances to disagree. {@link MapLibreStyleComposer} is the only one, and both
 * the map endpoint and the preview endpoint call it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LayerStyleService {

    /** {@code #rgb}, {@code #rrggbb}, {@code #rrggbbaa}, {@code rgb(...)} or {@code rgba(...)}. */
    private static final Pattern COLOUR = Pattern.compile(
            "^(#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})|rgba?\\([^)]*\\))$");

    private final LayerStyleRepository styleRepository;
    private final LayerStyleRuleRepository ruleRepository;
    private final LayerManagementService layerService;
    private final LayerMetadataService metadataService;
    private final AuditService auditService;
    private final SymbolLibrary symbolLibrary;
    private final com.aquagrid.platform.gis.infrastructure.persistence.MapSymbolRepository symbolRepository;
    /*
     * Every write below evicts. The tile endpoint caches which attributes a layer's style reads so
     * it knows what to put in the tile; a style that starts classifying on a new field would
     * otherwise be composed into an expression reading a property the tile does not carry, and the
     * map would draw every feature in the fallback colour while reporting nothing wrong.
     */
    private final LayerRenderCache renderCache;

    // ---- Reads ---------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<StyleDetail> listForLayer(UUID organizationId, UUID layerId) {
        layerService.require(organizationId, layerId);
        List<LayerStyle> styles = styleRepository
                .findByOrganizationIdAndLayerIdOrderByNameAsc(organizationId, layerId);
        if (styles.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<LayerStyleRule>> rulesByStyle = rulesFor(styles);
        return styles.stream()
                .map(s -> new StyleDetail(s, rulesByStyle.getOrDefault(s.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public StyleDetail get(UUID organizationId, UUID styleId) {
        LayerStyle style = require(organizationId, styleId);
        return new StyleDetail(style, ruleRepository.findByStyleIdOrderBySortOrderAscIdAsc(styleId));
    }

    @Transactional(readOnly = true)
    public LayerStyle require(UUID organizationId, UUID styleId) {
        return styleRepository.findByIdAndOrganizationId(styleId, organizationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No style " + styleId + " in this organisation."));
    }

    /**
     * The whole tenant's map, composed: every usable layer with its default style resolved into
     * MapLibre layer specifications.
     *
     * <p>One request rather than one per layer. The alternative is N round trips on every page load
     * and a map that paints its layers in whatever order the responses happened to arrive, which
     * puts a service connection above the DMA it sits in about half the time.
     *
     * <p>A layer with no active default style is <em>included</em>, composed against the platform's
     * fallback symbology. Excluding it would mean deactivating a style makes a layer vanish, which is
     * a much bigger consequence than the administrator asked for.
     */
    @Transactional(readOnly = true)
    public List<MapLibreStyleComposer.ComposedLayer> composeMapStyle(UUID organizationId,
                                                                     String tileUrlPattern) {
        List<Layer> layers = layerService.usableLayers(organizationId).stream()
                .filter(Layer::isVectorTileEnabled)
                .toList();
        if (layers.isEmpty()) {
            return List.of();
        }

        Map<UUID, LayerStyle> defaults = styleRepository.findActiveDefaults(organizationId).stream()
                .collect(Collectors.toMap(LayerStyle::getLayerId, Function.identity(), (a, b) -> a));
        Map<UUID, List<LayerStyleRule>> rulesByStyle = rulesFor(defaults.values());

        return layers.stream().map(layer -> {
            LayerStyle style = defaults.get(layer.getId());
            List<LayerStyleRule> rules = style == null ? List.of()
                    : rulesByStyle.getOrDefault(style.getId(), List.of());
            return MapLibreStyleComposer.compose(layer, style, rules,
                    fieldTypes(organizationId, layer.getId()),
                    tileUrlPattern.replace("{layer}", layer.getCode()));
        }).toList();
    }

    /**
     * Composes one style without saving it — the live preview.
     *
     * <p>Runs the same validation as {@link #save}, so the preview cannot show something the save
     * would reject. It is the identical code path with the write removed, which is what makes
     * "preview then save" a guarantee rather than a hope.
     */
    @Transactional(readOnly = true)
    public MapLibreStyleComposer.ComposedLayer preview(UUID organizationId, StyleCommands.Save command,
                                                       String tileUrlPattern) {
        Layer layer = layerService.require(organizationId, command.layerId());
        Map<String, AttributeDefinition> catalogue = catalogue(organizationId, layer.getId());

        LayerStyle style = new LayerStyle();
        style.setId(UUID.randomUUID());
        style.setOrganizationId(organizationId);
        style.setLayerId(layer.getId());
        apply(organizationId, style, command, catalogue);

        List<LayerStyleRule> rules = buildRules(organizationId, style.getId(), command, catalogue);
        return MapLibreStyleComposer.compose(layer, style, rules,
                typesOf(catalogue), tileUrlPattern.replace("{layer}", layer.getCode()));
    }

    // ---- Writes --------------------------------------------------------------------------------

    /**
     * Creates a style, or replaces an existing one entire.
     *
     * <p>Whole-style replacement rather than a patch, and rules replaced wholesale rather than
     * merged: a rule dropped from the editor must disappear, and a merge would keep drawing a class
     * the administrator deleted. One transaction, so the map never observes a half-edited
     * classification.
     */
    @Transactional
    public StyleDetail save(UUID organizationId, UUID actorId, String actorName, UUID styleId,
                            StyleCommands.Save command) {
        Layer layer = layerService.require(organizationId, command.layerId());
        Map<String, AttributeDefinition> catalogue = catalogue(organizationId, layer.getId());

        boolean creating = styleId == null;
        LayerStyle style = creating ? new LayerStyle() : require(organizationId, styleId);
        if (creating) {
            style.setOrganizationId(organizationId);
            style.setLayerId(layer.getId());
        } else if (!style.getLayerId().equals(layer.getId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A style belongs to the layer it was created on and cannot be moved to another. "
                            + "Its rules are validated against that layer's fields, which the new "
                            + "layer may not have.");
        }

        String name = command.name() == null || command.name().isBlank() ? "Default" : command.name().trim();
        styleRepository.findByLayerIdAndNameIgnoreCase(layer.getId(), name)
                .filter(other -> !other.getId().equals(style.getId()))
                .ifPresent(other -> {
                    throw new BusinessException(ErrorCode.RESOURCE_CONFLICT,
                            "'" + name + "' is already a style on " + layer.getTitle() + ".");
                });
        style.setName(name);
        apply(organizationId, style, command, catalogue);

        /*
         * Clearing the previous default before setting this one is required, not merely tidy: the
         * partial unique index in V1333 would otherwise reject the write. The index is what
         * guarantees "which style does the map use" has one answer even when two administrators save
         * at the same instant; this is the cooperative half of the same rule.
         */
        if (style.isDefaultStyle() && style.isActive()) {
            styleRepository.findByLayerIdAndDefaultStyleTrueAndActiveTrue(layer.getId())
                    .filter(current -> !current.getId().equals(style.getId()))
                    .ifPresent(current -> {
                        current.setDefaultStyle(false);
                        styleRepository.saveAndFlush(current);
                    });
        }

        LayerStyle saved = styleRepository.save(style);

        if (!creating) {
            ruleRepository.deleteByStyleId(saved.getId());
            // Flushed before the inserts so the delete and the re-insert cannot be reordered into a
            // unique-constraint collision on a rule that is being kept.
            ruleRepository.flush();
        }
        List<LayerStyleRule> rules = buildRules(organizationId, saved.getId(), command, catalogue);
        List<LayerStyleRule> savedRules = rules.isEmpty() ? List.of() : ruleRepository.saveAll(rules);

        renderCache.evict(organizationId);
        audit(organizationId, actorId, actorName,
                creating ? AuditEventTypes.GIS_LAYER_STYLE_CREATED : AuditEventTypes.GIS_LAYER_STYLE_UPDATED,
                saved, layer,
                (creating ? "Created" : "Updated") + " style '" + saved.getName() + "' on " + layer.getTitle(),
                Map.of("styleType", saved.getStyleType().name(),
                        "classifyField", saved.getClassifyField() == null ? "" : saved.getClassifyField(),
                        // Counts go into a JSONB column on the append-only audit table as text: a
                        // numeric value there has produced a failed UPDATE before.
                        "ruleCount", String.valueOf(savedRules.size()),
                        "isDefault", String.valueOf(saved.isDefaultStyle())));
        log.info("Style '{}' saved on layer {} for org {} with {} rules",
                saved.getName(), layer.getCode(), organizationId, savedRules.size());
        return new StyleDetail(saved, savedRules);
    }

    /**
     * Activates or deactivates a style.
     *
     * <p>Deactivating the default is allowed and leaves the layer with none, at which point the map
     * falls back to the platform's built-in symbology. That is the point: the alternative — refusing,
     * or blanking the layer — would make deactivation either impossible or destructive, and it is
     * neither.
     */
    @Transactional
    public StyleDetail setActive(UUID organizationId, UUID actorId, String actorName, UUID styleId,
                                 boolean active, String reason) {
        LayerStyle style = require(organizationId, styleId);
        if (style.isActive() == active) {
            return get(organizationId, styleId);
        }
        Layer layer = layerService.require(organizationId, style.getLayerId());
        style.setActive(active);
        LayerStyle saved = styleRepository.save(style);
        renderCache.evict(organizationId);

        audit(organizationId, actorId, actorName,
                active ? AuditEventTypes.GIS_LAYER_STYLE_ACTIVATED
                        : AuditEventTypes.GIS_LAYER_STYLE_DEACTIVATED,
                saved, layer,
                "Style '" + saved.getName() + "' on " + layer.getTitle()
                        + (active ? " activated" : " deactivated")
                        + (!active && saved.isDefaultStyle()
                        ? "; the layer now draws with the platform's built-in symbology" : ""),
                Map.of("reason", reason == null ? "" : reason,
                        "wasDefault", String.valueOf(saved.isDefaultStyle())));
        return new StyleDetail(saved, ruleRepository.findByStyleIdOrderBySortOrderAscIdAsc(styleId));
    }

    /** Makes a style the one the map draws. Activates it if it was not: a hidden default is not one. */
    @Transactional
    public StyleDetail makeDefault(UUID organizationId, UUID actorId, String actorName, UUID styleId) {
        LayerStyle style = require(organizationId, styleId);
        Layer layer = layerService.require(organizationId, style.getLayerId());

        styleRepository.findByLayerIdAndDefaultStyleTrueAndActiveTrue(layer.getId())
                .filter(current -> !current.getId().equals(styleId))
                .ifPresent(current -> {
                    current.setDefaultStyle(false);
                    styleRepository.saveAndFlush(current);
                });
        style.setDefaultStyle(true);
        style.setActive(true);
        LayerStyle saved = styleRepository.save(style);
        renderCache.evict(organizationId);

        audit(organizationId, actorId, actorName, AuditEventTypes.GIS_LAYER_STYLE_DEFAULTED,
                saved, layer,
                "Style '" + saved.getName() + "' is now the default for " + layer.getTitle(),
                Map.of());
        return new StyleDetail(saved, ruleRepository.findByStyleIdOrderBySortOrderAscIdAsc(styleId));
    }

    // ---- Validation ----------------------------------------------------------------------------

    private void apply(UUID organizationId, LayerStyle style, StyleCommands.Save command,
                       Map<String, AttributeDefinition> catalogue) {
        StyleType type = command.styleType() == null ? StyleType.SIMPLE : command.styleType();
        style.setStyleType(type);
        style.setDescription(blankToNull(command.description()));
        style.setActive(command.active() == null || command.active());
        style.setDefaultStyle(Boolean.TRUE.equals(command.defaultStyle()));
        style.setMinZoom((short) clampZoom(command.minZoom() == null ? 0 : command.minZoom()));
        style.setMaxZoom((short) clampZoom(command.maxZoom() == null ? 24 : command.maxZoom()));
        if (style.getMinZoom() > style.getMaxZoom()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Minimum zoom (" + style.getMinZoom() + ") is above maximum zoom ("
                            + style.getMaxZoom() + "), which is a style that can never draw.");
        }

        String classifyField = blankToNull(command.classifyField());
        if (type.requiresClassifyField()) {
            if (classifyField == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "A " + type.name().toLowerCase(Locale.ROOT) + " style needs a field to "
                                + "classify on. Choose one of the layer's fields in Data Management.");
            }
            AttributeDefinition field = requireField(catalogue, classifyField);
            if (type == StyleType.GRADUATED && !field.dataType().isNumeric()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "'" + field.displayName() + "' is a " + field.dataType()
                                + " field, so it has no numeric bands to graduate across. Use a "
                                + "categorical style for it, or classify on a numeric field.");
            }
        }
        style.setClassifyField(classifyField);

        style.setSymbol(validatedSymbol(organizationId, command.symbol(), "symbol"));
        style.setLabel(validatedLabel(command.label(), catalogue));
    }

    /**
     * Validates the symbol document against the closed vocabulary.
     *
     * <p>Unknown keys are dropped rather than rejected. The composer ignores them anyway, and
     * refusing a whole style because a client sent one extra key would make every field the style
     * editor adds a breaking change for older clients — while a colour that is not a colour, or an
     * opacity of 400, is refused, because those are mistakes that reach the renderer.
     */
    private Map<String, Object> validatedSymbol(UUID organizationId, Map<String, Object> raw, String what) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        Set<String> known = SymbolKeys.all();
        Set<String> colours = SymbolKeys.colourKeys();
        Set<String> opacities = SymbolKeys.opacityKeys();
        Set<String> sizes = SymbolKeys.sizeKeys();
        Map<String, List<String>> enumerated = SymbolKeys.enumeratedKeys();

        raw.forEach((key, value) -> {
            if (!known.contains(key) || value == null) {
                return;
            }
            if (colours.contains(key)) {
                out.put(key, requireColour(key, value, what));
            } else if (opacities.contains(key)) {
                out.put(key, requireRange(key, value, 0, 1, what));
            } else if (sizes.contains(key)) {
                out.put(key, requireRange(key, value, 0, 200, what));
            } else if (SymbolKeys.ICON.equals(key)) {
                out.put(key, requireIcon(organizationId, value, what));
            } else if (enumerated.containsKey(key)) {
                List<String> allowed = enumerated.get(key);
                if (!allowed.contains(String.valueOf(value))) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "'" + value + "' is not a valid " + key + ". Use one of: "
                                    + String.join(", ", allowed) + ".");
                }
                out.put(key, String.valueOf(value));
            } else {
                out.put(key, value);
            }
        });
        return out;
    }

    /**
     * Confirms an icon reference resolves to something the map can actually load.
     *
     * <p>MapLibre draws nothing for a missing image and reports no error, which turns a typo or a
     * deleted upload into a point layer that silently stops appearing — the same class of failure
     * {@code requireField} exists to catch for attributes, applied to the one other place a style
     * names something by an id rather than by value. Built-in shape names ({@code circle},
     * {@code diamond}…) are not checked against a list here: the client draws all seven regardless of
     * which layer references them, so there is nothing that can be missing.
     */
    private String requireIcon(UUID organizationId, Object value, String what) {
        String icon = String.valueOf(value);
        if (icon.startsWith("lib-")) {
            symbolLibrary.require(icon.substring("lib-".length()));
        } else if (icon.startsWith("sym-")) {
            String rawId = icon.substring("sym-".length());
            UUID symbolId;
            try {
                symbolId = UUID.fromString(rawId);
            } catch (IllegalArgumentException notAUuid) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "'" + icon + "' (" + what + ".icon) is not a valid uploaded symbol reference.");
            }
            symbolRepository.findByIdAndOrganizationId(symbolId, organizationId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "'" + icon + "' (" + what + ".icon) does not name an uploaded symbol in "
                                    + "this organisation. It may have been deleted."));
        }
        return icon;
    }

    /**
     * Validates the label document, including that its field exists in Data Management.
     *
     * <p>This is the check the brief is really asking for when it says labels must use the existing
     * Data Management fields: not that the picker is populated from the catalogue — a client could
     * always send anything — but that the server refuses a field the catalogue does not have.
     */
    private Map<String, Object> validatedLabel(Map<String, Object> raw,
                                               Map<String, AttributeDefinition> catalogue) {
        Map<String, Object> label = new HashMap<>();
        if (raw == null) {
            label.put(SymbolKeys.LABEL_ENABLED, false);
            return label;
        }
        boolean enabled = Boolean.parseBoolean(String.valueOf(raw.getOrDefault(SymbolKeys.LABEL_ENABLED, false)));
        label.put(SymbolKeys.LABEL_ENABLED, enabled);

        Object field = raw.get(SymbolKeys.LABEL_FIELD);
        String fieldName = field == null ? null : blankToNull(field.toString());
        if (enabled) {
            if (fieldName == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Labels are switched on but no field was chosen. Pick the field whose value "
                                + "should be drawn — a bore well's asset id, a tank's name.");
            }
            requireField(catalogue, fieldName);
        }
        if (fieldName != null) {
            label.put(SymbolKeys.LABEL_FIELD, fieldName);
        }

        Map<String, Object> appearance = new LinkedHashMap<>(raw);
        appearance.keySet().retainAll(Set.of(SymbolKeys.LABEL_TEXT_SIZE, SymbolKeys.LABEL_TEXT_COLOR,
                SymbolKeys.LABEL_HALO_COLOR, SymbolKeys.LABEL_HALO_WIDTH,
                SymbolKeys.LABEL_MIN_ZOOM, SymbolKeys.LABEL_MAX_ZOOM));
        appearance.forEach((key, value) -> {
            if (value == null) {
                return;
            }
            if (SymbolKeys.colourKeys().contains(key)) {
                label.put(key, requireColour(key, value, "label"));
            } else if (SymbolKeys.sizeKeys().contains(key)) {
                label.put(key, requireRange(key, value, 0, 200, "label"));
            } else {
                label.put(key, requireRange(key, value, 0, 24, "label"));
            }
        });
        return label;
    }

    /**
     * Turns the command's rules into entities, validating each against the attribute catalogue.
     *
     * <p>The type check is what makes attribute-based styling trustworthy rather than merely
     * possible. A rule that compares a TEXT field with {@code >} would compose into an expression
     * MapLibre evaluates as a string collation, and one that puts a word where a numeric band's
     * bound belongs would compose into an expression that never matches — both render as a style
     * that is simply wrong, with nothing anywhere reporting a problem. Refusing them at the point
     * they are written is the only place the mistake is still attached to the person who made it.
     */
    private List<LayerStyleRule> buildRules(UUID organizationId, UUID styleId,
                                            StyleCommands.Save command,
                                            Map<String, AttributeDefinition> catalogue) {
        if (command.rules() == null || command.rules().isEmpty()) {
            return List.of();
        }
        StyleType type = command.styleType() == null ? StyleType.SIMPLE : command.styleType();
        if (!type.isClassified()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A simple style draws every feature the same way, so it cannot carry rules. "
                            + "Choose Categorical, Graduated or Rule-based.");
        }

        List<LayerStyleRule> rules = new ArrayList<>();
        int index = 0;
        for (StyleCommands.Rule source : command.rules()) {
            index += 10;
            String fieldName = blankToNull(source.fieldName()) != null
                    ? source.fieldName().trim()
                    : command.classifyField();
            if (fieldName == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Every rule needs a field. A rule-based style names one per rule; a "
                                + "categorical or graduated style takes the style's own field.");
            }
            AttributeDefinition field = requireField(catalogue, fieldName);
            StyleOperator operator = source.operator() == null ? StyleOperator.EQ : source.operator();
            validateOperands(field, operator, source);

            LayerStyleRule rule = new LayerStyleRule();
            rule.setOrganizationId(organizationId);
            rule.setStyleId(styleId);
            rule.setFieldName(field.fieldName());
            rule.setOperator(operator);
            rule.setValue1(blankToNull(source.value1()));
            rule.setValue2(blankToNull(source.value2()));
            rule.setValueList(source.valueList() == null || source.valueList().isEmpty()
                    ? null : source.valueList());
            rule.setLabel(blankToNull(source.label()));
            rule.setSymbol(validatedSymbol(organizationId, source.symbol(), "rule symbol"));
            rule.setSortOrder(source.sortOrder() != null ? source.sortOrder() : index);
            rule.setActive(true);
            rules.add(rule);
        }
        return rules;
    }

    private static void validateOperands(AttributeDefinition field, StyleOperator operator,
                                         StyleCommands.Rule rule) {
        if (operator.isOrdered() && !field.dataType().isNumeric()
                && field.dataType() != AttributeDataType.DATE
                && field.dataType() != AttributeDataType.DATE_TIME) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "'" + field.displayName() + "' is a " + field.dataType() + " field, so '"
                            + operator.symbol() + "' would compare it as text — '9' would sort after "
                            + "'10'. Use = , != or IN for it, or choose a numeric field.");
        }
        switch (operator.arity()) {
            case NONE -> { /* IS NULL / IS NOT NULL take no operand. */ }
            case ONE -> requireValue(rule.value1(), field, operator, "a value");
            case TWO -> {
                requireValue(rule.value1(), field, operator, "a lower bound");
                requireValue(rule.value2(), field, operator, "an upper bound");
            }
            case LIST -> {
                if (rule.valueList() == null || rule.valueList().isEmpty()) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "The IN rule on '" + field.displayName() + "' lists no values, so it "
                                    + "matches nothing.");
                }
                rule.valueList().forEach(v -> requireValue(v, field, operator, "a value"));
            }
        }
    }

    /**
     * Checks one operand against the field's declared type.
     *
     * <p>Reuses {@link AttributeDataType#coerce} — the same coercion the importer applies to a value
     * arriving from a file. Using one implementation means a rule can never be written against a
     * value the importer would have refused, which is the difference between a style that classifies
     * the data and one that classifies data nobody could have loaded.
     */
    private static void requireValue(String value, AttributeDefinition field, StyleOperator operator,
                                     String what) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "The '" + operator.symbol() + "' rule on '" + field.displayName() + "' needs "
                            + what + ".");
        }
        field.dataType().coerce(value, "Rule value for " + field.displayName(),
                field.maxLength(), field.numericPrecision(), field.numericScale());
    }

    /**
     * Resolves a field name against the layer's Data Management catalogue.
     *
     * <p>The single point at which this module refuses to invent a field list of its own. An inactive
     * or unknown field fails here with a message naming the module that owns it, because the fix is
     * always in Data Management and never here.
     */
    private static AttributeDefinition requireField(Map<String, AttributeDefinition> catalogue,
                                                    String fieldName) {
        AttributeDefinition field = catalogue.get(fieldName.trim().toLowerCase(Locale.ROOT));
        if (field == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "'" + fieldName + "' is not an active field on this layer. Styles read the same "
                            + "field catalogue as import and export — add or reactivate it in Data "
                            + "Management first. Available: "
                            + catalogue.values().stream()
                            .map(AttributeDefinition::fieldName).sorted()
                            .collect(Collectors.joining(", ")) + ".");
        }
        return field;
    }

    // ---- Shared --------------------------------------------------------------------------------

    /** The layer's active fields, keyed by field name. Data Management's catalogue, unmodified. */
    @Transactional(readOnly = true)
    public Map<String, AttributeDefinition> catalogue(UUID organizationId, UUID layerId) {
        return metadataService.definitionsForLayer(organizationId, layerId).stream()
                .collect(Collectors.toMap(AttributeDefinition::fieldName, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
    }

    private Map<String, AttributeDataType> fieldTypes(UUID organizationId, UUID layerId) {
        return typesOf(catalogue(organizationId, layerId));
    }

    private static Map<String, AttributeDataType> typesOf(Map<String, AttributeDefinition> catalogue) {
        Map<String, AttributeDataType> types = new LinkedHashMap<>();
        catalogue.forEach((name, definition) -> types.put(name, definition.dataType()));
        return types;
    }

    private Map<UUID, List<LayerStyleRule>> rulesFor(java.util.Collection<LayerStyle> styles) {
        List<UUID> ids = styles.stream().map(LayerStyle::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return ruleRepository.findActiveForStyles(ids).stream()
                .collect(Collectors.groupingBy(LayerStyleRule::getStyleId));
    }

    private static Object requireColour(String key, Object value, String what) {
        String text = String.valueOf(value).trim();
        if (!COLOUR.matcher(text).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "'" + text + "' is not a colour the renderer understands (" + what + "." + key
                            + "). Use #RGB, #RRGGBB, #RRGGBBAA, rgb(...) or rgba(...).");
        }
        return text;
    }

    private static Object requireRange(String key, Object value, double min, double max, String what) {
        double number;
        try {
            number = value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException notANumber) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "'" + value + "' is not a number (" + what + "." + key + ").");
        }
        if (number < min || number > max) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    what + "." + key + " is " + number + "; it must be between " + min + " and " + max + ".");
        }
        return number;
    }

    private static int clampZoom(int zoom) {
        return Math.max(0, Math.min(24, zoom));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void audit(UUID organizationId, UUID actorId, String actorName, String eventType,
                       LayerStyle style, Layer layer, String message, Map<String, Object> metadata) {
        Map<String, Object> full = new LinkedHashMap<>(metadata);
        full.put("layerCode", layer.getCode());
        full.put("styleName", style.getName());
        auditService.record(AuditEvent.builder()
                .organizationId(organizationId)
                .actorUserId(actorId)
                .actorUsername(actorName)
                .eventType(eventType)
                .category(AuditCategory.CONFIGURATION)
                .severity(AuditSeverity.INFO)
                .resourceType("gis.layer_style")
                .resourceId(style.getId() == null ? "" : style.getId().toString())
                .success(true)
                .message(message)
                .metadata(full)
                .build());
    }

    /** A style and its rules, in evaluation order. */
    public record StyleDetail(LayerStyle style, List<LayerStyleRule> rules) {
        public StyleDetail {
            rules = rules == null ? List.of() : rules.stream()
                    .sorted(Comparator.comparingInt(LayerStyleRule::getSortOrder))
                    .toList();
        }
    }
}

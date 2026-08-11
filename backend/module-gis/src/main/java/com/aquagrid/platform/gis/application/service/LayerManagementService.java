package com.aquagrid.platform.gis.application.service;

import com.aquagrid.platform.common.audit.AuditCategory;
import com.aquagrid.platform.common.audit.AuditEvent;
import com.aquagrid.platform.common.audit.AuditEventTypes;
import com.aquagrid.platform.common.audit.AuditService;
import com.aquagrid.platform.common.audit.AuditSeverity;
import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.gis.application.command.LayerCommands;
import com.aquagrid.platform.gis.domain.enums.AssetType;
import com.aquagrid.platform.gis.domain.enums.GeometryType;
import com.aquagrid.platform.gis.domain.enums.LayerStatus;
import com.aquagrid.platform.gis.domain.metadata.LayerCodePolicy;
import com.aquagrid.platform.gis.domain.enums.StyleType;
import com.aquagrid.platform.gis.domain.model.Layer;
import com.aquagrid.platform.gis.domain.model.LayerStyle;
import com.aquagrid.platform.gis.domain.style.SymbolKeys;
import com.aquagrid.platform.gis.infrastructure.persistence.AssetRepository;
import com.aquagrid.platform.gis.infrastructure.persistence.LayerRepository;
import com.aquagrid.platform.gis.infrastructure.persistence.LayerStyleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Layer Management — the registry's only writer, and its read side.
 *
 * <p>This service owns what the platform <em>contains</em>. Data Management owns what each layer
 * holds and Layer Style Management owns how it is drawn; none of the three duplicates the others,
 * and the seam between them is {@code gis.layers.id}.
 *
 * <p>Three rules shape everything below.
 *
 * <ul>
 *   <li><b>Creating a layer issues no DDL.</b> A new layer is an INSERT into the registry, backed by
 *       the {@code gis.assets} supertype that already provides geometry(4326), the generated
 *       Web-Mercator column, both GiST indexes and a GIN-indexed attribute bag. The brief asks for a
 *       PostGIS table per layer; the platform declined the equivalent for attributes in V1330 and
 *       declines it here for the same reason — generating {@code CREATE TABLE} at runtime means the
 *       web tier holding DDL rights on its own schema, and it would make the new layer invisible to
 *       the tile endpoint, the importer, the exporter and the register until each was generalised to
 *       find it. What the brief actually wants from that table — a declared geometry type, a
 *       declared SRID, a spatial index, safe names, no injection — is delivered here without it.</li>
 *   <li><b>There is no delete.</b> A layer's features are surveyed geometry that cost a contractor a
 *       season to collect. {@link LayerStatus} withdraws the layer and touches no row of
 *       {@code gis.assets}, so every state is reversible.</li>
 *   <li><b>System layers are partly frozen.</b> The dashboard sums {@code PIPELINE} length and the
 *       network trace walks {@code PIPELINE} and {@code VALVE}. Their labels, category, styling,
 *       zoom and flags belong to the tenant; their code and asset type do not, and they cannot be
 *       archived out from under the code that reads them.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LayerManagementService {

    /** How many distinct values the categorical style editor will offer before it says "too many". */
    private static final int MAX_DISTINCT_VALUES = 200;

    private final LayerRepository layerRepository;
    private final AssetRepository assetRepository;
    /*
     * Written to directly rather than through LayerStyleService, which depends on this class — going
     * the other way would close the cycle. What is written is a seed, not user input: it needs the
     * default symbology, not the validation a submitted style needs.
     */
    private final LayerStyleRepository styleRepository;
    private final AuditService auditService;
    private final LayerRenderCache renderCache;
    private final JdbcTemplate jdbc;

    // ---- Reads ---------------------------------------------------------------------------------

    /**
     * The registry's list.
     *
     * <p>Filtered in memory over the tenant's full layer list rather than in SQL — see the note in
     * {@code LayerRepository}: a tenant has tens of layers, and expressing "status is null means
     * any" against an {@code @Enumerated(STRING)} column in HQL means walking back into the untyped-
     * null failure the datasource's {@code stringtype=unspecified} produces.
     *
     * <p>Archived layers are excluded unless asked for. An archived layer is a decision someone made
     * to retire; listing it beside live layers is how it gets re-enabled by accident.
     */
    @Transactional(readOnly = true)
    public List<LayerSummary> list(UUID organizationId, LayerQuery query) {
        String search = query.search() == null ? null : query.search().trim().toLowerCase(Locale.ROOT);

        return layerRepository.findByOrganizationIdOrderBySortOrderAsc(organizationId).stream()
                .filter(l -> query.status() == null ? l.getStatus() != LayerStatus.ARCHIVED
                        : l.getStatus() == query.status())
                .filter(l -> query.category() == null || query.category().equals(l.getCategory()))
                .filter(l -> query.geometryType() == null || query.geometryType() == l.getGeometryType())
                .filter(l -> search == null || search.isEmpty()
                        || contains(l.getCode(), search)
                        || contains(l.getTitle(), search)
                        || contains(l.getDescription(), search))
                .sorted(Comparator.comparingInt(Layer::getSortOrder)
                        .thenComparing(Layer::getTitle, Comparator.nullsLast(String::compareTo)))
                .map(l -> summarise(organizationId, l, query.withCounts()))
                .toList();
    }

    /**
     * One layer with its live statistics.
     *
     * <p>Feature count and extent are always computed here, unlike in the list, because this backs
     * the detail panel and the preview map — both of which are about one layer and need the numbers
     * to be current rather than cheap.
     */
    @Transactional(readOnly = true)
    public LayerSummary get(UUID organizationId, UUID layerId) {
        return summarise(organizationId, require(organizationId, layerId), true);
    }

    @Transactional(readOnly = true)
    public Layer require(UUID organizationId, UUID layerId) {
        return layerRepository.findByIdAndOrganizationId(layerId, organizationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No layer " + layerId + " in this organisation."));
    }

    @Transactional(readOnly = true)
    public Layer requireByCode(UUID organizationId, String code) {
        return layerRepository.findByOrganizationIdAndCode(organizationId, code)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No layer '" + code + "' in this organisation."));
    }

    /** The layers the map, the tile endpoint and the import hub should offer. */
    @Transactional(readOnly = true)
    public List<Layer> usableLayers(UUID organizationId) {
        return layerRepository.findByOrganizationIdAndStatusOrderBySortOrderAsc(
                organizationId, LayerStatus.ACTIVE);
    }

    /** Categories in use, so the registry's filter and the create form offer what the tenant has. */
    @Transactional(readOnly = true)
    public List<String> categories(UUID organizationId) {
        List<String> used = layerRepository.findByOrganizationIdOrderBySortOrderAsc(organizationId)
                .stream().map(Layer::getCategory).filter(c -> c != null && !c.isBlank()).distinct().sorted().toList();
        /*
         * Seeded suggestions merged with what is actually in use, rather than either alone. A pure
         * "distinct in use" list cannot offer a category to the first layer that would belong to it,
         * and a pure fixed list is the hard-coded vocabulary this module exists to avoid.
         */
        List<String> merged = new ArrayList<>(List.of(
                "Pipe Network", "Point Assets", "Facilities", "Boundaries", "Other"));
        used.forEach(c -> {
            if (!merged.contains(c)) {
                merged.add(c);
            }
        });
        return merged;
    }

    /**
     * Feature count for a layer, from an indexed {@code COUNT(*)}.
     *
     * <p>Never a fetch. The registry shows this for every layer on screen at once and a layer with
     * two million service connections must cost what one with four tanks costs.
     */
    @Transactional(readOnly = true)
    public long featureCount(UUID organizationId, Layer layer) {
        return assetRepository.countForLayer(organizationId, layer.getId(), layer.getAssetType().name());
    }

    /** Extent in EPSG:4326 from {@code ST_Extent}, or empty when the layer holds nothing. */
    @Transactional(readOnly = true)
    public Optional<double[]> extent(UUID organizationId, Layer layer) {
        return assetRepository
                .findExtentForLayer(organizationId, layer.getId(), layer.getAssetType().name())
                .stream().findFirst()
                .map(row -> new double[]{
                        ((Number) row[0]).doubleValue(), ((Number) row[1]).doubleValue(),
                        ((Number) row[2]).doubleValue(), ((Number) row[3]).doubleValue()});
    }

    /** Distinct values of an attribute across a layer, for the categorical style editor. */
    @Transactional(readOnly = true)
    public List<String> distinctValues(UUID organizationId, Layer layer, String fieldName) {
        return assetRepository.findDistinctAttributeValues(organizationId, layer.getId(),
                layer.getAssetType().name(), fieldName, MAX_DISTINCT_VALUES);
    }

    /** Observed min and max of a numeric attribute, for the graduated style editor's suggestions. */
    @Transactional(readOnly = true)
    public Optional<double[]> numericRange(UUID organizationId, Layer layer, String fieldName) {
        return assetRepository.findAttributeNumericRange(organizationId, layer.getId(),
                        layer.getAssetType().name(), fieldName)
                .stream().findFirst()
                .filter(row -> row[0] != null && row[1] != null)
                .map(row -> new double[]{((Number) row[0]).doubleValue(), ((Number) row[1]).doubleValue()});
    }

    /**
     * The coordinate reference systems this database can actually honour.
     *
     * <p>Read from {@code public.spatial_ref_sys}, which is PostGIS's own catalogue, rather than
     * from a hard-coded pair of EPSG codes. That is what the brief means by "use the existing CRS
     * configuration": the projections a deployment supports are already written down, including any
     * local grid a state utility has added, and a second list in Java could only ever be a subset
     * that goes stale.
     *
     * <p>Filtered to the authority and, optionally, a search term, and capped — spatial_ref_sys ships
     * with about nine thousand rows, which is a dropdown nobody can use and a payload nobody needs.
     */
    @Transactional(readOnly = true)
    public List<CrsOption> availableCrs(String search, int limit) {
        String term = search == null || search.isBlank() ? null : "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
        /*
         * Every occurrence of the search parameter is cast, not just the IS NULL guard.
         *
         * The datasource runs stringtype=unspecified, so the driver sends a null String untyped and
         * Postgres cannot infer a type from `? IS NULL` — it fails to plan with "could not determine
         * data type of parameter $1", and the failure hits the *unfiltered* call, which is the one
         * the CRS picker makes on open. This is the same trap AGENTS.md documents for JPQL, and it
         * bites plain JDBC identically; the cast at every position is the fix in both.
         */
        String sql = """
                SELECT srid, auth_name, coalesce(split_part(srtext, '"', 2), 'SRID ' || srid) AS title
                FROM public.spatial_ref_sys
                WHERE auth_name IS NOT NULL
                  AND (cast(? as text) IS NULL
                       OR lower(coalesce(srtext, '')) LIKE cast(? as text)
                       OR cast(srid as text) LIKE cast(? as text))
                ORDER BY
                    -- The two everyone actually wants, first, whatever the search says. 4326 is the
                    -- storage CRS and 3857 is the tile CRS; a list that buries them under 9,000
                    -- alphabetical alternatives is a list that gets scrolled past.
                    CASE srid WHEN 4326 THEN 0 WHEN 3857 THEN 1 ELSE 2 END,
                    srid
                LIMIT ?
                """;
        return jdbc.query(sql, (rs, i) -> new CrsOption(
                        rs.getInt("srid"), rs.getString("auth_name"), rs.getString("title")),
                term, term, term, Math.min(Math.max(limit, 1), 500));
    }

    // ---- Create --------------------------------------------------------------------------------

    /**
     * Registers a layer.
     *
     * <p>The layer is usable the moment this returns: it appears in the map's layer control, the
     * tile endpoint serves it, Data Management will accept attributes on it and the import hub will
     * offer it as a target. None of that required a migration, a restart or a line of code, which is
     * the point of the module.
     */
    @Transactional
    public Layer create(UUID organizationId, UUID actorId, String actorName, LayerCommands.Create command) {
        String code = command.code() == null || command.code().isBlank()
                ? LayerCodePolicy.normaliseAndValidate(deriveCode(command.title()))
                : LayerCodePolicy.normaliseAndValidate(command.code());

        if (layerRepository.existsByOrganizationIdAndCode(organizationId, code)) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT,
                    "A layer named '" + code + "' already exists. Layer names are permanent because "
                            + "they appear in tile URLs and MapLibre source ids, so they cannot be "
                            + "reused even after a layer is archived — choose another, or reactivate "
                            + "the existing layer.");
        }

        Layer layer = new Layer();
        layer.setOrganizationId(organizationId);
        layer.setCode(code);
        layer.setAssetType(command.assetType() == null ? AssetType.CUSTOM : command.assetType());
        layer.setSystem(false);
        layer.setStatus(command.active() == null || command.active() ? LayerStatus.ACTIVE : LayerStatus.INACTIVE);
        layer.setSortOrder(command.sortOrder() != null ? command.sortOrder()
                : layerRepository.maxSortOrder(organizationId) + 10);
        applyEditable(layer, command.title(), command.description(), command.category(),
                command.geometryType(), command.crsAuthority(), command.srid(),
                command.visibleByDefault(), command.editable(), command.queryable(), command.searchable(),
                command.importEnabled(), command.exportEnabled(), command.vectorTileEnabled(),
                command.minZoom(), command.maxZoom());
        if (layer.getTitle() == null || layer.getTitle().isBlank()) {
            layer.setTitle(code);
        }

        Layer saved = layerRepository.save(layer);

        int claimed = 0;
        if (command.claimExistingFeatures() && saved.getAssetType() != AssetType.CUSTOM) {
            /*
             * Only unclaimed features, and only when the caller asked. The import wizard asks,
             * because the file that prompted the layer has just landed and its rows would otherwise
             * belong to no layer; the registry's own create form does not, because adopting another
             * layer's unassigned backlog on a button press is a surprise nobody consented to.
             */
            claimed = assetRepository.claimUnassignedFeatures(organizationId, saved.getId(),
                    saved.getAssetType().name());
        }

        seedDefaultStyle(saved);

        renderCache.evict(organizationId);
        audit(organizationId, actorId, actorName, AuditEventTypes.GIS_LAYER_CREATED, saved,
                "Created layer '" + saved.getTitle() + "' (" + saved.getCode() + ")",
                Map.of("geometryType", saved.getGeometryType().name(),
                        "crs", saved.crs(),
                        "assetType", saved.getAssetType().name(),
                        // Audit metadata goes into a JSONB column on an append-only table; a
                        // numeric value there has caused a failed UPDATE before, so counts are
                        // recorded as text.
                        "claimedFeatures", String.valueOf(claimed)));
        log.info("Layer {} created for org {} ({} existing features claimed)",
                saved.getCode(), organizationId, claimed);
        return saved;
    }

    /**
     * Gives a new layer a default style, so it draws in its own colour from the first tile.
     *
     * <p>Without this a new layer renders with the composer's grey fallback, and the style editor
     * opens on a blank form rather than on something to adjust — which is a worse first five minutes
     * than the feature deserves, and the state V1333 deliberately avoided for every existing layer.
     *
     * <p>The palette is a hard-coded list, which is the thing this module removed from the client.
     * The difference is that these are <em>seed values written to a row</em>, editable from the
     * moment they land, exactly as V1333's migrated colours are — not a lookup consulted at render
     * time that no administrator can reach. Nothing reads this list after the INSERT.
     *
     * <p>Saturated red, amber and orange are absent: the platform reserves them for alarm severity,
     * and a layer that helps itself to one leaves an operator unable to read urgency from colour.
     */
    private void seedDefaultStyle(Layer layer) {
        String[] palette = {
                "#3B82F6", "#06B6D4", "#14B8A6", "#8B5CF6", "#EC4899",
                "#0EA5E9", "#6366F1", "#A855F7", "#0891B2", "#C026D3"};
        /*
         * Chosen by the layer's own id rather than by a counter, so two layers created in either
         * order get the same colours — a counter would make the result depend on creation sequence,
         * which is invisible and irreproducible.
         */
        String colour = palette[Math.floorMod(layer.getId().hashCode(), palette.length)];

        LayerStyle style = new LayerStyle();
        style.setOrganizationId(layer.getOrganizationId());
        style.setLayerId(layer.getId());
        style.setName("Default");
        style.setDescription("Built-in symbology for " + layer.getTitle() + ".");
        style.setStyleType(StyleType.SIMPLE);
        style.setActive(true);
        style.setDefaultStyle(true);
        Map<String, Object> symbol = new LinkedHashMap<>();
        symbol.put(SymbolKeys.RENDER_MODE, "circle");
        symbol.put(SymbolKeys.FILL_COLOR, colour);
        symbol.put(SymbolKeys.GLOW_COLOR, colour);
        symbol.put(SymbolKeys.STROKE_COLOR, "rgba(255,255,255,0.9)");
        symbol.put(SymbolKeys.STROKE_WIDTH, 1.5);
        symbol.put(SymbolKeys.SIZE, 5);
        symbol.put(SymbolKeys.OPACITY, 1.0);
        symbol.put(SymbolKeys.LINE_COLOR, colour);
        symbol.put(SymbolKeys.LINE_WIDTH, 3);
        symbol.put(SymbolKeys.LINE_OPACITY, 1.0);
        symbol.put(SymbolKeys.LINE_CAP, "round");
        symbol.put(SymbolKeys.LINE_JOIN, "round");
        symbol.put(SymbolKeys.FILL_OPACITY, 0.14);
        symbol.put(SymbolKeys.OUTLINE_COLOR, colour);
        symbol.put(SymbolKeys.OUTLINE_WIDTH, 1.5);
        symbol.put(SymbolKeys.OUTLINE_OPACITY, 1.0);
        style.setSymbol(symbol);
        // Labels off, matching every migrated layer: a new layer should look like the others until
        // someone decides otherwise.
        style.setLabel(new LinkedHashMap<>(Map.of(SymbolKeys.LABEL_ENABLED, false)));
        styleRepository.save(style);
    }

    // ---- Update --------------------------------------------------------------------------------

    /**
     * Edits a layer's metadata.
     *
     * <p>{@code code} and {@code assetType} are not editable and are not in the command. The code is
     * the {@code source-layer} name inside every cached vector tile and the MapLibre source id every
     * render layer references — renaming it would stop the map drawing the layer with no error
     * anywhere. The asset type decides which physical bucket the features are in, so changing it
     * would orphan every existing feature rather than move it.
     */
    @Transactional
    public Layer update(UUID organizationId, UUID actorId, String actorName, UUID layerId,
                        LayerCommands.Update command) {
        Layer layer = require(organizationId, layerId);
        Map<String, Object> before = snapshot(layer);

        applyEditable(layer, command.title(), command.description(), command.category(),
                command.geometryType(), command.crsAuthority(), command.srid(),
                command.visibleByDefault(), command.editable(), command.queryable(), command.searchable(),
                command.importEnabled(), command.exportEnabled(), command.vectorTileEnabled(),
                command.minZoom(), command.maxZoom());
        if (command.sortOrder() != null) {
            layer.setSortOrder(command.sortOrder());
        }

        Layer saved = layerRepository.save(layer);
        renderCache.evict(organizationId);
        audit(organizationId, actorId, actorName, AuditEventTypes.GIS_LAYER_UPDATED, saved,
                "Updated layer '" + saved.getTitle() + "'",
                Map.of("previousTitle", String.valueOf(before.get("title")),
                        "previousGeometryType", String.valueOf(before.get("geometryType"))));
        return saved;
    }

    // ---- Lifecycle -----------------------------------------------------------------------------

    /**
     * Moves a layer between {@link LayerStatus} values. The module's only "delete", and it removes
     * nothing.
     *
     * <p>A system layer cannot be archived: the dashboard sums {@code PIPELINE} length and the
     * network trace walks {@code PIPELINE} and {@code VALVE}, so archiving one would leave the
     * platform's own code reading a layer the registry says is gone. Disabling it is allowed — that
     * takes it off the map without telling the code it no longer exists.
     */
    @Transactional
    public Layer changeStatus(UUID organizationId, UUID actorId, String actorName, UUID layerId,
                              LayerStatus target, String reason) {
        Layer layer = require(organizationId, layerId);
        if (layer.getStatus() == target) {
            return layer;
        }
        if (target == LayerStatus.ARCHIVED && layer.isSystem()) {
            throw new BusinessException(ErrorCode.OPERATION_NOT_PERMITTED,
                    "'" + layer.getTitle() + "' is a system layer: the platform's own code reads it "
                            + "by asset type — the dashboard sums its length, the network trace walks "
                            + "it — so archiving it would leave that code querying a layer the "
                            + "registry says is gone. Disable it instead: that takes it off the map "
                            + "and out of the import hub while leaving it addressable.");
        }

        LayerStatus previous = layer.getStatus();
        layer.setStatus(target);
        Layer saved = layerRepository.save(layer);
        renderCache.evict(organizationId);

        String eventType = switch (target) {
            case ACTIVE -> AuditEventTypes.GIS_LAYER_ENABLED;
            case INACTIVE -> AuditEventTypes.GIS_LAYER_DISABLED;
            case ARCHIVED -> AuditEventTypes.GIS_LAYER_ARCHIVED;
        };
        audit(organizationId, actorId, actorName, eventType, saved,
                "Layer '" + saved.getTitle() + "' moved from " + previous + " to " + target
                        + "; no feature was removed",
                Map.of("previousStatus", previous.name(),
                        "reason", reason == null ? "" : reason));
        log.info("Layer {} for org {} moved {} -> {}", saved.getCode(), organizationId, previous, target);
        return saved;
    }

    // ---- Shared --------------------------------------------------------------------------------

    /**
     * Applies the fields an edit may touch, treating null as "leave alone".
     *
     * <p>System layers are guarded here rather than in the controller: their labels, category,
     * styling, zoom and flags are the tenant's, and only the two immutable fields are withheld —
     * which they are for every layer, so there is nothing extra to check. What <em>is</em> checked is
     * the geometry type, because narrowing it on a layer that already holds mixed geometry would
     * silently start rejecting the half that no longer fits.
     */
    private void applyEditable(Layer layer, String title, String description, String category,
                               GeometryType geometryType, String crsAuthority, Integer srid,
                               Boolean visible, Boolean editable, Boolean queryable, Boolean searchable,
                               Boolean importEnabled, Boolean exportEnabled, Boolean vectorTileEnabled,
                               Integer minZoom, Integer maxZoom) {
        if (title != null && !title.isBlank()) layer.setTitle(title.trim());
        if (description != null) layer.setDescription(blankToNull(description));
        if (category != null) layer.setCategory(blankToNull(category));
        if (geometryType != null) layer.setGeometryType(geometryType);
        if (crsAuthority != null && !crsAuthority.isBlank()) layer.setCrsAuthority(crsAuthority.trim());
        if (srid != null) layer.setSrid(validateSrid(srid));
        if (visible != null) layer.setVisible(visible);
        if (editable != null) layer.setEditable(editable);
        if (queryable != null) layer.setQueryable(queryable);
        if (searchable != null) layer.setSearchable(searchable);
        if (importEnabled != null) layer.setImportEnabled(importEnabled);
        if (exportEnabled != null) layer.setExportEnabled(exportEnabled);
        if (vectorTileEnabled != null) layer.setVectorTileEnabled(vectorTileEnabled);
        if (minZoom != null) layer.setMinZoom((short) clampZoom(minZoom));
        if (maxZoom != null) layer.setMaxZoom((short) clampZoom(maxZoom));

        if (layer.getMinZoom() > layer.getMaxZoom()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Minimum zoom (" + layer.getMinZoom() + ") is above maximum zoom ("
                            + layer.getMaxZoom() + "), which is a layer that can never draw.");
        }
    }

    /**
     * Checks an SRID against PostGIS's own catalogue.
     *
     * <p>A service lookup rather than a foreign key, for the reason V1332 records: declaring a
     * reference to {@code public.spatial_ref_sys} needs the REFERENCES privilege on a table the
     * application's role has SELECT on and ownership of nowhere, so the constraint would apply in
     * development and fail the deployment that matters. The check is here instead, where it also
     * produces a message that says what is wrong.
     */
    private int validateSrid(int srid) {
        Boolean known = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM public.spatial_ref_sys WHERE srid = ?)", Boolean.class, srid);
        if (!Boolean.TRUE.equals(known)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "SRID " + srid + " is not defined in this database's spatial_ref_sys, so PostGIS "
                            + "could not transform geometry into or out of it. Choose a CRS from the "
                            + "list, or have the projection added to spatial_ref_sys first.");
        }
        return srid;
    }

    private static String deriveCode(String title) {
        String derived = LayerCodePolicy.deriveFrom(title);
        if (derived.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A layer name could not be derived from the display name. Enter one directly — "
                            + "lower-case letters, numbers and hyphens.");
        }
        return derived;
    }

    private LayerSummary summarise(UUID organizationId, Layer layer, boolean withStatistics) {
        long count = withStatistics ? featureCount(organizationId, layer) : -1L;
        double[] box = withStatistics ? extent(organizationId, layer).orElse(null) : null;
        return new LayerSummary(layer, count, box);
    }

    private static int clampZoom(int zoom) {
        return Math.max(0, Math.min(24, zoom));
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Map<String, Object> snapshot(Layer layer) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("title", layer.getTitle());
        state.put("category", layer.getCategory());
        state.put("geometryType", layer.getGeometryType().name());
        state.put("crs", layer.crs());
        state.put("status", layer.getStatus().name());
        return state;
    }

    private void audit(UUID organizationId, UUID actorId, String actorName, String eventType,
                       Layer layer, String message, Map<String, Object> metadata) {
        Map<String, Object> full = new LinkedHashMap<>(metadata);
        full.put("layerCode", layer.getCode());
        auditService.record(AuditEvent.builder()
                .organizationId(organizationId)
                .actorUserId(actorId)
                .actorUsername(actorName)
                .eventType(eventType)
                .category(AuditCategory.CONFIGURATION)
                .severity(AuditSeverity.INFO)
                .resourceType("gis.layers")
                .resourceId(layer.getId().toString())
                .success(true)
                .message(message)
                .metadata(full)
                .build());
    }

    // ---- Query and view types ------------------------------------------------------------------

    /**
     * The registry grid's filters.
     *
     * @param withCounts whether to compute the feature count and extent per row. The grid asks for
     *                   them; a picker that only needs names does not, and on a tenant with fifty
     *                   layers that is fifty aggregate queries saved on a screen that would not have
     *                   shown the answers.
     */
    public record LayerQuery(
            LayerStatus status,
            String category,
            GeometryType geometryType,
            String search,
            boolean withCounts
    ) {
    }

    /**
     * A layer as the registry shows it.
     *
     * @param featureCount {@code -1} when statistics were not requested, so "not asked" is
     *                     distinguishable from "empty layer" — the two mean very different things to
     *                     someone deciding whether an import worked
     * @param extent       {@code [minLon, minLat, maxLon, maxLat]} in EPSG:4326, or null when the
     *                     layer holds no features
     */
    public record LayerSummary(Layer layer, long featureCount, double[] extent) {
    }

    /** One coordinate reference system, as {@code spatial_ref_sys} knows it. */
    public record CrsOption(int srid, String authority, String title) {
    }
}

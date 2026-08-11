package com.aquagrid.platform.gis.application.service;

import com.aquagrid.platform.common.audit.AuditCategory;
import com.aquagrid.platform.common.audit.AuditEvent;
import com.aquagrid.platform.common.audit.AuditEventTypes;
import com.aquagrid.platform.common.audit.AuditService;
import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.gis.api.AttributeDefinition;
import com.aquagrid.platform.gis.api.LayerMetadataApi;
import com.aquagrid.platform.gis.domain.enums.AssetType;
import com.aquagrid.platform.gis.domain.enums.AttributeStorage;
import com.aquagrid.platform.gis.domain.model.Layer;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Exports a layer's assets using the columns the catalogue declares.
 *
 * <p>The counterpart of the metadata-driven importer, and the reason the pair is worth having: a
 * field created in Data Management is importable and exportable the same afternoon, and the file
 * that comes out has the same columns as the file that went in. An exporter with its own hard-coded
 * column list would drift from the importer within one release, and the drift would be invisible
 * until a utility noticed a field it had been supplying for months was never coming back.
 *
 * <p><b>Active and visible, by default.</b> Active is the soft delete — a retired field is not
 * exported at all, because a column that is empty for every row imported since it was retired is
 * worse than an absent one. Visible is presentation, and it is why {@code lon} and {@code lat} do
 * not appear: they are how a CSV describes a geometry it cannot otherwise carry, and emitting them
 * beside the geometry they were folded into would be two derived columns pretending to be data.
 * {@code includeHidden} overrides the second, never the first.
 *
 * <p>The SQL is assembled from the catalogue rather than written out, which means it interpolates
 * identifiers. That is safe here for a reason worth stating rather than assuming: only seeded
 * system rows can carry a {@code storageTable} or a column {@code storageTarget} — the create API
 * forces {@link AttributeStorage#JSONB} and nothing an administrator does can produce anything else
 * — so the interpolated names come from a closed set fixed by migration, not from user input.
 * {@link #IDENTIFIER} re-checks each one anyway, because "this can only ever hold safe values" is
 * an assumption that outlives the code that made it true.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetExportService {

    /**
     * Row cap.
     *
     * <p>An export is a file a human opens. Beyond this the answer is a database extract or a
     * scheduled report, not a browser download that takes four minutes and produces a spreadsheet
     * Excel refuses to open. Hitting the cap is reported to the caller rather than silently
     * truncating: a file that stops at a round number is one somebody will reconcile against.
     */
    public static final int MAX_ROWS = 100_000;

    /** Defence in depth on interpolated identifiers. See the class comment. */
    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]*$");

    private final LayerMetadataApi metadataApi;
    private final LayerMetadataService metadataService;
    private final EntityManager entityManager;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /** What an export produced: the column headings, the rows, and whether the cap was hit. */
    public record ExportData(
            Layer layer,
            List<AttributeDefinition> columns,
            List<Map<String, Object>> rows,
            boolean truncated
    ) {
    }

    /**
     * Reads a layer's assets into the shape a writer turns into CSV, Excel or GeoJSON.
     *
     * @param includeHidden   include attributes that are active but not visible
     * @param search          optional free text over asset code and name, mirroring the register's
     *                        own search so "export what I am looking at" is one call
     * @param geoJsonGeometry emit the geometry as a GeoJSON object rather than as WKT. The two
     *                        formats want different things from the same column and the database
     *                        produces either for the same cost, so the choice belongs in the query
     *                        rather than in a reparse afterwards.
     */
    @Transactional(readOnly = true)
    public ExportData read(UUID organizationId, UUID layerId, boolean includeHidden, String search,
                           boolean geoJsonGeometry) {
        Layer layer = metadataService.requireLayer(layerId, organizationId);
        List<AttributeDefinition> all = metadataApi.definitionsForLayer(organizationId, layerId);
        List<AttributeDefinition> columns = all.stream()
                .filter(d -> includeHidden || d.visible())
                .toList();
        if (columns.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    layer.getTitle() + " has no visible attributes to export. Make at least one "
                            + "attribute visible in Data Management, or export with hidden fields included.");
        }

        Select select = buildSelect(columns, geoJsonGeometry);
        /*
         * :search is cast inside concat as well as in the IS NULL guard. concat is variadic over
         * "any", so it gives Postgres nothing to infer an argument's type from, and the datasource
         * runs stringtype=unspecified — without the inner cast every non-empty search fails with
         * "could not determine data type of parameter" while the unfiltered export works.
         */
        String sql = """
                SELECT %s
                FROM gis.assets a
                %s
                WHERE a.organization_id = :organizationId
                  AND a.asset_type = :assetType
                  AND (cast(:search as text) IS NULL
                       OR a.asset_code ILIKE concat('%%', cast(:search as text), '%%')
                       OR a.name ILIKE concat('%%', cast(:search as text), '%%'))
                ORDER BY a.asset_code
                """.formatted(String.join(", ", select.projections()), select.join());

        Query query = entityManager.createNativeQuery(sql)
                .setParameter("organizationId", organizationId)
                .setParameter("assetType", layer.getAssetType().name())
                .setParameter("search", search == null || search.isBlank() ? null : search.trim())
                // One more than the cap, so "there were exactly MAX_ROWS" and "there were more" are
                // distinguishable without a second COUNT over the same predicate.
                .setMaxResults(MAX_ROWS + 1);

        @SuppressWarnings("unchecked")
        List<Object[]> raw = query.getResultList();
        boolean truncated = raw.size() > MAX_ROWS;
        List<Object[]> kept = truncated ? raw.subList(0, MAX_ROWS) : raw;

        List<Map<String, Object>> rows = new ArrayList<>(kept.size());
        for (Object[] tuple : kept) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                row.put(columns.get(i).fieldName(), normalise(tuple[i]));
            }
            rows.add(row);
        }

        log.info("Exported {} {} assets ({} columns) for org {}{}",
                rows.size(), layer.getAssetType(), columns.size(), organizationId,
                truncated ? " — capped at " + MAX_ROWS : "");
        return new ExportData(layer, columns, rows, truncated);
    }

    /** Records the extraction. An export moves a tenant's asset base outside the platform. */
    public void audit(UUID organizationId, UUID actorId, String actorName, ExportData data,
                      String format) {
        auditService.record(AuditEvent.builder()
                .organizationId(organizationId)
                .actorUserId(actorId)
                .actorUsername(actorName)
                .eventType(AuditEventTypes.ASSET_EXPORT_GENERATED)
                .category(AuditCategory.DATA)
                .resourceType("gis.assets")
                .resourceId(data.layer().getId().toString())
                .success(true)
                .message("Exported " + data.rows().size() + " " + data.layer().getTitle()
                        + " as " + format)
                .metadata(Map.of(
                        "layerCode", data.layer().getCode(),
                        "format", format,
                        "rows", data.rows().size(),
                        "columns", data.columns().size(),
                        "truncated", data.truncated()))
                .build());
    }

    // ---- SQL assembly -------------------------------------------------------------------------

    private record Select(List<String> projections, String join) {
    }

    /**
     * One projection per catalogue column, in catalogue order, plus at most one detail-table join.
     *
     * <p>At most one because a layer's typed detail rows all live in the same table —
     * {@code gis.tanks} for tanks, {@code gis.valves} for valves. If a layer ever declares
     * attributes across two, that is a catalogue error rather than something to silently
     * half-export, and it says so.
     */
    private static Select buildSelect(List<AttributeDefinition> columns, boolean geoJsonGeometry) {
        List<String> projections = new ArrayList<>(columns.size());
        String detailTable = null;

        for (AttributeDefinition column : columns) {
            switch (column.storage()) {
                case COLUMN -> projections.add("a." + identifier(column.resolvedTarget()));
                /*
                 * `->>` rather than `->`: the text projection is what a spreadsheet cell holds. The
                 * JSON form would export a string as "\"DI\"", quotes and all, in every row.
                 */
                case JSONB -> projections.add(
                        "a.attributes ->> '" + escapeLiteral(column.fieldName()) + "'");
                /*
                 * WKT for a spreadsheet, GeoJSON for a .geojson file. WKT is the form every GIS
                 * tool reads back out of a CSV cell; a GeoJSON export needs the geometry as an
                 * object so it can be the feature's own `geometry` member rather than a string
                 * inside its properties.
                 */
                case GEOMETRY -> projections.add("geom".equals(column.resolvedTarget())
                        ? (geoJsonGeometry ? "ST_AsGeoJSON(a.geom)" : "ST_AsText(a.geom)")
                        : coordinateProjection(column.resolvedTarget()));
                case TYPE_TABLE -> {
                    if (detailTable == null) {
                        detailTable = identifier(column.storageTable());
                    } else if (!detailTable.equals(identifier(column.storageTable()))) {
                        throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                                "The catalogue declares attributes on two different detail tables for "
                                        + "one layer (" + detailTable + " and " + column.storageTable()
                                        + "). One of them is wrong.");
                    }
                    projections.add("t." + identifier(column.resolvedTarget()));
                }
            }
        }
        String join = detailTable == null
                ? ""
                : "LEFT JOIN gis." + detailTable + " t ON t.asset_id = a.id";
        return new Select(projections, join);
    }

    /**
     * Longitude and latitude, when an export explicitly asks for hidden fields.
     *
     * <p>{@code ST_X}/{@code ST_Y} are only defined for points, so a line or polygon layer would
     * error rather than return null. {@code ST_Centroid} makes the column meaningful for every
     * geometry — the point a label would be placed at — which is what someone exporting
     * coordinates from a polygon layer is asking for.
     */
    private static String coordinateProjection(String target) {
        String function = "lon".equals(target) ? "ST_X" : "ST_Y";
        return function + "(ST_Centroid(a.geom))";
    }

    private static String identifier(String candidate) {
        if (candidate == null || !IDENTIFIER.matcher(candidate).matches()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "The catalogue holds '" + candidate + "' as a column name, which is not a legal "
                            + "identifier. Refusing to build a query from it.");
        }
        return candidate;
    }

    /**
     * A JSONB key as a SQL literal.
     *
     * <p>Field names are validated on the way in and cannot contain a quote, so this is belt and
     * braces — but the key cannot be a bind parameter without repeating the whole projection list
     * as positional parameters, and doubling a quote is cheaper than the alternative being wrong.
     */
    private static String escapeLiteral(String value) {
        return value.replace("'", "''");
    }

    /**
     * Makes a JDBC value safe to hand a writer.
     *
     * <p>The driver returns {@code java.sql.Date}, {@code Timestamp} and {@code PGobject} for
     * several of these columns, none of which a spreadsheet cell or a JSON serialiser handles the
     * way a reader expects. Converting once here keeps every writer free of driver types.
     */
    private Object normalise(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof java.time.temporal.TemporalAccessor || value instanceof Number
                || value instanceof Boolean || value instanceof String) {
            return value;
        }
        return value.toString();
    }

}

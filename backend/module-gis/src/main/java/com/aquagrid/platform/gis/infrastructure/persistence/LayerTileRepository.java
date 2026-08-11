package com.aquagrid.platform.gis.infrastructure.persistence;

import com.aquagrid.platform.gis.domain.enums.AttributeDataType;
import com.aquagrid.platform.gis.domain.metadata.FieldNamePolicy;
import com.aquagrid.platform.gis.domain.tile.TileCoordinate;
import com.aquagrid.platform.gis.domain.tile.TileFilter;
import com.aquagrid.platform.gis.infrastructure.config.GisTileProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds a layer's vector tile, carrying whatever attributes its style needs to read.
 *
 * <p>Separate from {@link AssetRepository} because it is the one query in the module whose
 * projection is not fixed. Attribute-based styling is a MapLibre expression evaluated in the tile
 * worker against the properties the tile carries, so a style that colours by {@code water_level}
 * only works if {@code water_level} is <em>in</em> the tile. The old fixed five-column projection —
 * id, code, type, name, status — could serve no classified style at all.
 *
 * <p>That makes the projection dynamic, which makes SQL injection the question. It is closed off
 * three times over, and the belt-and-braces is deliberate because this is the only place in the
 * module where an identifier reaches SQL as text:
 *
 * <ol>
 *   <li>The field names never come from the request. They are read from
 *       {@code gis.layer_attribute_master} — the Data Management catalogue — for the layer being
 *       tiled, so the only names that can appear are ones an administrator with
 *       {@code gis:metadata:manage} already created.</li>
 *   <li>Every name is re-checked against {@link FieldNamePolicy} here, at the point of use, rather
 *       than trusted because it was checked when it was stored. A row can arrive by restore, by
 *       script, or by a future module; the check that matters is the one next to the interpolation.</li>
 *   <li>Names are emitted only as a <em>literal inside a JSONB key lookup</em> and as a quoted
 *       output alias, never as a table or column identifier — and the grammar
 *       ({@code ^[a-z][a-z0-9_]*$}) admits no quote, space or semicolon that could escape either
 *       position.</li>
 * </ol>
 *
 * <p>Everything else — tenant, layer, asset type, z/x/y and every filter operand — is a bound
 * parameter. A {@link TileFilter} follows exactly the same three-part rule for its field name and
 * binds its values; see that class for why a predicate is resolved against the catalogue rather
 * than accepted from the request.
 */
@Repository
@RequiredArgsConstructor
public class LayerTileRepository {

    /**
     * How many catalogue attributes a tile will carry.
     *
     * <p>A tile is the map's hot path and every property is bytes on the wire for every feature in
     * every tile at every zoom. Styles reference a handful of fields; this ceiling exists so a
     * misconfigured style cannot turn the tile endpoint into a full-attribute export.
     */
    private static final int MAX_TILE_ATTRIBUTES = 12;

    private final JdbcTemplate jdbc;
    private final GisTileProperties properties;

    /**
     * One tile's PBF bytes.
     *
     * <p>Everything happens in Postgres: the spatial restriction, the reprojection (already
     * materialised in {@code geom_3857}), the clip to the tile grid and the MVT encoding. No
     * geometry crosses into Java, which is the property that makes the response O(viewport) rather
     * than O(layer).
     *
     * @param styledFields the catalogue attributes the layer's style reads, with their declared
     *                     types. Numeric and date types are cast in SQL so MapLibre's comparison
     *                     operators work on them — {@code attributes->>'x'} is text, and
     *                     {@code ['>', ['get','x'], 20]} against text is a comparison that silently
     *                     never matches.
     * @param filters      server-side predicates, already resolved against the layer's catalogue.
     *                     Applied in the {@code WHERE} clause rather than as a MapLibre filter so a
     *                     filtered view actually transfers fewer bytes.
     * @return the tile bytes, possibly zero-length when the tile holds no features. The caller
     *         substitutes a valid empty-layer MVT; see {@code GisService.getTile}.
     */
    public byte[] buildTile(UUID organizationId, UUID layerId, String assetType, String layerName,
                            TileCoordinate tile, Map<String, AttributeDataType> styledFields,
                            List<TileFilter> filters) {

        String projection = projectionFor(styledFields);

        /*
         * Bindings are assembled in the same pass that builds the clause, so a predicate's
         * placeholders and its values cannot get out of step — the failure mode where an added
         * operator silently shifts every later parameter by one and the tile filters on the wrong
         * value without erroring.
         */
        StringBuilder predicates = new StringBuilder();
        List<Object> filterBindings = new ArrayList<>();
        for (TileFilter filter : filters == null ? List.<TileFilter>of() : filters) {
            predicates.append("\n                      AND ").append(filter.toSql());
            filterBindings.addAll(filter.bindings());
        }

        String sql = """
                SELECT COALESCE(ST_AsMVT(tile.*, ?, %d, 'geom'), '\\x'::bytea)
                FROM (
                    SELECT a.id, a.asset_code, a.asset_type, a.name, a.status%s,
                           ST_AsMVTGeom(
                               a.geom_3857,
                               ST_TileEnvelope(?, ?, ?),
                               %d, %d, true
                           ) AS geom
                    FROM gis.assets a
                    WHERE a.organization_id = ?
                      AND (a.layer_id = ? OR (a.layer_id IS NULL AND a.asset_type = ?))
                      AND a.geom_3857 && ST_TileEnvelope(?, ?, ?)%s
                ) AS tile
                WHERE tile.geom IS NOT NULL
                """.formatted(properties.extent(), projection,
                properties.extent(), properties.buffer(), predicates);

        /*
         * ST_TileEnvelope appears twice — once as the clip target and once as the && operand — and
         * both are bound rather than shared through a CTE. A `WITH tile AS (...)` reads better but
         * Postgres materialises it, and the planner then has an opaque single row on the left of the
         * && instead of a constant it can turn into an index condition on ix_assets_geom_3857. The
         * duplicated call is what keeps the GiST index scan; the function itself is immutable and
         * costs nothing to evaluate twice.
         */
        List<Object> args = new ArrayList<>();
        args.add(layerName);
        args.add(tile.z()); args.add(tile.x()); args.add(tile.y());
        args.add(organizationId);
        args.add(layerId);
        args.add(assetType);
        args.add(tile.z()); args.add(tile.x()); args.add(tile.y());
        args.addAll(filterBindings);

        byte[] bytes = jdbc.queryForObject(sql, byte[].class, args.toArray());
        return bytes == null ? new byte[0] : bytes;
    }

    /**
     * The extra SELECT expressions, one per styled field.
     *
     * <p>Numeric attributes get a guarded cast rather than a bare one. A JSONB value is text until
     * something casts it and the catalogue's declared type is a contract about what <em>should</em>
     * be there, not a constraint Postgres enforced — one row imported before the field was retyped
     * is enough to make {@code ::double precision} abort the statement, which would blank every tile
     * in the viewport rather than mis-style one feature. The regex guard yields NULL for a value
     * that will not read as a number, and a MapLibre rule simply does not match a NULL.
     */
    private static String projectionFor(Map<String, AttributeDataType> styledFields) {
        if (styledFields == null || styledFields.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int emitted = 0;
        for (Map.Entry<String, AttributeDataType> entry : styledFields.entrySet()) {
            if (emitted >= MAX_TILE_ATTRIBUTES) {
                break;
            }
            String field = entry.getKey();
            // Re-validated at the point of use, not trusted from storage. See the class javadoc.
            if (!FieldNamePolicy.isValidFieldName(field)) {
                continue;
            }
            String jsonValue = "a.attributes ->> '" + field + "'";
            String expression = entry.getValue() != null && entry.getValue().isNumeric()
                    ? "CASE WHEN " + jsonValue + " ~ '^-?[0-9]+(\\.[0-9]+)?$' THEN ("
                      + jsonValue + ")::double precision END"
                    : jsonValue;
            out.append(", ").append(expression).append(" AS \"").append(field).append('"');
            emitted++;
        }
        return out.toString();
    }

    /**
     * Filters a set of field names down to the ones that are safe and useful to put in a tile.
     *
     * <p>Kept here rather than in the service so the rule and the interpolation stay in the same
     * file: the columns already in the fixed projection are dropped, because emitting
     * {@code attributes->>'status'} beside {@code a.status} would give a tile two properties of the
     * same name and MapLibre would read whichever the encoder wrote last.
     */
    public static Map<String, AttributeDataType> tileable(Map<String, AttributeDataType> fields) {
        Map<String, AttributeDataType> out = new LinkedHashMap<>();
        if (fields == null) {
            return out;
        }
        List<String> reserved = List.of("id", "asset_code", "asset_type", "name", "status", "geom");
        fields.forEach((name, type) -> {
            if (name != null && FieldNamePolicy.isValidFieldName(name) && !reserved.contains(name)) {
                out.put(name, type);
            }
        });
        return out;
    }
}

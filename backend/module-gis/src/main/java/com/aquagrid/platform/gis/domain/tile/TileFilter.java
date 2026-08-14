package com.aquagrid.platform.gis.domain.tile;

import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.gis.domain.enums.AttributeDataType;
import com.aquagrid.platform.gis.domain.enums.StyleOperator;
import com.aquagrid.platform.gis.domain.metadata.FieldNamePolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One server-side predicate applied to a layer's tile query.
 *
 * <p>Filtering a large layer has to happen in PostGIS. The alternative the client would otherwise
 * reach for — fetch the layer, filter in the browser — is the payload the whole vector-tile design
 * exists to avoid, and a MapLibre {@code filter} expression is not a substitute either: it hides
 * features the tile already carried, so the bytes were still transferred and the tile still cost
 * what it cost. A predicate in the {@code WHERE} clause is the only version that makes the response
 * smaller.
 *
 * <p>That puts a caller-supplied predicate next to SQL, so the shape of this type is dictated by
 * what must <em>not</em> be possible:
 *
 * <ul>
 *   <li><b>The field is whitelisted, never interpolated from the request.</b> A name is matched
 *       against the layer's Data Management catalogue (or the fixed set of {@code gis.assets}
 *       columns in {@link #CORE_COLUMNS}) and it is the <em>catalogue's</em> copy of the string that
 *       reaches SQL. A name that matches nothing is refused rather than passed through, so there is
 *       no path by which request text becomes an identifier.</li>
 *   <li><b>The operator is an enum constant.</b> Reusing {@link StyleOperator} rather than
 *       inventing a parallel vocabulary — it is the same list of comparisons Layer Style Management
 *       already validates rules against, and its {@code symbol()} strings are compile-time
 *       constants.</li>
 *   <li><b>The value is always a bound parameter.</b> It is the one part of a filter that is genuinely
 *       free text, and it never appears in the statement — only as a {@code ?}. {@code IN} and
 *       {@code BETWEEN} expand to a fixed number of placeholders derived from the operand count,
 *       not from the operand contents.</li>
 * </ul>
 *
 * <p>Belt and braces on top of that: the resolved field is re-checked against
 * {@link FieldNamePolicy} at the point the SQL fragment is built, for the same reason
 * {@code LayerTileRepository} re-checks its projection there — a catalogue row can arrive by
 * restore or by script, and the check that matters is the one next to the interpolation.
 */
public record TileFilter(String field, StyleOperator operator, List<String> values,
                         AttributeDataType dataType, boolean coreColumn) {

    /**
     * How many predicates one tile request may carry.
     *
     * <p>Each one is a term in the {@code WHERE} clause of the map's hot path. A handful expresses
     * every filter a layer panel can offer; an unbounded list is a way to make the planner do
     * arbitrary work per tile, of which a viewport issues dozens.
     */
    public static final int MAX_FILTERS = 5;

    /**
     * Columns of {@code gis.assets} a filter may name directly.
     *
     * <p>Deliberately a short fixed list rather than "any column". These are the four an operator
     * actually filters a layer by — status above all — and they are real columns, so the predicate
     * uses the indexes on them instead of reaching into the JSONB bag. Tenant, geometry and the
     * audit columns are absent: {@code organization_id} is set from the principal and must not be
     * expressible in a request at all, and the rest are not things a map filters on.
     */
    public static final Set<String> CORE_COLUMNS = Set.of("status", "asset_type", "asset_code", "name");

    /**
     * Parses {@code field:operator:value} into a validated predicate.
     *
     * <p>Colon-separated because the filter travels as a query parameter on a tile URL, which is
     * assembled by MapLibre from the source's template and must stay a plain string. Multi-operand
     * operators take their operands comma-separated in the value position
     * ({@code status:IN:IN_SERVICE,DAMAGED}), and a value containing a comma is therefore not
     * expressible with {@code IN} — accepted, because the alternative is an escaping scheme in a URL
     * segment, and {@code EQ} covers that case exactly.
     *
     * @param known the layer's active attributes, from Data Management, keyed by field name. The
     *              only source of legal non-core field names.
     */
    public static TileFilter parse(String raw, Map<String, AttributeDataType> known) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Filter is empty.");
        }
        // Limit 3 so a value may itself contain colons — a timestamp operand, most obviously.
        String[] parts = raw.split(":", 3);
        if (parts.length < 2) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Filter must be 'field:operator' or 'field:operator:value'.");
        }
        String requested = parts[0].trim().toLowerCase(Locale.ROOT);

        StyleOperator operator;
        try {
            operator = StyleOperator.valueOf(parts[1].trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notAnOperator) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Unsupported filter operator '" + parts[1].trim() + "'.");
        }

        /*
         * Resolution, not acceptance. `requested` is compared to the catalogue and then discarded —
         * what the filter carries is the catalogue's own string, so a name that survives is one an
         * administrator created rather than one a caller typed.
         */
        boolean core = CORE_COLUMNS.contains(requested);
        String resolved = null;
        AttributeDataType type = null;
        if (core) {
            resolved = CORE_COLUMNS.stream().filter(requested::equals).findFirst().orElseThrow();
            type = AttributeDataType.TEXT;
        } else {
            for (Map.Entry<String, AttributeDataType> entry : known.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equals(requested)) {
                    resolved = entry.getKey();
                    type = entry.getValue();
                    break;
                }
            }
        }
        if (resolved == null) {
            /*
             * The message names the field the caller sent, which is safe — it is echoed as text in a
             * JSON body, never as SQL — and is the difference between "your filter was rejected" and
             * a filter silently doing nothing, which on a map looks identical to no data.
             */
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "'" + requested + "' is not a filterable field on this layer.");
        }

        /*
         * A DATE attribute is stored as dd-MMM-yyyy text, which does not sort the way it reads —
         * "15-Feb-2024" lexically precedes "02-Jan-2025". DATE_TIME keeps working here: it is still
         * ISO text, and ISO text sorts the way it reads.
         */
        if (operator.isOrdered() && type == AttributeDataType.DATE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "'" + resolved + "' is a DATE field, so '" + operator.symbol()
                            + "' would compare it as text. Use = , != or IN for it.");
        }

        List<String> values = operands(parts.length == 3 ? parts[2] : "", operator);
        return new TileFilter(resolved, operator, values, type, core);
    }

    /** Splits and count-checks the operands for the operator's arity. */
    private static List<String> operands(String raw, StyleOperator operator) {
        List<String> values = new ArrayList<>();
        switch (operator.arity()) {
            case NONE -> {
                return List.of();
            }
            case LIST -> {
                for (String value : raw.split(",")) {
                    if (!value.isBlank()) {
                        values.add(value.trim());
                    }
                }
                if (values.isEmpty()) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "Filter operator " + operator + " needs at least one value.");
                }
            }
            case TWO -> {
                for (String value : raw.split(",", 2)) {
                    values.add(value.trim());
                }
                if (values.size() != 2) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "Filter operator " + operator + " needs two comma-separated values.");
                }
            }
            default -> {
                if (raw.isBlank()) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "Filter operator " + operator + " needs a value.");
                }
                values.add(raw.trim());
            }
        }
        return List.copyOf(values);
    }

    /**
     * The SQL fragment for this predicate, with a {@code ?} per operand.
     *
     * <p>A non-core field is read out of the attribute bag, and a numeric one gets the same guarded
     * cast the tile's projection uses: the catalogue's declared type is a contract about what should
     * be in the bag, not a constraint Postgres enforced, so a bare {@code ::double precision} would
     * let one badly-typed row abort every tile in the viewport. The guard yields NULL instead, and a
     * NULL simply fails the comparison — one feature missing from a filtered view rather than a blank
     * map.
     */
    public String toSql() {
        // Re-validated at the point of use, not trusted from storage. See the class javadoc.
        if (!coreColumn && !FieldNamePolicy.isValidFieldName(field)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Filter field is not a usable identifier.");
        }
        String operand = coreColumn
                ? "a." + field
                : "a.attributes ->> '" + field + "'";
        if (!coreColumn && dataType != null && dataType.isNumeric()) {
            operand = "CASE WHEN " + operand + " ~ '^-?[0-9]+(\\.[0-9]+)?$' THEN ("
                    + operand + ")::double precision END";
        }
        return switch (operator) {
            case IS_NULL -> "(" + operand + ") IS NULL";
            case IS_NOT_NULL -> "(" + operand + ") IS NOT NULL";
            case BETWEEN -> "(" + operand + ") BETWEEN " + placeholder() + " AND " + placeholder();
            case IN -> "(" + operand + ") IN ("
                    + String.join(", ", values.stream().map(v -> placeholder()).toList()) + ")";
            default -> "(" + operand + ") " + operator.symbol() + " " + placeholder();
        };
    }

    /**
     * The bind placeholder, cast to match the operand's type.
     *
     * <p>The cast is on the placeholder rather than left to inference because the datasource runs
     * {@code stringtype=unspecified}: an untyped parameter compared against a {@code double
     * precision} expression gives the planner nothing to infer from and fails with "could not
     * determine data type of parameter". This is the same rule §6 of AGENTS.md states for nullable
     * JPQL filters, in its JDBC form.
     */
    private String placeholder() {
        return !coreColumn && dataType != null && dataType.isNumeric()
                ? "cast(? as double precision)"
                : "cast(? as text)";
    }

    /** The values to bind, in the order {@link #toSql()} emits their placeholders. */
    public List<Object> bindings() {
        if (!coreColumn && dataType != null && dataType.isNumeric()) {
            List<Object> numbers = new ArrayList<>(values.size());
            for (String value : values) {
                try {
                    numbers.add(Double.valueOf(value));
                } catch (NumberFormatException notANumber) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "Filter on numeric field '" + field + "' needs a numeric value.");
                }
            }
            return numbers;
        }
        return List.copyOf(values);
    }

    /**
     * Parses a request's filters, or an empty list.
     *
     * @throws BusinessException when more than {@link #MAX_FILTERS} are supplied, or any one of them
     *                           is not valid — refused rather than dropped, because a filter that
     *                           silently does not apply shows the operator more features than they
     *                           asked to see and gives no sign of it.
     */
    public static List<TileFilter> parseAll(List<String> raw, Map<String, AttributeDataType> known) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if (raw.size() > MAX_FILTERS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "At most " + MAX_FILTERS + " filters may be applied to a tile.");
        }
        return raw.stream().map(one -> parse(one, known)).toList();
    }
}

package com.aquagrid.platform.gis.domain.metadata;

import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * What may be used as an attribute's field name.
 *
 * <p>A field name is not a label — it is an identifier that ends up as a JSON key, a column header
 * in an export, a DBF field in a shapefile, and, if the attribute is ever promoted to a real
 * column, a Postgres identifier. It therefore has to satisfy the strictest of those, not the
 * loosest, and it has to be settled at creation because renaming it later orphans every value
 * already written under it.
 *
 * <p>The rules, and why each one is here rather than left to the database:
 *
 * <ul>
 *   <li><b>Lower-case letters, digits and underscore only, starting with a letter.</b> Postgres
 *       folds unquoted identifiers to lower case, so {@code Meter_No} and {@code meter_no} are the
 *       same column but two different JSON keys — a distinction that produces two attributes the
 *       administrator believes are one. Normalising to lower case at the door removes the class of
 *       bug rather than the instance.</li>
 *   <li><b>No reserved words.</b> {@code SELECT * FROM t WHERE "order" = 1} works only if someone
 *       remembered to quote it. Every generated query, every export header, every future
 *       {@code ALTER TABLE} is a place someone might not, and the failure surfaces far from the
 *       screen where the name was typed.</li>
 *   <li><b>63 characters.</b> Postgres's {@code NAMEDATALEN - 1}. Longer names are silently
 *       truncated by the server, which turns two distinct fields into one.</li>
 * </ul>
 *
 * <p>The same grammar is a CHECK constraint on the table (V1330). This class produces the error a
 * human reads; the constraint stops a bad row arriving by a route that bypasses this class.
 */
public final class FieldNamePolicy {

    /** Postgres NAMEDATALEN - 1. A longer name is truncated by the server, silently. */
    public static final int MAX_LENGTH = 63;

    private static final Pattern VALID = Pattern.compile("^[a-z][a-z0-9_]*$");

    /**
     * SQL reserved and key words, plus the identifiers this platform's own generated SQL uses.
     *
     * <p>Sourced from the SQL:2016 reserved word list and Postgres 16's
     * {@code pg_get_keywords()} rows classed {@code reserved} or {@code type_func_name}, which is
     * the set that cannot appear unquoted where a column name is expected. Non-reserved keywords
     * ({@code name}, {@code type}, {@code value}…) are deliberately absent: they are legal
     * unquoted, they are exactly the words a utility wants for its fields, and banning them would
     * make the module annoying for no safety gained.
     *
     * <p>The platform's own additions at the end are not SQL keywords. {@code geom},
     * {@code geom_3857}, {@code attributes} and the audit columns are real columns on
     * {@code gis.assets}; an attribute claiming one of those names would be indistinguishable from
     * the column it shadows the moment anything joins the bag to the row.
     */
    private static final Set<String> RESERVED = Set.of(
            // SQL:2016 / Postgres reserved
            "all", "analyse", "analyze", "and", "any", "array", "as", "asc", "asymmetric",
            "authorization", "binary", "both", "case", "cast", "check", "collate", "collation",
            "column", "concurrently", "constraint", "create", "cross", "current_catalog",
            "current_date", "current_role", "current_schema", "current_time", "current_timestamp",
            "current_user", "default", "deferrable", "desc", "distinct", "do", "else", "end",
            "except", "false", "fetch", "for", "foreign", "freeze", "from", "full", "grant",
            "group", "having", "ilike", "in", "initially", "inner", "intersect", "into", "is",
            "isnull", "join", "lateral", "leading", "left", "like", "limit", "localtime",
            "localtimestamp", "natural", "not", "notnull", "null", "offset", "on", "only", "or",
            "order", "outer", "overlaps", "placing", "primary", "references", "returning", "right",
            "select", "session_user", "similar", "some", "symmetric", "system_user", "table",
            "tablesample", "then", "to", "trailing", "true", "union", "unique", "user", "using",
            "variadic", "verbose", "when", "where", "window", "with",

            // Real columns on gis.assets — an attribute of the same name would shadow one.
            "id", "organization_id", "geom", "geom_3857", "attributes",
            "created_at", "created_by", "updated_at", "updated_by", "version");

    private FieldNamePolicy() {
    }

    /**
     * Normalises and validates a proposed field name.
     *
     * <p>Normalisation is only case folding and trimming. It does not rewrite spaces to
     * underscores or strip punctuation: a name typed as {@code Meter No} is rejected with the rule
     * explained, rather than silently becoming {@code meter_no}. Guessing at a permanent
     * identifier on the operator's behalf is how you get a field nobody can find later because it
     * is not called what they typed.
     *
     * @return the normalised name to store
     * @throws BusinessException when the name cannot be used
     */
    public static String normaliseAndValidate(String proposed) {
        if (proposed == null || proposed.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Field name is required.");
        }
        String name = proposed.trim().toLowerCase();

        if (name.length() > MAX_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Field name is " + name.length() + " characters; the limit is " + MAX_LENGTH + ".");
        }
        if (!VALID.matcher(name).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Field name '" + proposed.trim() + "' is not valid. Use letters, numbers and "
                            + "underscore only, starting with a letter — for example 'consumer_no'.");
        }
        if (RESERVED.contains(name)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Field name '" + name + "' is a reserved word and cannot be used. "
                            + "Try a more specific name, such as '" + name + "_code'.");
        }
        return name;
    }

    /**
     * Whether a stored name still satisfies the grammar — a silent boolean, for the callers that
     * need a guard rather than an error message.
     *
     * <p>Its real use is at the one point in the module where a field name is interpolated into SQL
     * rather than bound: {@code LayerTileRepository} builds a tile's projection from the catalogue's
     * own field names, and re-checks each one here at the point of use rather than trusting that it
     * was checked when it was stored. A row can arrive by restore, by script or by a future module;
     * the check that matters is the one next to the interpolation.
     *
     * <p>Reserved words are deliberately not consulted. This asks whether a name is <em>safe and
     * well-formed</em>, which the pattern alone answers; the reserved list is a usability rule about
     * what an administrator may create, and applying it here would drop a legitimate stored field
     * out of a tile because the word list grew after it was made.
     */
    public static boolean isValidFieldName(String name) {
        return name != null && name.length() <= MAX_LENGTH && VALID.matcher(name).matches();
    }

    /** Whether a name is reserved, for the client-side hint on the create form. */
    public static boolean isReserved(String name) {
        return name != null && RESERVED.contains(name.trim().toLowerCase());
    }

    /** The reserved list, so the create form can warn before the round trip. */
    public static Set<String> reservedWords() {
        return RESERVED;
    }
}

package com.aquagrid.platform.iot.dataconfig.domain.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * The types a device parameter can declare, and the only place a received value is judged against
 * one.
 *
 * <p>Stored as VARCHAR + CHECK like every other enumeration in this schema — a PostgreSQL
 * {@code ENUM} needs {@code ALTER TYPE} and an exclusive lock to grow, and its ordinals break on
 * reorder.
 *
 * <p>The type list and the judge for each type live in one file because they are one decision, the
 * same reasoning {@code AttributeDataType} states for the GIS catalogue: adding {@code DURATION}
 * tomorrow means saying what a duration is <em>and</em> how a payload value becomes one, and
 * splitting those across two files is how a type ends up declarable but unjudgeable.
 * {@link #accepts} is exhaustive over the enum, so the compiler makes a new constant supply its own
 * rule.
 *
 * <h2>Why this does not throw</h2>
 *
 * <p>{@code AttributeDataType.coerce} rejects a bad value with a {@code BusinessException}, and that
 * is right for an import: the file is in front of an operator who can fix it and try again.
 * Telemetry has no such operator. A device in the ground sends what its firmware sends, the packet
 * cannot be re-requested, and a value that fails its declared type is still evidence — usually the
 * evidence that the parameter was configured wrongly. So the methods here return a verdict rather
 * than throwing one, the value is stored either way, and the verdict is written beside it as
 * {@link QualityStatus}.
 */
public enum ParameterDataType {

    /** Free text. Anything that arrived can be read as text, so this never fails. */
    TEXT,
    /** 32-bit signed whole number. */
    INTEGER,
    /** 64-bit signed whole number. */
    LONG_INTEGER,
    /** Exact decimal. Honours {@code decimalPrecision}. */
    DECIMAL,
    /** IEEE-754 double. Use {@link #DECIMAL} where exactness matters — volumes, billing registers. */
    DOUBLE,
    /** A 0/1 condition. Accepts the spellings devices actually send, not only {@code true}. */
    BOOLEAN,
    DATE,
    DATE_TIME,
    /** A nested object. Stored whole in the raw payload; not reduced to a reading. */
    JSON,
    /** A list. Stored whole in the raw payload; not reduced to a reading. */
    ARRAY;

    /** True when the type holds a number, so min/max and decimal precision mean something. */
    public boolean isNumeric() {
        return this == INTEGER || this == LONG_INTEGER || this == DECIMAL || this == DOUBLE;
    }

    /**
     * True when values of this type become rows in {@code iot.device_readings}.
     *
     * <p>That table stores a {@code DOUBLE PRECISION} value per metric, so only numbers and the 0/1
     * form of a boolean can land in it. A JSON object or an array is configured so that it has a
     * name, a description and a place in the catalogue — but the payload table is where its value
     * lives, and pretending otherwise would mean inventing a number for it.
     */
    public boolean isReading() {
        return isNumeric() || this == BOOLEAN;
    }

    /** True when {@code decimalPrecision} is meaningful. */
    public boolean usesPrecision() {
        return this == DECIMAL || this == DOUBLE;
    }

    /**
     * Whether a received value can be read as this type.
     *
     * <p>Absence is not a failure and is not this method's business: a null returns {@code true}
     * here, and whether the parameter had to be present is a separate question answered by
     * {@code isMandatory}. Conflating them would report a missing optional field as a type error.
     */
    public boolean accepts(Object value) {
        if (value == null) {
            return true;
        }
        return switch (this) {
            // Everything has a string form, so a text parameter cannot be violated by a value.
            case TEXT -> true;
            case INTEGER -> asLong(value) != null
                    && asLong(value) >= Integer.MIN_VALUE && asLong(value) <= Integer.MAX_VALUE;
            case LONG_INTEGER -> asLong(value) != null;
            case DECIMAL, DOUBLE -> asDouble(value) != null;
            case BOOLEAN -> asBoolean(value) != null;
            case DATE -> parses(value, LocalDate::parse);
            // The space-separated form is accepted because devices send it far more often than the
            // ISO 'T'. Refusing it would mark a whole fleet's timestamps INVALID over a separator.
            case DATE_TIME -> parses(value, raw -> LocalDateTime.parse(raw.replace(' ', 'T')));
            case JSON -> value instanceof java.util.Map || node(value, JsonNode::isObject);
            case ARRAY -> value instanceof java.util.Collection || node(value, JsonNode::isArray);
        };
    }

    /**
     * The numeric form of a value, for the reading row and the range check, or null if it has none.
     *
     * <p>Booleans become 1.0 and 0.0 because that is how {@code iot.device_readings} has stored
     * every flag since V1400, and because the alarm rules read them that way.
     */
    public Double toReadingValue(Object value) {
        if (value == null) {
            return null;
        }
        if (this == BOOLEAN) {
            Boolean flag = asBoolean(value);
            return flag == null ? null : flag ? 1.0 : 0.0;
        }
        return isNumeric() ? asDouble(value) : null;
    }

    /**
     * Applies the declared decimal precision.
     *
     * <p>Rounded, never rejected. A device reporting 12.3456 for a parameter declared to two places
     * has more resolution than the configuration, not wrong data, and marking it INVALID would
     * throw away the 12.35 that is genuinely wanted. This is the same call
     * {@code AttributeDataType.parseDecimal} makes for imported values, for the same reason.
     */
    public Double round(Double value, Integer decimalPrecision) {
        if (value == null || decimalPrecision == null || !usesPrecision()) {
            return value;
        }
        return BigDecimal.valueOf(value)
                .setScale(decimalPrecision, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /**
     * Guesses the type of a value nobody has configured.
     *
     * <p>Feeds the "Detected Data Type" column on the discovery screen and pre-fills the
     * configuration form. A guess, and presented as one: it is derived from a single observed
     * value, so a counter that happened to read 42 on its first sighting will be guessed
     * {@code INTEGER} and is the administrator's to correct.
     */
    public static ParameterDataType detect(Object value) {
        if (value == null) {
            return TEXT;
        }
        if (value instanceof Boolean) {
            return BOOLEAN;
        }
        if (value instanceof java.util.Map) {
            return JSON;
        }
        if (value instanceof java.util.Collection) {
            return ARRAY;
        }
        if (value instanceof Number number) {
            double d = number.doubleValue();
            // Whole-valued doubles are reported as DOUBLE rather than INTEGER: JSON has one number
            // type, so a flow rate that happens to read exactly 8.0 this once is indistinguishable
            // from a count, and guessing INTEGER would give the operator a form that truncates it.
            return (value instanceof Integer || value instanceof Long || value instanceof Short)
                    ? (d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE ? INTEGER : LONG_INTEGER)
                    : DOUBLE;
        }
        String text = String.valueOf(value).trim();
        if (asBoolean(text) != null) {
            return BOOLEAN;
        }
        if (asDouble(text) != null) {
            return DOUBLE;
        }
        if (parses(text, LocalDate::parse)) {
            return DATE;
        }
        if (parses(text, raw -> LocalDateTime.parse(raw.replace(' ', 'T')))) {
            return DATE_TIME;
        }
        return TEXT;
    }

    /** Resolves a name from the API or the database; null when it names no type. */
    public static ParameterDataType from(String name) {
        if (name == null) {
            return null;
        }
        for (ParameterDataType type : values()) {
            if (type.name().equalsIgnoreCase(name.trim())) {
                return type;
            }
        }
        return null;
    }

    // ---- Readers ------------------------------------------------------------------------------

    private static Long asLong(Object value) {
        Double d = asDouble(value);
        // A fractional value is not a whole number, and rounding it here would silently record 8.6
        // litres as 9 under a parameter someone typed the wrong data type for.
        return d == null || d != Math.floor(d) || d.isInfinite() ? null : d.longValue();
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof Boolean flag) {
            return flag ? 1.0 : 0.0;
        }
        try {
            return Double.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * The boolean spellings devices actually send.
     *
     * <p>{@code Boolean.parseBoolean} maps everything that is not "true" to false, silently — which
     * would turn a column of "Y"/"N" pump statuses into a column of all-clear and look like healthy
     * data. Anything outside this list is not a boolean.
     */
    private static Boolean asBoolean(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            double d = number.doubleValue();
            return d == 0.0 ? Boolean.FALSE : d == 1.0 ? Boolean.TRUE : null;
        }
        return switch (String.valueOf(value).trim().toUpperCase()) {
            case "TRUE", "T", "YES", "Y", "ON", "1" -> Boolean.TRUE;
            case "FALSE", "F", "NO", "N", "OFF", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static boolean parses(Object value, java.util.function.Consumer<String> parser) {
        try {
            parser.accept(String.valueOf(value).trim());
            return true;
        } catch (DateTimeParseException | IllegalArgumentException notADate) {
            return false;
        }
    }

    private static boolean node(Object value, java.util.function.Predicate<JsonNode> test) {
        return value instanceof JsonNode json && test.test(json);
    }
}

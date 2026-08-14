package com.aquagrid.platform.gis.domain.enums;

import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * The vocabulary of types an attribute can declare, and the only place a text value from an
 * imported file becomes a typed one.
 *
 * <p>Stored as VARCHAR + CHECK, like every other enumeration in this schema — a PG {@code ENUM}
 * needs {@code ALTER TYPE} and an exclusive lock to grow, and its ordinals break on reorder.
 *
 * <p>Two things are deliberately co-located here. The type list and the parser for each type sit
 * in one file because they are one decision: adding {@code CURRENCY} tomorrow means saying what a
 * currency is *and* how a CSV cell becomes one, and splitting those across two files is how a type
 * ends up declarable but unparseable. {@link #coerce} is therefore exhaustive over the enum, and
 * the compiler enforces that a new constant supplies its own conversion.
 *
 * <p>Coercion failures are {@link BusinessException}s carrying the field's display name and the
 * offending value, because they surface one per row in an import report that an operator has to
 * act on. "Cannot parse" with no value in it is a message that sends someone back to the file to
 * guess.
 */
public enum AttributeDataType {

    /** Variable-length text. Honours {@code maxLength}. */
    TEXT,
    /** 32-bit signed. */
    INTEGER,
    /** 64-bit signed. */
    LONG_INTEGER,
    /** 16-bit signed. */
    SHORT_INTEGER,
    /** Exact decimal. Honours {@code precision} and {@code scale}. */
    DECIMAL,
    /** IEEE-754 double. Use {@link #DECIMAL} where exactness matters — volumes, money, readings. */
    DOUBLE,
    BOOLEAN,
    DATE,
    DATE_TIME,
    TIME,
    UUID,
    /**
     * Geometry. Never coerced from a mapped column: the geometry comes from the source file's own
     * feature, or from the lon/lat pseudo-fields on a CSV. Present so the catalogue can describe
     * the geometry column rather than pretending a layer has none.
     */
    GEOMETRY;

    /**
     * The one format a {@code DATE} attribute is ever written or read in, across import, export,
     * the Data Management form and every asset form the catalogue drives — {@code 02-Jan-2025}.
     *
     * <p>Case-insensitive on parse so "02-JAN-2025" from an all-caps source spreadsheet is not
     * rejected for a reason no operator would guess, while every stored and displayed value comes
     * back out through the same formatter and is therefore always title case.
     */
    public static final DateTimeFormatter DATE_FORMAT = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("dd-MMM-yyyy")
            .toFormatter(Locale.ENGLISH);

    /**
     * Parses a value already coerced by {@link #DATE}'s {@link #coerce}.
     *
     * <p>Used where a {@code DATE} attribute is routed onto a native {@code LocalDate} column
     * (see {@code AttributeBinder}) rather than into the JSONB bag — the value has already passed
     * {@link #coerce} by that point, so failure here would mean the two disagree about the format,
     * not that the source data is bad.
     */
    public static LocalDate parseStoredDate(String text) {
        return LocalDate.parse(text, DATE_FORMAT);
    }

    /** True when {@code maxLength} is meaningful for this type. */
    public boolean usesLength() {
        return this == TEXT;
    }

    /** True when {@code precision} and {@code scale} are meaningful for this type. */
    public boolean usesPrecisionScale() {
        return this == DECIMAL;
    }

    /** True when the type holds a number, and so a MIN_VALUE/MAX_VALUE rule can apply to it. */
    public boolean isNumeric() {
        return this == INTEGER || this == LONG_INTEGER || this == SHORT_INTEGER
                || this == DECIMAL || this == DOUBLE;
    }

    /**
     * Converts one raw text value — a CSV cell, a GeoJSON property, a form field — into the typed
     * value that will be stored.
     *
     * <p>Blank is not an error and not an empty string: it is absence, returned as {@code null} so
     * the caller can apply a default or fail a mandatory check with a message about the *field*
     * rather than about the parse. Writing "" into the attribute bag would make "column present but
     * empty" indistinguishable from "column mapped and populated", which later reads as data.
     *
     * @param raw       the source value; may be null or blank
     * @param label     the attribute's display name, used in the error message
     * @param maxLength for TEXT, the configured maximum; ignored otherwise
     * @param precision for DECIMAL, total significant digits; ignored otherwise
     * @param scale     for DECIMAL, digits after the point; ignored otherwise
     * @return the typed value, or {@code null} when the source value is absent
     */
    public Object coerce(String raw, String label, Integer maxLength, Integer precision, Integer scale) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        return switch (this) {
            case TEXT -> {
                if (maxLength != null && value.length() > maxLength) {
                    throw reject(label, value,
                            "is " + value.length() + " characters; the field allows " + maxLength);
                }
                yield value;
            }
            case SHORT_INTEGER -> parseIntegral(value, label, Short.MIN_VALUE, Short.MAX_VALUE);
            case INTEGER -> parseIntegral(value, label, Integer.MIN_VALUE, Integer.MAX_VALUE);
            case LONG_INTEGER -> parseIntegral(value, label, Long.MIN_VALUE, Long.MAX_VALUE);
            case DECIMAL -> parseDecimal(value, label, precision, scale);
            case DOUBLE -> {
                try {
                    yield Double.valueOf(value);
                } catch (NumberFormatException e) {
                    throw reject(label, value, "is not a number");
                }
            }
            case BOOLEAN -> parseBoolean(value, label);
            case DATE -> {
                try {
                    yield LocalDate.parse(value, DATE_FORMAT).format(DATE_FORMAT);
                } catch (DateTimeParseException e) {
                    throw reject(label, value, "is not a date in dd-MMM-yyyy form (for example 02-Jan-2025)");
                }
            }
            case DATE_TIME -> {
                try {
                    yield LocalDateTime.parse(value.replace(' ', 'T')).toString();
                } catch (DateTimeParseException e) {
                    throw reject(label, value, "is not a date and time in YYYY-MM-DDTHH:MM:SS form");
                }
            }
            case TIME -> {
                try {
                    yield LocalTime.parse(value).toString();
                } catch (DateTimeParseException e) {
                    throw reject(label, value, "is not a time in HH:MM or HH:MM:SS form");
                }
            }
            case UUID -> {
                try {
                    yield java.util.UUID.fromString(value).toString();
                } catch (IllegalArgumentException e) {
                    throw reject(label, value, "is not a UUID");
                }
            }
            case GEOMETRY -> throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Geometry is read from the source file's own feature, not from a mapped column, "
                            + "so '" + label + "' cannot be filled from one.");
        };
    }

    /**
     * Integral parsing with an explicit range check.
     *
     * <p>{@code Short.parseShort} on 40000 throws {@code NumberFormatException} with the message
     * "Value out of range", which is indistinguishable in a log from "not a number" and sends the
     * operator hunting for a typo in a value that is simply too big for the field they chose. The
     * two cases get different sentences here.
     */
    private static Long parseIntegral(String value, String label, long min, long max) {
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw reject(label, value, "is not a whole number");
        }
        if (parsed < min || parsed > max) {
            throw reject(label, value, "is outside the range this field holds (" + min + " to " + max + ")");
        }
        return parsed;
    }

    /**
     * Decimal parsing that respects the declared precision and scale.
     *
     * <p>Scale is applied by rounding HALF_UP rather than by rejecting: a source file that carries
     * 12.3456 for a field declared to two places has more resolution than the field, not wrong
     * data, and refusing the row would lose the 12.35 that is genuinely wanted. Precision is
     * rejected, because a value with more integer digits than the field holds is not a rounding
     * question — it is a different magnitude, and quietly truncating a 12-digit capacity into a
     * 10-digit field is how a reservoir ends up a hundredth of its size.
     */
    private static BigDecimal parseDecimal(String value, String label, Integer precision, Integer scale) {
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw reject(label, value, "is not a decimal number");
        }
        if (scale != null) {
            parsed = parsed.setScale(scale, RoundingMode.HALF_UP);
        }
        if (precision != null && parsed.precision() - parsed.scale() > precision - (scale == null ? 0 : scale)) {
            throw reject(label, value,
                    "has more digits before the decimal point than the field's precision of " + precision + " allows");
        }
        return parsed;
    }

    /**
     * Boolean parsing over the spellings survey deliverables actually use.
     *
     * <p>{@code Boolean.parseBoolean} maps everything that is not "true" to false, silently, which
     * would turn a column of "Y"/"N" into a column of all-false and look like clean data. Anything
     * outside this list is rejected rather than guessed.
     */
    private static Boolean parseBoolean(String value, String label) {
        return switch (value.toUpperCase()) {
            case "TRUE", "T", "YES", "Y", "1" -> Boolean.TRUE;
            case "FALSE", "F", "NO", "N", "0" -> Boolean.FALSE;
            default -> throw reject(label, value, "is not a yes/no value");
        };
    }

    private static BusinessException reject(String label, String value, String why) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED,
                label + ": '" + value + "' " + why + ".");
    }
}

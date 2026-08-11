package com.aquagrid.platform.gis.domain.enums;

/**
 * Value constraints expressible per attribute.
 *
 * <p>Deliberately a short, closed list. The temptation with a metadata module is to make the rule
 * language general — an expression evaluator, a scripting hook — and the cost is that every rule
 * becomes something only its author can read, running with the importer's privileges over the
 * tenant's data. These six cover what utility field definitions actually constrain, each one is
 * checkable in a line, and none of them can do anything but accept or reject a value.
 */
public enum ValidationRuleType {

    /** Minimum character count. Text only. */
    MIN_LENGTH,
    /** Maximum character count, tighter than the field's own {@code maxLength}. Text only. */
    MAX_LENGTH,
    /** Inclusive lower bound. Numeric types only. */
    MIN_VALUE,
    /** Inclusive upper bound. Numeric types only. */
    MAX_VALUE,
    /** A regular expression the whole value must match. */
    PATTERN,
    /** A comma-separated list of the only accepted values. */
    ALLOWED_VALUES
}

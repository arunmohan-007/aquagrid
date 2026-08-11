package com.aquagrid.platform.gis.domain.enums;

/**
 * How a style decides which symbol a feature gets.
 *
 * <p>Not cosmetic: the type selects which MapLibre expression
 * {@code MapLibreStyleComposer} emits, and which columns of {@code gis.layer_style_rule} carry
 * meaning. A categorical rule holds a value; a graduated rule holds a band; a rule-based rule holds
 * a predicate. One rule table serves all three because they differ in how they are read, not in
 * what they are.
 */
public enum StyleType {

    /** One symbol for every feature. What every layer starts with. */
    SIMPLE,

    /**
     * One symbol per value of {@code classifyField} — {@code status} ACTIVE green, FAULT red.
     * Composed as a MapLibre {@code match}, which is the expression built for exact-value dispatch
     * and is evaluated as a lookup rather than as a chain of comparisons.
     */
    CATEGORICAL,

    /**
     * One symbol per numeric band of {@code classifyField} — water level 0–20, 20–50, 50–100.
     * Composed as a MapLibre {@code step}, which requires ascending, non-overlapping bounds and is
     * why the service sorts and checks them rather than trusting the order they were entered in.
     */
    GRADUATED,

    /**
     * Arbitrary predicates, each naming its own field and operator, first match wins. The general
     * case the other two are readable special cases of — kept separate rather than collapsed into
     * it because "colour by status" is what an administrator actually wants nine times in ten, and
     * making them express it as three predicates would be a worse product for the sake of one
     * fewer enum constant.
     */
    RULE_BASED;

    /** Whether {@code classifyField} must be set — the field the rules all read. */
    public boolean requiresClassifyField() {
        return this == CATEGORICAL || this == GRADUATED;
    }

    /** Whether this style draws its symbol from rules at all. */
    public boolean isClassified() {
        return this != SIMPLE;
    }
}

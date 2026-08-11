package com.aquagrid.platform.gis.domain.enums;

/**
 * The comparisons a style rule can make against an attribute's value.
 *
 * <p>Each constant carries its own arity so validation, the rule builder's form and the CHECK
 * constraint in V1333 all agree on how many operands a rule needs. A {@code BETWEEN} missing its
 * upper bound composes into a MapLibre expression that throws inside the tile worker and blanks the
 * map — a very long way from the dialog where the mistake was made — so the arity is declared once
 * here and enforced everywhere from it.
 */
public enum StyleOperator {

    EQ("=", Arity.ONE),
    NEQ("!=", Arity.ONE),
    LT("<", Arity.ONE),
    LTE("<=", Arity.ONE),
    GT(">", Arity.ONE),
    GTE(">=", Arity.ONE),
    IN("IN", Arity.LIST),
    BETWEEN("BETWEEN", Arity.TWO),
    IS_NULL("IS NULL", Arity.NONE),
    IS_NOT_NULL("IS NOT NULL", Arity.NONE);

    /** How many operands the operator consumes. */
    public enum Arity { NONE, ONE, TWO, LIST }

    private final String symbol;
    private final Arity arity;

    StyleOperator(String symbol, Arity arity) {
        this.symbol = symbol;
        this.arity = arity;
    }

    /** How the operator is written in the rule builder, e.g. {@code >=}. */
    public String symbol() {
        return symbol;
    }

    public Arity arity() {
        return arity;
    }

    /**
     * Whether the operator compares magnitudes, and therefore needs a numeric or date attribute.
     *
     * <p>{@code >} on a TEXT field is not an error Postgres would refuse — it would collate — but in
     * a style rule it is always a mistake: an administrator writing {@code status > 'ACTIVE'} means
     * something the renderer cannot guess. The service refuses it against the catalogue's declared
     * data type rather than composing an expression that quietly compares strings.
     */
    public boolean isOrdered() {
        return this == LT || this == LTE || this == GT || this == GTE || this == BETWEEN;
    }
}

package com.aquagrid.platform.gis.domain.enums;

/**
 * A layer's lifecycle state. There is no delete.
 *
 * <p>The same rule Data Management applies to attributes, and for a stronger reason: an attribute's
 * values survive in the JSONB bag, but a layer's features are rows of surveyed geometry that cost a
 * contractor a season to collect. Withdrawing a layer must never be able to take them with it, so
 * the registry withdraws the <em>layer</em> and leaves {@code gis.assets} untouched. Every state
 * below is reversible and none of them removes anything.
 */
public enum LayerStatus {

    /** Drawn, queried, imported into, exported from. The normal state. */
    ACTIVE,

    /**
     * Temporarily withdrawn. Off the map and out of the import hub, but still listed in the
     * registry and still holding every feature — the state for a layer being re-surveyed, whose
     * data is not to be trusted this month but will be next.
     */
    INACTIVE,

    /**
     * Retired. Hidden from the ordinary layer list as well as the map, and shown only when the
     * registry is asked for archived layers explicitly.
     *
     * <p>Distinct from {@link #INACTIVE} because the two answer different questions. Inactive says
     * "not now"; archived says "not again, but the record stands". Collapsing them would mean an
     * administrator scanning the registry for what to re-enable has to read every retired layer of
     * the last five years to find the one that is genuinely paused.
     */
    ARCHIVED;

    /** Whether the map, the tile endpoint and the import hub should offer this layer. */
    public boolean isUsable() {
        return this == ACTIVE;
    }
}

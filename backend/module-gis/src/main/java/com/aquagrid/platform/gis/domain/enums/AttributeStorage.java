package com.aquagrid.platform.gis.domain.enums;

/**
 * Where an attribute's value physically lives.
 *
 * <p>This is what lets the module create attributes at runtime without granting the application
 * DDL rights on its own schema: a new attribute is a {@link #JSONB} key in the bag
 * {@code gis.assets.attributes} that has existed since V1300, not a new column. Everything the
 * platform's code already reads by name is described by one of the other three, so the catalogue
 * covers the whole layer rather than only the part that happens to be dynamic.
 */
public enum AttributeStorage {

    /** A key in {@code gis.assets.attributes}. The default, and the only kind an administrator creates. */
    JSONB,

    /** A real column on {@code gis.assets}, named by {@code storageTarget}. */
    COLUMN,

    /**
     * The geometry column, or the {@code lon}/{@code lat} pseudo-fields a CSV point import is
     * folded into. Never written to the attribute bag.
     */
    GEOMETRY,

    /**
     * A column on a strongly-typed detail table ({@code gis.tanks}, {@code gis.valves} …), named by
     * {@code storageTable} + {@code storageTarget}.
     *
     * <p>Catalogued and exported, but not offered for import mapping. Those tables carry
     * constraints the supertype import cannot satisfy on its own — a tank row needs
     * {@code capacity_m3} NOT NULL and positive, a pipeline row needs its own LineString — so a
     * generic writer would have to weaken them or fail on most files. They are written through
     * their own typed endpoints and appear here so the catalogue describes the layer completely.
     */
    TYPE_TABLE;

    /** True when the importer can write this attribute from a mapped source column. */
    public boolean isImportable() {
        return this == JSONB || this == COLUMN || this == GEOMETRY;
    }
}

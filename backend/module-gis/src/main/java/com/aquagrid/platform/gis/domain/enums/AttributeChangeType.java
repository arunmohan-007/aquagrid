package com.aquagrid.platform.gis.domain.enums;

/** What happened to an attribute definition, as recorded in {@code gis.layer_attribute_history}. */
public enum AttributeChangeType {
    CREATED,
    UPDATED,
    /** Soft delete. The definition and every value written under it survive; it stops being read. */
    DEACTIVATED,
    REACTIVATED
}

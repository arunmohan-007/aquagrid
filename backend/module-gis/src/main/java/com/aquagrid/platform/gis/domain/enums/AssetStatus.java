package com.aquagrid.platform.gis.domain.enums;

/** Lifecycle state of a spatial asset. Mirrors the {@code ck_assets_status} CHECK constraint. */
public enum AssetStatus {
    PLANNED,
    IN_SERVICE,
    OUT_OF_SERVICE,
    DECOMMISSIONED,
    DAMAGED
}

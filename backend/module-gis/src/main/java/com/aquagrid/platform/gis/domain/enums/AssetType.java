package com.aquagrid.platform.gis.domain.enums;

/**
 * The kind of spatial asset. Stored as VARCHAR + CHECK in the database; mirrored here so the
 * application layer has a type-safe vocabulary. Adding a type is a migration that extends the
 * CHECK and inserts a row here — never an ENUM {@code ALTER TYPE}.
 */
public enum AssetType {
    METER,
    VALVE,
    PIPELINE,
    HYDRANT,
    /** Over head tank. The enum name predates the label and is not worth a data migration. */
    TANK,
    RESERVOIR,
    PUMP_STATION,
    /** Dug well — a wide-mouthed shaft, surveyed either as a point or as a footprint. */
    OPEN_WELL,
    /** Borehole. Always a single point; there is no footprint to digitise. */
    BORE_WELL,
    DMA,
    /** Local-government boundary. Imported alongside DMAs but administratively distinct. */
    PANCHAYAT,
    SERVICE_CONNECTION,
    SENSOR,
    /**
     * A layer an administrator created through Layer Management, rather than a type the platform's
     * own code knows about.
     *
     * <p>Added once, in V1332, and deliberately not one constant per custom layer: this enum is the
     * discriminator the typed detail tables, the network trace and the dashboard dispatch on, so
     * growing it per layer would make creating a layer a release — which is the thing Layer
     * Management exists to stop. What separates one custom layer's features from another's is
     * {@code gis.assets.layer_id}, not this.
     *
     * <p>Inert everywhere the others are meaningful: no detail table, no trace participation, no
     * dashboard aggregate. That is the correct behaviour, not a gap — the platform cannot know what
     * a utility's "Roads" layer means, only that it must draw it, query it and export it.
     */
    CUSTOM
}

package com.aquagrid.platform.gis.domain.enums;

import java.util.Map;
import java.util.Optional;

/**
 * The spellings of an asset type that real registers use but the enum does not.
 *
 * <p>Lifted out of {@code BulkImportService} when the importer became metadata-driven: the
 * knowledge is about what an asset type is called, not about how a file is read, and both the
 * importer and anything that later accepts a typed value from a form need it.
 *
 * <p>Deliberately short. This is for wording that is unambiguous and common in Tamil Nadu water
 * inventories, not a general synonym dictionary — a well inventory says "Open Well", a tank
 * register says "OHT", and both used to fail an import with an enum-parse error on a column the
 * operator had mapped precisely because it was meaningful. Anything not listed is not an error
 * either: the wizard already knows what is being imported, so the chosen type stands and the
 * source's own wording is kept where the operator can see it.
 */
public final class AssetTypeVocabulary {

    private static final Map<String, AssetType> ALIASES = Map.of(
            "OHT", AssetType.TANK,
            "OVER_HEAD_TANK", AssetType.TANK,
            "OVERHEAD_TANK", AssetType.TANK,
            "OPENWELL", AssetType.OPEN_WELL,
            "OPEN_WELLS", AssetType.OPEN_WELL,
            "BOREWELL", AssetType.BORE_WELL,
            "BORE_WELLS", AssetType.BORE_WELL,
            "TUBE_WELL", AssetType.BORE_WELL,
            "TUBEWELL", AssetType.BORE_WELL);

    private AssetTypeVocabulary() {
    }

    /**
     * Resolves a source file's wording to an asset type.
     *
     * <p>Normalises case, spaces and hyphens, checks the enum, then the alias table.
     *
     * @return the resolved type, or empty when the wording is not recognised — which is a signal to
     *         keep the caller's chosen type and preserve the raw value, not to fail the row.
     */
    public static Optional<AssetType> resolve(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return Optional.empty();
        }
        String normalised = rawValue.trim().toUpperCase().replaceAll("[^A-Z0-9]+", "_");
        try {
            return Optional.of(AssetType.valueOf(normalised));
        } catch (IllegalArgumentException notAnEnumConstant) {
            return Optional.ofNullable(ALIASES.get(normalised));
        }
    }
}

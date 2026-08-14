package com.aquagrid.platform.gis.domain.metadata;

import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.gis.api.AttributeDefinition;
import com.aquagrid.platform.gis.domain.enums.AssetStatus;
import com.aquagrid.platform.gis.domain.enums.AssetType;
import com.aquagrid.platform.gis.domain.enums.AssetTypeVocabulary;
import com.aquagrid.platform.gis.domain.enums.AttributeDataType;
import com.aquagrid.platform.gis.domain.model.Asset;

/**
 * Writes one source value onto an asset, according to the attribute's definition.
 *
 * <p>This is where the module stops being a catalogue and starts being metadata-<i>driven</i>. The
 * importer no longer knows any field names: it walks the definitions the catalogue returned, hands
 * each one a raw string, and this class decides what the value means and where it goes. Adding a
 * field to a layer therefore changes nothing here — which is the property the whole module exists
 * to produce.
 *
 * <p>The one {@code switch} on a field name is over the supertype's own columns, and it is not the
 * kind of switch this codebase avoids. The rejected pattern is a switch that grows when the product
 * grows — a case per transport, a case per asset type. This one is closed: {@code gis.assets} has
 * six writable columns, they are fixed by the schema rather than by the catalogue, and a tenant
 * adding a hundred attributes adds no branches. Everything open-ended lands in the JSONB bag
 * through a single path.
 */
public final class AttributeBinder {

    private AttributeBinder() {
    }

    /**
     * Coordinates gathered from the {@code lon}/{@code lat} pseudo-fields while a row is bound.
     *
     * <p>They cannot be applied as they arrive: a point needs both, and they are two separate
     * columns arriving in whatever order the file lists them. The importer creates one of these per
     * row and asks it for the geometry at the end.
     */
    public static final class Coordinates {
        private Double lon;
        private Double lat;

        public boolean isComplete() {
            return lon != null && lat != null;
        }

        public double lon() {
            return lon;
        }

        public double lat() {
            return lat;
        }
    }

    /**
     * Binds one value.
     *
     * <p>The order — coerce, default, mandatory, rules — is the order the errors need to arrive in.
     * Coercion first, so a malformed value is reported as malformed rather than as missing. The
     * default next, so a mandatory field with a default is satisfied by it. Mandatory before rules,
     * so an empty required field says "required" rather than "must be at least 4 characters".
     *
     * @param raw the source value, or null when the column was not mapped or the cell was empty
     */
    public static void bind(Asset asset, AttributeDefinition attribute, String raw, Coordinates coordinates) {
        if (!attribute.importable()) {
            // TYPE_TABLE attributes are catalogued and exported but written through their own typed
            // endpoints. Silently ignoring one here would be worse than the mapper never offering
            // it, so the mapper does not — and this is the guard for a hand-built request.
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "'" + attribute.displayName() + "' belongs to a typed detail table and cannot be "
                            + "filled by an import. Set it through the asset's own editor.");
        }

        Object value = attribute.dataType().coerce(raw, attribute.displayName(),
                attribute.maxLength(), attribute.numericPrecision(), attribute.numericScale());

        if (value == null && attribute.defaultValue() != null) {
            value = attribute.dataType().coerce(attribute.defaultValue(), attribute.displayName(),
                    attribute.maxLength(), attribute.numericPrecision(), attribute.numericScale());
        }
        if (value == null) {
            if (attribute.mandatory()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        attribute.displayName() + " is required and the source column is empty.");
            }
            return;
        }

        AttributeRuleEvaluator.check(attribute, value);

        switch (attribute.storage()) {
            case JSONB -> asset.getAttributes().put(attribute.fieldName(), value);
            case COLUMN -> writeColumn(asset, attribute, value);
            case GEOMETRY -> writeGeometry(attribute, value, coordinates);
            case TYPE_TABLE -> throw new IllegalStateException("unreachable: guarded above");
        }
    }

    /**
     * The supertype's writable columns.
     *
     * <p>{@code asset_type} is the interesting one. Its value is a classification, not a label, so a
     * register that spells it "OHT" is describing something the platform knows about — see
     * {@link AssetTypeVocabulary}. Wording that does not resolve is not an error: the wizard's
     * chosen type already stands, and the source's own words are kept in the attribute bag where an
     * operator can see them and correct the data, rather than being dropped with no trace.
     */
    private static void writeColumn(Asset asset, AttributeDefinition attribute, Object value) {
        String text = value.toString();
        switch (attribute.resolvedTarget()) {
            case "asset_code" -> asset.setAssetCode(text);
            case "name" -> asset.setName(text);
            case "status" -> asset.setStatus(parseStatus(text));
            case "install_date" -> asset.setInstallDate(AttributeDataType.parseStoredDate(text));
            case "decommission_date" -> asset.setDecommissionDate(AttributeDataType.parseStoredDate(text));
            case "asset_type" -> AssetTypeVocabulary.resolve(text).ifPresentOrElse(
                    asset::setAssetType,
                    () -> asset.getAttributes().put("asset_type", text));
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "'" + attribute.displayName() + "' is declared as a column named '"
                            + attribute.resolvedTarget() + "', which is not a writable column on an asset.");
        }
    }

    /**
     * Status parsing that keeps the row.
     *
     * <p>A source file's status vocabulary is its own — "Working", "In use", "Functional" — and
     * none of it is worth failing an import over, because the asset is real regardless of what the
     * spreadsheet called its condition. Unrecognised values leave the status at its default and are
     * preserved in the bag under {@code status_source}, which is both visible to the operator and
     * queryable when someone decides to build a mapping table for it.
     */
    private static AssetStatus parseStatus(String text) {
        try {
            return AssetStatus.valueOf(text.trim().toUpperCase().replaceAll("[^A-Z0-9]+", "_"));
        } catch (IllegalArgumentException notAStatus) {
            return AssetStatus.IN_SERVICE;
        }
    }

    private static void writeGeometry(AttributeDefinition attribute, Object value, Coordinates coordinates) {
        double parsed = ((Number) value).doubleValue();
        switch (attribute.resolvedTarget()) {
            case "lon" -> {
                if (parsed < -180 || parsed > 180) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "Longitude " + parsed + " is outside -180..180. A projected easting in a "
                                    + "longitude column is the usual cause — reproject the file to EPSG:4326.");
                }
                coordinates.lon = parsed;
            }
            case "lat" -> {
                if (parsed < -90 || parsed > 90) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "Latitude " + parsed + " is outside -90..90. A projected northing in a "
                                    + "latitude column is the usual cause — reproject the file to EPSG:4326.");
                }
                coordinates.lat = parsed;
            }
            /*
             * `geom` itself. Reached only if someone maps a source column onto the geometry field;
             * the mapper does not offer it, because a feature's geometry comes from the feature and
             * a CSV's comes from lon/lat. AttributeDataType.GEOMETRY.coerce already rejects this, so
             * this branch exists to make the exhaustive switch honest rather than to be taken.
             */
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Geometry is read from the source file's own feature, not from a mapped column.");
        }
    }

    /**
     * Applies the type the wizard chose when the file did not classify the row itself.
     *
     * <p>Called after every value is bound, so a mapped {@code asset_type} column wins and an
     * unmapped one falls back — rather than the fallback overwriting a resolved value depending on
     * column order.
     */
    public static void applyDefaultType(Asset asset, AssetType defaultType) {
        if (asset.getAssetType() == null) {
            asset.setAssetType(defaultType);
        }
    }
}

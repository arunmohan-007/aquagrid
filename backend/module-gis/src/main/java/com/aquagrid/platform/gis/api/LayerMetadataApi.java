package com.aquagrid.platform.gis.api;

import com.aquagrid.platform.gis.domain.enums.AssetType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The catalogue's read side, published to everything that consumes field definitions.
 *
 * <p>This interface is the contract that makes "no Java change per field" true. The importer, the
 * exporter and the dynamic forms to come depend on this and never on the repository or the entity,
 * so a new attribute reaches all three by being inserted, and moving the catalogue behind a cache,
 * a shared L2 or (when GIS is extracted) an HTTP call is a change to one implementation rather than
 * to every consumer.
 *
 * <p>Everything here returns the <b>active</b> catalogue. Deactivation is the module's only delete,
 * and a consumer that had to remember to filter on {@code active} would be one forgotten predicate
 * away from resurrecting a retired field in an import mapping. The Data Management screen, which
 * must show retired fields in order to revive them, goes through the service directly.
 */
public interface LayerMetadataApi {

    /** Active attributes for a layer, in catalogue order. */
    List<AttributeDefinition> definitionsForLayer(UUID organizationId, UUID layerId);

    /**
     * Active attributes for the layer that draws an asset type.
     *
     * <p>The importer's entry point: the import wizard chooses an asset type, not a layer id.
     * Returns empty when the tenant has no layer for that type, which is the honest answer — a
     * tenant that cannot see a layer has no field definitions for it either.
     */
    List<AttributeDefinition> definitionsForAssetType(UUID organizationId, AssetType assetType);

    /** One attribute by field name, for a consumer resolving a saved mapping. */
    Optional<AttributeDefinition> findByFieldName(UUID organizationId, UUID layerId, String fieldName);

    /** The layer id that draws an asset type for this tenant, if one exists. */
    Optional<UUID> layerIdForAssetType(UUID organizationId, AssetType assetType);
}

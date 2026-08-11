package com.aquagrid.platform.gis.application.command;

import com.aquagrid.platform.gis.domain.enums.AssetType;
import com.aquagrid.platform.gis.domain.enums.GeometryType;

/**
 * What the Layer Management service is asked to do.
 *
 * <p>Separate from the web DTOs on purpose: the controller's shapes carry validation annotations and
 * OpenAPI documentation and change when the API changes, while these describe the operation itself.
 * The same split {@code AttributeCommands} uses, and for the same reason — the import path calls
 * {@code create} directly when a file asks for a new layer, and it should not have to assemble an
 * HTTP request body to do it.
 */
public final class LayerCommands {

    private LayerCommands() {
    }

    /**
     * Register a new layer.
     *
     * @param code        the layer name. Null or blank derives one from {@code title} — see
     *                    {@code LayerCodePolicy.deriveFrom}. Immutable once created.
     * @param assetType   which physical bucket in {@code gis.assets} the features land in. Null
     *                    means {@link AssetType#CUSTOM}, which is the right answer for a layer the
     *                    platform's own code knows nothing about.
     * @param claimExistingFeatures whether to claim the unassigned features of {@code assetType} for
     *                    this layer. True for the import wizard's "create a new layer" path, where
     *                    the file that prompted the layer has just been (or is about to be) loaded;
     *                    false for a layer created empty from the registry, where claiming another
     *                    layer's unassigned backlog would be a surprise.
     */
    public record Create(
            String code,
            String title,
            String description,
            String category,
            AssetType assetType,
            GeometryType geometryType,
            String crsAuthority,
            Integer srid,
            Boolean active,
            Boolean visibleByDefault,
            Boolean editable,
            Boolean queryable,
            Boolean searchable,
            Boolean importEnabled,
            Boolean exportEnabled,
            Boolean vectorTileEnabled,
            Integer minZoom,
            Integer maxZoom,
            Integer sortOrder,
            boolean claimExistingFeatures
    ) {
    }

    /**
     * Edit a layer's metadata.
     *
     * <p>Every field is optional and null means "leave it alone", never "clear it" — the convention
     * the whole platform uses for partial updates, so a form that renders half the fields cannot
     * blank the other half. {@code code} and {@code assetType} are absent by design: both are
     * immutable, and the service explains why when something tries.
     */
    public record Update(
            String title,
            String description,
            String category,
            GeometryType geometryType,
            String crsAuthority,
            Integer srid,
            Boolean visibleByDefault,
            Boolean editable,
            Boolean queryable,
            Boolean searchable,
            Boolean importEnabled,
            Boolean exportEnabled,
            Boolean vectorTileEnabled,
            Integer minZoom,
            Integer maxZoom,
            Integer sortOrder
    ) {
    }
}

package com.aquagrid.platform.gis.application.service;

import com.aquagrid.platform.gis.domain.enums.AttributeDataType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the tile endpoint needs to know about a layer, cached.
 *
 * <p>The tile endpoint is the map's hot path — a single pan issues dozens of requests — and each one
 * needs three things resolved before it can build a tile: which layer the code names, which asset
 * type backs it, and which catalogue attributes its style reads and must therefore be carried in the
 * tile. Resolving that per tile means a layer lookup, a style lookup, a rule query and an attribute
 * query on every request, for answers that change when an administrator edits a style — perhaps
 * weekly.
 *
 * <p>Invalidated by the writers, never expired on a timer, which is the same rule
 * {@code ParameterResolver} follows and for the same reason: a time-based cache means an
 * administrator saves a style, reloads the map, sees no change, and concludes the feature is broken.
 * Every path that can change the answer — creating, editing or archiving a layer, saving,
 * activating, deactivating or defaulting a style — calls {@link #evict(UUID)}.
 *
 * <p>Keyed per tenant so one utility's edit cannot flush another's working set.
 */
@Slf4j
@Component
public class LayerRenderCache {

    private final Map<UUID, Map<String, RenderPlan>> byTenant = new ConcurrentHashMap<>();

    /**
     * What the tile query needs, for one layer code.
     *
     * @param styledFields the catalogue attributes the layer's style reads, with their declared
     *                     types. Empty for a layer styled with a single symbol, which is most of
     *                     them — so the common tile carries the same five properties it always did.
     * @param filterableFields the layer's whole active catalogue, with declared types. Wider than
     *                     {@code styledFields} on purpose: a filter restricts which rows are
     *                     encoded and does not need its field in the tile, so an operator can filter
     *                     by a field nobody styles by. It is the whitelist a request's filter is
     *                     resolved against, which is why it is resolved from the catalogue here
     *                     rather than accepted from the caller.
     * @param editable     Layer Management's editable flag, which decides the tile's cache lifetime.
     *                     Carried on the plan so the controller does not re-read the layer row on
     *                     the hot path just to choose a header.
     */
    public record RenderPlan(UUID layerId, String assetType, String layerCode,
                             boolean tileEnabled, boolean editable,
                             Map<String, AttributeDataType> styledFields,
                             Map<String, AttributeDataType> filterableFields) {
    }

    /** The cached plan for a layer code, or null when it has not been resolved yet. */
    public RenderPlan get(UUID organizationId, String layerCode) {
        Map<String, RenderPlan> tenant = byTenant.get(organizationId);
        return tenant == null ? null : tenant.get(layerCode);
    }

    public void put(UUID organizationId, String layerCode, RenderPlan plan) {
        byTenant.computeIfAbsent(organizationId, key -> new ConcurrentHashMap<>()).put(layerCode, plan);
    }

    /**
     * Drops a tenant's whole plan set.
     *
     * <p>The whole set rather than one layer, deliberately. The invalidating events are rare and the
     * set is small, while working out which layers a given edit could have affected is the kind of
     * reasoning that is right until someone adds a code path that makes it wrong — at which point
     * the symptom is a map that will not update and no obvious reason why.
     */
    public void evict(UUID organizationId) {
        Map<String, RenderPlan> removed = byTenant.remove(organizationId);
        if (removed != null && !removed.isEmpty()) {
            log.debug("Evicted {} cached layer render plans for org {}", removed.size(), organizationId);
        }
    }
}

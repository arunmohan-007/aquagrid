package com.aquagrid.platform.gis.infrastructure.persistence;

import com.aquagrid.platform.gis.domain.enums.LayerStatus;
import com.aquagrid.platform.gis.domain.model.Layer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LayerRepository extends JpaRepository<Layer, UUID> {

    List<Layer> findByOrganizationIdOrderBySortOrderAsc(UUID organizationId);

    /** Layer codes are unique per tenant (uq_layers_org_code), so this is at most one row. */
    Optional<Layer> findByOrganizationIdAndCode(UUID organizationId, String code);

    Optional<Layer> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /**
     * The layer that draws an asset type.
     *
     * <p>Ordered and returned as a list, not an {@code Optional}: nothing constrains a tenant to one
     * layer per asset type, and while the seeded catalogue happens to have exactly one, a query
     * that assumes it would start throwing {@code IncorrectResultSizeDataAccessException} the day a
     * utility splits its meters into domestic and bulk layers. The caller takes the first by sort
     * order, which is the catalogue's own idea of the primary layer for that type.
     */
    List<Layer> findByOrganizationIdAndAssetTypeOrderBySortOrderAsc(
            UUID organizationId, com.aquagrid.platform.gis.domain.enums.AssetType assetType);

    // ---- Layer Management (V1332) ---------------------------------------------------------------

    /**
     * The layers the map, the tile endpoint and the import hub should offer.
     *
     * <p>Active only. An inactive or archived layer keeps every feature it ever held — withdrawal
     * never deletes — but it is not drawn, not tiled and not an import target, which is the whole
     * meaning of the status.
     */
    List<Layer> findByOrganizationIdAndStatusOrderBySortOrderAsc(UUID organizationId, LayerStatus status);

    /*
     * The registry's own filtering — status, category, free text — is done in the service over the
     * full tenant list rather than in a query here.
     *
     * That is not laziness about SQL. A tenant has tens of layers, not thousands, so the whole list
     * is one small indexed read either way; and expressing "status is null means any" in HQL against
     * an @Enumerated(STRING) column means either str() (gone in Hibernate 6) or a nullable enum
     * parameter, which is precisely the untyped-null shape the datasource's stringtype=unspecified
     * turns into "could not determine data type of parameter" — the failure AGENTS.md documents and
     * that hid in AssetRepository.findForTenant until a test finally passed a search term. There is
     * no reason to re-enter that minefield for a list this size.
     */

    boolean existsByOrganizationIdAndCode(UUID organizationId, String code);

    /** Highest sort order in use, so a new layer lands at the end rather than in the middle. */
    @Query("SELECT coalesce(max(l.sortOrder), 0) FROM Layer l WHERE l.organizationId = :organizationId")
    int maxSortOrder(@Param("organizationId") UUID organizationId);
}

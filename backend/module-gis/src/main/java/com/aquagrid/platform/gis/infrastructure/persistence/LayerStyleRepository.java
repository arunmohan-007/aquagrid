package com.aquagrid.platform.gis.infrastructure.persistence;

import com.aquagrid.platform.gis.domain.model.LayerStyle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LayerStyleRepository extends JpaRepository<LayerStyle, UUID> {

    List<LayerStyle> findByOrganizationIdAndLayerIdOrderByNameAsc(UUID organizationId, UUID layerId);

    Optional<LayerStyle> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /**
     * The style the map draws for a layer.
     *
     * <p>Returned as an {@link Optional} because a layer legitimately has none: deactivating the
     * default is allowed, and the composer then falls back to the platform's built-in symbology
     * rather than drawing nothing. The partial unique index in V1333 guarantees at most one row, so
     * this cannot start throwing {@code IncorrectResultSizeDataAccessException} the way an
     * unconstrained "find the default" would.
     */
    Optional<LayerStyle> findByLayerIdAndDefaultStyleTrueAndActiveTrue(UUID layerId);

    /** Name collision check, case-insensitive to match {@code uq_layer_style_layer_name}. */
    @Query("SELECT s FROM LayerStyle s WHERE s.layerId = :layerId AND lower(s.name) = lower(:name)")
    Optional<LayerStyle> findByLayerIdAndNameIgnoreCase(@Param("layerId") UUID layerId,
                                                        @Param("name") String name);

    /**
     * Every active default style for a tenant, in one query.
     *
     * <p>The map asks for the whole catalogue's styling at once — one request rather than one per
     * layer, because the alternative is N round trips on every page load and a map that paints its
     * layers in whatever order the responses happened to arrive.
     */
    @Query("""
            SELECT s FROM LayerStyle s
            WHERE s.organizationId = :organizationId
              AND s.active = true AND s.defaultStyle = true
            """)
    List<LayerStyle> findActiveDefaults(@Param("organizationId") UUID organizationId);

    long countByOrganizationIdAndLayerId(UUID organizationId, UUID layerId);
}

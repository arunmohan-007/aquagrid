package com.aquagrid.platform.gis.infrastructure.persistence;

import com.aquagrid.platform.gis.domain.model.LayerAttribute;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Attribute catalogue persistence. Every query is tenant-scoped explicitly. */
@Repository
public interface LayerAttributeRepository extends JpaRepository<LayerAttribute, UUID> {

    /**
     * The Data Management grid's query: every filter the screen offers, in one method.
     *
     * <p>Nullable filters are wrapped in {@code cast(:x as string) IS NULL}. The datasource runs
     * {@code stringtype=unspecified}, so an untyped null parameter cannot be planned and the most
     * common call of all — the unfiltered list — would error instead of returning everything. The
     * boolean filters are {@code Boolean} objects for the same reason: three states (yes / no /
     * don't filter) cannot be expressed by a primitive.
     *
     * <p>{@code :search} is cast at <b>every</b> occurrence, not only in the guard. Hibernate
     * renders {@code concat('%', :search, '%')} as {@code '%'||?||'%'}, and an untyped parameter
     * there has nothing to resolve the operator against: Postgres falls back to {@code bytea||bytea}
     * and the query dies on {@code function lower(bytea) does not exist}. It fails only when
     * {@code search} is null — with a value the {@code '%'} literal is enough for Postgres to infer
     * text — so this is invisible to any test that always passes a search term, and it broke exactly
     * the query the screen opens with.
     *
     * <p>Sorting is left to the {@code Pageable} rather than fixed in the query, because the grid
     * offers it per column. {@code sortOrder} then {@code fieldName} is the caller's default: the
     * seeded catalogue is ordered so that the fields an operator recognises come first, and
     * alphabetical order would open the list on {@code asset_type} and {@code decommission_date}.
     */
    @Query("""
            SELECT a FROM LayerAttribute a
            WHERE a.organizationId = :organizationId
              AND (:layerId IS NULL OR a.layerId = :layerId)
              AND (cast(:search as string) IS NULL
                   OR lower(a.fieldName) LIKE lower(concat('%', cast(:search as string), '%'))
                   OR lower(a.displayName) LIKE lower(concat('%', cast(:search as string), '%'))
                   OR lower(coalesce(a.description, '')) LIKE lower(concat('%', cast(:search as string), '%')))
              AND (cast(:dataType as string) IS NULL OR cast(a.dataType as string) = :dataType)
              AND (:mandatory IS NULL OR a.mandatory = :mandatory)
              AND (:editable IS NULL OR a.editable = :editable)
              AND (:visible IS NULL OR a.visible = :visible)
              AND (:active IS NULL OR a.active = :active)
            """)
    Page<LayerAttribute> search(@Param("organizationId") UUID organizationId,
                                @Param("layerId") UUID layerId,
                                @Param("search") String search,
                                @Param("dataType") String dataType,
                                @Param("mandatory") Boolean mandatory,
                                @Param("editable") Boolean editable,
                                @Param("visible") Boolean visible,
                                @Param("active") Boolean active,
                                Pageable pageable);

    /**
     * A layer's attributes, ordered as the catalogue declares.
     *
     * <p>The read path of the whole module: import mapping, export column sets and dynamic forms
     * all start here, which is why it is cached by {@code LayerMetadataService} rather than hit per
     * row of an import.
     */
    List<LayerAttribute> findByOrganizationIdAndLayerIdOrderBySortOrderAscFieldNameAsc(
            UUID organizationId, UUID layerId);

    Optional<LayerAttribute> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /**
     * Duplicate check, deliberately not filtered by {@code active}.
     *
     * <p>Reusing the field name of a deactivated attribute would silently adopt every value already
     * written under it — either exactly what was wanted, in which case reactivation is the honest
     * path, or a data-corruption bug nobody notices for months. The service's error message points
     * at reactivation.
     */
    Optional<LayerAttribute> findByLayerIdAndFieldName(UUID layerId, String fieldName);

    /** Distinct data types actually present, so the filter dropdown offers only useful options. */
    @Query("""
            SELECT DISTINCT cast(a.dataType as string) FROM LayerAttribute a
            WHERE a.organizationId = :organizationId ORDER BY 1
            """)
    List<String> findUsedDataTypes(@Param("organizationId") UUID organizationId);

    long countByOrganizationIdAndLayerIdAndActiveTrue(UUID organizationId, UUID layerId);
}

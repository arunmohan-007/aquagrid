package com.aquagrid.platform.iot.dataconfig.infrastructure.persistence;

import com.aquagrid.platform.iot.dataconfig.domain.model.MeasurementUnit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Unit lookup. Platform-supplied rows and the tenant's own, in one list. */
@Repository
public interface MeasurementUnitRepository extends JpaRepository<MeasurementUnit, UUID> {

    /**
     * The units this tenant may choose from.
     *
     * <p>Platform rows ({@code organizationId IS NULL}) union the tenant's own. Not a tenant-scoped
     * query in the usual sense, and that is the one place in this module where the multi-tenancy
     * rule is stated differently: a null organisation here means "belongs to everyone", not "belongs
     * to nobody", and the FK plus the CHECK in V1405 are what keep that from being a leak — a null
     * row holds no tenant data, only the string "bar".
     */
    @Query("""
            SELECT u FROM MeasurementUnit u
            WHERE (u.organizationId IS NULL OR u.organizationId = :organizationId)
              AND (:activeOnly = false OR u.active = true)
            ORDER BY u.sortOrder ASC, u.code ASC
            """)
    List<MeasurementUnit> findForTenant(@Param("organizationId") UUID organizationId,
                                        @Param("activeOnly") boolean activeOnly);

    @Query("""
            SELECT u FROM MeasurementUnit u
            WHERE u.code = :code AND (u.organizationId IS NULL OR u.organizationId = :organizationId)
            ORDER BY u.organizationId ASC NULLS LAST
            LIMIT 1
            """)
    Optional<MeasurementUnit> findByCodeForTenant(@Param("organizationId") UUID organizationId,
                                                  @Param("code") String code);
}

package com.aquagrid.platform.iot.dataconfig.infrastructure.persistence;

import com.aquagrid.platform.iot.dataconfig.domain.model.DeviceDataParameter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Parameter catalogue persistence. Every query is tenant-scoped explicitly. */
@Repository
public interface DeviceDataParameterRepository extends JpaRepository<DeviceDataParameter, UUID> {

    /**
     * The Device Data Configuration grid's query: every filter the screen offers, in one method.
     *
     * <p>Nullable filters are wrapped in {@code cast(:x as string) IS NULL}. The datasource runs
     * {@code stringtype=unspecified}, so an untyped null parameter cannot be planned and the most
     * common call of all — the unfiltered list — would error instead of returning everything. The
     * boolean filters are {@code Boolean} objects for the same reason: three states (yes / no /
     * don't filter) cannot be expressed by a primitive.
     *
     * <p>{@code :search} is cast at <b>every</b> occurrence, not only in the guard, because
     * {@code concat} is variadic over {@code "any"} and gives Postgres nothing to infer from — the
     * failure mode {@code AssetRepository.findForTenant} hid for months, where the unfiltered list
     * worked and every non-empty search died on "could not determine data type of parameter".
     *
     * <p>{@code deviceId} filters both scopes on purpose: asked for one device's parameters, the
     * screen must show the type template it inherits as well as its own overrides, because that
     * union is what the device actually runs under. Showing only the overrides would present an
     * almost-empty grid for a fully configured device.
     */
    @Query("""
            SELECT p FROM DeviceDataParameter p
            WHERE p.organizationId = :organizationId
              AND (cast(:scope as string) IS NULL OR cast(p.scope as string) = :scope)
              AND (cast(:deviceType as string) IS NULL OR p.deviceType = :deviceType)
              AND (:deviceId IS NULL
                   OR p.deviceId = :deviceId
                   OR (cast(:inheritedDeviceType as string) IS NOT NULL
                       AND p.deviceType = :inheritedDeviceType))
              AND (cast(:search as string) IS NULL
                   OR lower(p.parameterName) LIKE lower(concat('%', cast(:search as string), '%'))
                   OR lower(p.displayName) LIKE lower(concat('%', cast(:search as string), '%'))
                   OR lower(coalesce(p.description, '')) LIKE lower(concat('%', cast(:search as string), '%')))
              AND (cast(:dataType as string) IS NULL OR cast(p.dataType as string) = :dataType)
              AND (cast(:category as string) IS NULL OR p.category = :category)
              AND (:mandatory IS NULL OR p.mandatory = :mandatory)
              AND (:dashboardVisible IS NULL OR p.dashboardVisible = :dashboardVisible)
              AND (:useForAlarm IS NULL OR p.useForAlarm = :useForAlarm)
              AND (:useForReports IS NULL OR p.useForReports = :useForReports)
              AND (:active IS NULL OR p.active = :active)
            """)
    Page<DeviceDataParameter> search(@Param("organizationId") UUID organizationId,
                                     @Param("scope") String scope,
                                     @Param("deviceType") String deviceType,
                                     @Param("deviceId") UUID deviceId,
                                     @Param("inheritedDeviceType") String inheritedDeviceType,
                                     @Param("search") String search,
                                     @Param("dataType") String dataType,
                                     @Param("category") String category,
                                     @Param("mandatory") Boolean mandatory,
                                     @Param("dashboardVisible") Boolean dashboardVisible,
                                     @Param("useForAlarm") Boolean useForAlarm,
                                     @Param("useForReports") Boolean useForReports,
                                     @Param("active") Boolean active,
                                     Pageable pageable);

    /**
     * A device type's template, active rows only.
     *
     * <p>Half of the resolver's read, and on the reception hot path — hence the partial index
     * V1405 creates over exactly these columns.
     */
    @Query("""
            SELECT p FROM DeviceDataParameter p
            WHERE p.organizationId = :organizationId
              AND p.scope = com.aquagrid.platform.iot.dataconfig.domain.model.ParameterScope.DEVICE_TYPE
              AND p.deviceType = :deviceType
              AND p.active = true
            ORDER BY p.sortOrder ASC, p.parameterName ASC
            """)
    List<DeviceDataParameter> findActiveTemplate(@Param("organizationId") UUID organizationId,
                                                 @Param("deviceType") String deviceType);

    /** One device's own overrides, active rows only. The other half of the resolver's read. */
    @Query("""
            SELECT p FROM DeviceDataParameter p
            WHERE p.organizationId = :organizationId
              AND p.scope = com.aquagrid.platform.iot.dataconfig.domain.model.ParameterScope.DEVICE
              AND p.deviceId = :deviceId
              AND p.active = true
            ORDER BY p.sortOrder ASC, p.parameterName ASC
            """)
    List<DeviceDataParameter> findActiveOverrides(@Param("organizationId") UUID organizationId,
                                                  @Param("deviceId") UUID deviceId);

    Optional<DeviceDataParameter> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /**
     * Duplicate checks, deliberately not filtered by {@code active}.
     *
     * <p>Reusing the name of a deactivated parameter would silently adopt every reading already
     * stored under it — either exactly what was wanted, in which case reactivation is the honest
     * path, or a data-corruption bug nobody notices for months. The service's error message points
     * at reactivation.
     */
    @Query("""
            SELECT p FROM DeviceDataParameter p
            WHERE p.organizationId = :organizationId
              AND p.scope = com.aquagrid.platform.iot.dataconfig.domain.model.ParameterScope.DEVICE_TYPE
              AND p.deviceType = :deviceType
              AND p.parameterName = :parameterName
            """)
    Optional<DeviceDataParameter> findTemplateByName(@Param("organizationId") UUID organizationId,
                                                     @Param("deviceType") String deviceType,
                                                     @Param("parameterName") String parameterName);

    @Query("""
            SELECT p FROM DeviceDataParameter p
            WHERE p.organizationId = :organizationId
              AND p.scope = com.aquagrid.platform.iot.dataconfig.domain.model.ParameterScope.DEVICE
              AND p.deviceId = :deviceId
              AND p.parameterName = :parameterName
            """)
    Optional<DeviceDataParameter> findOverrideByName(@Param("organizationId") UUID organizationId,
                                                     @Param("deviceId") UUID deviceId,
                                                     @Param("parameterName") String parameterName);

    /** Device types that already have a template, so the picker can show which are configured. */
    @Query("""
            SELECT p.deviceType, count(p) FROM DeviceDataParameter p
            WHERE p.organizationId = :organizationId
              AND p.scope = com.aquagrid.platform.iot.dataconfig.domain.model.ParameterScope.DEVICE_TYPE
              AND p.active = true
            GROUP BY p.deviceType
            """)
    List<Object[]> countActiveByDeviceType(@Param("organizationId") UUID organizationId);

    long countByOrganizationIdAndDeviceIdAndActiveTrue(UUID organizationId, UUID deviceId);

    /** Highest sort order in a scope, so a new parameter lands at the end rather than the middle. */
    @Query("""
            SELECT coalesce(max(p.sortOrder), 90) FROM DeviceDataParameter p
            WHERE p.organizationId = :organizationId
              AND ((:deviceId IS NOT NULL AND p.deviceId = :deviceId)
                   OR (cast(:deviceType as string) IS NOT NULL AND p.deviceType = :deviceType))
            """)
    int maxSortOrder(@Param("organizationId") UUID organizationId,
                     @Param("deviceType") String deviceType,
                     @Param("deviceId") UUID deviceId);
}

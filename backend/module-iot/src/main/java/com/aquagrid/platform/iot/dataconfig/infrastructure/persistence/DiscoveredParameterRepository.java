package com.aquagrid.platform.iot.dataconfig.infrastructure.persistence;

import com.aquagrid.platform.iot.dataconfig.domain.model.DiscoveredParameter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Discovery queue persistence. The upsert is on the reception path; everything else is a screen. */
@Repository
public interface DiscoveredParameterRepository extends JpaRepository<DiscoveredParameter, UUID> {

    /**
     * Records one sighting of an undescribed parameter.
     *
     * <p>Native, and a single {@code INSERT .. ON CONFLICT DO UPDATE}, because this runs inside
     * packet reception: a device sending four unknown fields every five minutes would otherwise cost
     * four SELECTs and four writes per packet, and two receivers racing on the same first sighting
     * would produce a constraint violation rather than a count of two. Letting the database resolve
     * it is both cheaper and the only version that is correct under concurrency — the same reasoning
     * V1404's statistics upserts are built on.
     *
     * <p>The sample and detected type are refreshed on every sighting rather than kept from the
     * first. A field whose first observed value was {@code 0} tells an administrator nothing; the
     * most recent value is the one that helps them decide what it is.
     *
     * <p>{@code status} is deliberately not touched on conflict. A parameter an operator has
     * dismissed must stay dismissed however many more times the device sends it — otherwise
     * "Ignore" would mean "hide until the next packet", which for a five-minute reporting interval
     * is no feature at all.
     */
    @Modifying
    @Query(value = """
            INSERT INTO iot.device_discovered_parameter
                (id, organization_id, device_id, device_code, device_type, parameter_name,
                 sample_value, detected_data_type, first_seen_at, last_seen_at, occurrences, status)
            VALUES
                (gen_random_uuid(), :organizationId, :deviceId, :deviceCode, :deviceType,
                 :parameterName, :sampleValue, :detectedDataType, :seenAt, :seenAt, 1, 'PENDING')
            ON CONFLICT (device_id, parameter_name) DO UPDATE
            SET last_seen_at       = EXCLUDED.last_seen_at,
                occurrences        = iot.device_discovered_parameter.occurrences + 1,
                sample_value       = EXCLUDED.sample_value,
                detected_data_type = EXCLUDED.detected_data_type,
                device_code        = EXCLUDED.device_code,
                device_type        = EXCLUDED.device_type
            """, nativeQuery = true)
    void recordSighting(@Param("organizationId") UUID organizationId,
                        @Param("deviceId") UUID deviceId,
                        @Param("deviceCode") String deviceCode,
                        @Param("deviceType") String deviceType,
                        @Param("parameterName") String parameterName,
                        @Param("sampleValue") String sampleValue,
                        @Param("detectedDataType") String detectedDataType,
                        @Param("seenAt") Instant seenAt);

    /**
     * The Discovered Parameters screen's query.
     *
     * <p>Same null-filter casting rules as the catalogue search — see
     * {@code DeviceDataParameterRepository.search} for why every occurrence of {@code :search} is
     * cast rather than only the guard.
     */
    @Query("""
            SELECT d FROM DiscoveredParameter d
            WHERE d.organizationId = :organizationId
              AND (:deviceId IS NULL OR d.deviceId = :deviceId)
              AND (cast(:deviceType as string) IS NULL OR d.deviceType = :deviceType)
              AND (cast(:status as string) IS NULL OR cast(d.status as string) = :status)
              AND (cast(:search as string) IS NULL
                   OR lower(d.parameterName) LIKE lower(concat('%', cast(:search as string), '%'))
                   OR lower(coalesce(d.deviceCode, '')) LIKE lower(concat('%', cast(:search as string), '%')))
            """)
    Page<DiscoveredParameter> search(@Param("organizationId") UUID organizationId,
                                     @Param("deviceId") UUID deviceId,
                                     @Param("deviceType") String deviceType,
                                     @Param("status") String status,
                                     @Param("search") String search,
                                     Pageable pageable);

    Optional<DiscoveredParameter> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /**
     * Every pending discovery of a name, whatever device it was seen on.
     *
     * <p>Configuring a parameter as a device-type template answers the question for every device of
     * that type at once, so all of their discovery rows should close together. Leaving them open
     * would have the queue keep asking about a parameter that has already been defined.
     */
    @Query("""
            SELECT d FROM DiscoveredParameter d
            WHERE d.organizationId = :organizationId
              AND d.parameterName IN :names
              AND d.status = com.aquagrid.platform.iot.dataconfig.domain.model.DiscoveryStatus.PENDING
              AND (:deviceId IS NULL OR d.deviceId = :deviceId)
              AND (cast(:deviceType as string) IS NULL OR d.deviceType = :deviceType)
            """)
    List<DiscoveredParameter> findPendingByNames(@Param("organizationId") UUID organizationId,
                                                 @Param("names") List<String> names,
                                                 @Param("deviceType") String deviceType,
                                                 @Param("deviceId") UUID deviceId);

    long countByOrganizationIdAndStatus(UUID organizationId,
                                        com.aquagrid.platform.iot.dataconfig.domain.model.DiscoveryStatus status);
}

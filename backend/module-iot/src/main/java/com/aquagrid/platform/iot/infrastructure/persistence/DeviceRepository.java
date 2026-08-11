package com.aquagrid.platform.iot.infrastructure.persistence;

import com.aquagrid.platform.iot.domain.model.Device;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceRepository extends JpaRepository<Device, UUID> {

    /** Resolves a device by its tenant-scoped network address. The lookup every uplink performs. */
    Optional<Device> findByOrganizationIdAndNetworkAddress(UUID organizationId, String networkAddress);

    /**
     * Fetches a device by id within a tenant.
     *
     * <p>The tenant is part of the key, never a check performed afterwards: an id from another
     * organisation must be indistinguishable from an id that does not exist, or the not-found
     * response becomes a way to enumerate another tenant's device ids.
     */
    Optional<Device> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /**
     * How many devices of each type the tenant has.
     *
     * <p>Drives the Device Data Configuration type picker, where "Flow Meter — 5 parameters, 340
     * devices" is what tells an administrator which template is worth curating first. Aggregated in
     * the database rather than by counting a fetched list, which on a real estate would be tens of
     * thousands of rows loaded to produce ten numbers.
     */
    @Query("""
            SELECT d.deviceType, count(d) FROM Device d
            WHERE d.organizationId = :organizationId
            GROUP BY d.deviceType
            """)
    java.util.List<Object[]> countByDeviceType(@Param("organizationId") UUID organizationId);

    /**
     * Resolves a device by network address across tenants. Used by the unauthenticated ingestion
     * path: a gateway delivers bytes that carry only the device's radio identifier (DevEUI for
     * LoRaWAN, IMEI for NB-IoT and 4G), so the tenant is pinned from the resolved device row, not
     * from the request.
     */
    Optional<Device> findFirstByNetworkAddressIgnoreCase(String networkAddress);

    boolean existsByOrganizationIdAndNetworkAddress(UUID organizationId, String networkAddress);

    /**
     * Resolves a device by an identifier held in its {@code provisioning} block.
     *
     * <p>The fallback path for the receiver's device resolution: not every technology addresses a
     * device by the field its {@code CommunicationProfile} nominates as {@code networkAddress}. A
     * shared MQTT bridge names the meter only in the topic it publishes on, a TCP logger frames a
     * MAC address, an integration is issued a per-device token.
     *
     * <p>Native, because JPQL has no JSONB operator. {@code key} is bound rather than concatenated
     * — {@code ->>} takes its argument as text, so parameterising it is both safe and sufficient,
     * and building the operand by string concatenation would put a caller-supplied value into SQL.
     *
     * <p><b>Only keys with an expression index (V1403) may be passed.</b> Every other key is a
     * sequential scan of the device table on the ingestion path. The resolver enforces this by only
     * offering strategies whose key is indexed; this method cannot check it.
     */
    @Query(value = """
            SELECT * FROM iot.devices d
            WHERE d.provisioning ->> :key = :value
            LIMIT 1
            """, nativeQuery = true)
    Optional<Device> findFirstByProvisioningField(@Param("key") String key,
                                                  @Param("value") String value);

    /** Case-normalised variant, for hex identifiers and hardware addresses. See V1403. */
    @Query(value = """
            SELECT * FROM iot.devices d
            WHERE upper(d.provisioning ->> :key) = upper(:value)
            LIMIT 1
            """, nativeQuery = true)
    Optional<Device> findFirstByProvisioningFieldIgnoreCase(@Param("key") String key,
                                                            @Param("value") String value);

    /**
     * Resolves a device by serial number across tenants, for the same reason
     * {@link #findFirstByNetworkAddressIgnoreCase} does: ingestion is unauthenticated at the
     * transport level, so the tenant is pinned from the resolved row rather than trusted from the
     * request.
     */
    Optional<Device> findFirstBySerialNumberIgnoreCase(String serialNumber);

    Optional<Device> findFirstByDeviceCodeIgnoreCase(String deviceCode);

    /** Simulator-source devices for a tenant — the fleet the simulator drives. */
    java.util.List<Device> findBySourceAndOrganizationId(String source, UUID organizationId);

    boolean existsByOrganizationIdAndDeviceCodeIgnoreCase(UUID organizationId, String deviceCode);

    /**
     * The tenant's device list, with every filter optional.
     *
     * <p>Every optional filter is cast before its {@code IS NULL} test, and that is load-bearing.
     * The datasource runs with {@code stringtype=unspecified} — production binds Strings into
     * {@code inet} columns and the driver only accepts that untyped — so a null filter reaches
     * PostgreSQL with no type at all. {@code ? IS NULL} on an untyped parameter fails to plan with
     * "could not determine data type", which means an unfiltered device list, the most common call
     * there is, errors rather than returning everything. The cast pins the type.
     *
     * <p>{@code pattern} is a pre-lowercased {@code %term%} built by the caller rather than
     * concatenated here, for the same reason: {@code concat('%', ?, '%')} over an untyped null
     * resolves to {@code bytea} and fails with "function lower(bytea) does not exist". Building it
     * once is also cheaper than concatenating per row.
     */
    @Query("""
            SELECT d FROM Device d
            WHERE d.organizationId = :organizationId
              AND (cast(:status as string) IS NULL OR d.status = :status)
              AND (cast(:transport as string) IS NULL OR d.transport = :transport)
              AND (cast(:deviceType as string) IS NULL OR d.deviceType = :deviceType)
              AND (cast(:source as string) IS NULL OR d.source = :source)
              AND (cast(:protocol as string) IS NULL OR d.protocol = :protocol)
              AND (cast(:pattern as string) IS NULL
                   OR lower(d.name) LIKE :pattern
                   OR lower(d.deviceCode) LIKE :pattern
                   OR lower(d.serialNumber) LIKE :pattern
                   OR lower(d.assetNumber) LIKE :pattern
                   OR lower(d.networkAddress) LIKE :pattern)
            ORDER BY d.deviceCode
            """)
    Page<Device> findForTenant(@Param("organizationId") UUID organizationId,
                               @Param("status") String status,
                               @Param("transport") String transport,
                               @Param("deviceType") String deviceType,
                               @Param("source") String source,
                               @Param("protocol") String protocol,
                               @Param("pattern") String pattern,
                               Pageable pageable);
}

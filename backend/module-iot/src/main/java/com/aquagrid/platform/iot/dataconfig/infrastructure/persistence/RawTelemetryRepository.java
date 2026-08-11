package com.aquagrid.platform.iot.dataconfig.infrastructure.persistence;

import com.aquagrid.platform.iot.dataconfig.domain.model.RawTelemetry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Raw payload persistence.
 *
 * <p>Read-mostly by count of methods and write-mostly by count of rows: one insert per packet, and
 * queries only when somebody opens View Raw Payload or the discovery screen. Nothing here updates a
 * row — the payload column is the record of what a device sent, and a record that can be edited is
 * not one.
 */
@Repository
public interface RawTelemetryRepository extends JpaRepository<RawTelemetry, UUID> {

    /**
     * The View Raw Payload query.
     *
     * <p>Tenant-scoped explicitly, and it has to be: the table carries rows whose
     * {@code organizationId} is null by construction — packets from devices nobody registered — so
     * there is no database-level filter to fall back on. The same reasoning
     * {@code receiver_packet_logs} states in V1404.
     *
     * <p>The time bounds are written as {@code coalesce(:from, r.receivedAt)} rather than as the
     * usual {@code :from IS NULL OR ...} guard. The datasource runs {@code stringtype=unspecified},
     * and an untyped null binding cannot be planned — the platform documents this for string
     * parameters, but it bites timestamps identically, and it bites only the <em>unfiltered</em>
     * call, which is the one the screen opens with. A cast would work too; coalesce is preferred
     * here because it takes its type from the column rather than naming a dialect type, so it
     * cannot drift from what {@code receivedAt} actually is.
     */
    @Query("""
            SELECT r FROM RawTelemetry r
            WHERE r.organizationId = :organizationId
              AND (:deviceId IS NULL OR r.deviceId = :deviceId)
              AND (cast(:status as string) IS NULL OR r.processingStatus = :status)
              AND r.receivedAt >= coalesce(:from, r.receivedAt)
              AND r.receivedAt <= coalesce(:to, r.receivedAt)
            ORDER BY r.receivedAt DESC
            """)
    Page<RawTelemetry> search(@Param("organizationId") UUID organizationId,
                              @Param("deviceId") UUID deviceId,
                              @Param("status") String status,
                              @Param("from") Instant from,
                              @Param("to") Instant to,
                              Pageable pageable);

    Optional<RawTelemetry> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /**
     * The most recent payloads carrying a given key, for one device.
     *
     * <p>Native because the predicate is a JSONB key-existence test, which is what the
     * {@code jsonb_path_ops} GIN index V1406 creates exists to serve. It answers "show me the actual
     * data" from the discovery screen: an administrator deciding what {@code motor_temperature} is
     * wants to see the values, not a sample of one.
     *
     * <p>{@code jsonb_exists(payload, key)} rather than the {@code payload ? key} operator it is the
     * function form of. The operator's {@code ?} is indistinguishable from a JDBC positional
     * parameter, and doubling it to escape — {@code ??} — makes Spring Data read it as one, which
     * fails the whole repository at startup with "Mixing of ? parameters and other forms like ?1 is
     * not supported". The function takes the same index and cannot be misread.
     */
    @Query(value = """
            SELECT * FROM iot.device_raw_telemetry r
            WHERE r.organization_id = :organizationId
              AND r.device_id = :deviceId
              AND jsonb_exists(r.payload, :parameterName)
            ORDER BY r.received_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    java.util.List<RawTelemetry> findRecentCarrying(@Param("organizationId") UUID organizationId,
                                                    @Param("deviceId") UUID deviceId,
                                                    @Param("parameterName") String parameterName,
                                                    @Param("limit") int limit);
}

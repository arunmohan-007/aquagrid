package com.aquagrid.platform.iot.dataconfig.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only record of what a parameter's definition was.
 *
 * <p>Distinct from the platform audit trail, which answers "who changed this and when". This answers
 * "what did this parameter mean at the time that reading was written" — the question you have when a
 * parameter's unit moved from L/min to m3/hr, or its range was widened after a year of readings had
 * already been marked OUT_OF_RANGE against the old one. There is data on both sides of such a change
 * and nothing else to interpret it by.
 *
 * <p>Whole snapshots rather than diffs, so reading history at any point never requires replaying
 * every prior row to reconstruct state.
 *
 * <p>Not a {@code BaseEntity}: nothing ever updates a row here, so there is no optimistic-locking
 * version and no updated-at. A {@code @Version} column on an append-only log is a column that is
 * always zero and invites someone to write the update it implies is possible.
 */
@Getter
@Setter
@Entity
@Table(name = "device_parameter_history", schema = "iot")
public class DeviceParameterHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "parameter_id", nullable = false, updatable = false)
    private UUID parameterId;

    /** Denormalised so history stays readable without joining a definition that may have changed. */
    @Column(name = "parameter_name", nullable = false, length = 60, updatable = false)
    private String parameterName;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 12, updatable = false)
    private ParameterScope scope;

    @Column(name = "device_type", length = 40, updatable = false)
    private String deviceType;

    @Column(name = "device_id", updatable = false)
    private UUID deviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 20, updatable = false)
    private ParameterChangeType changeType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "previous_state", columnDefinition = "jsonb")
    private Map<String, Object> previousState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_state", columnDefinition = "jsonb")
    private Map<String, Object> newState;

    /** The administrator's own words: why the range was widened, why the parameter was retired. */
    @Column(name = "change_reason", length = 500, updatable = false)
    private String changeReason;

    @Column(name = "changed_by", updatable = false)
    private UUID changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt = Instant.now();
}

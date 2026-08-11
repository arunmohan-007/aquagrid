package com.aquagrid.platform.gis.domain.model;

import com.aquagrid.platform.gis.domain.enums.AttributeChangeType;
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
 * Append-only record of what an attribute's definition was.
 *
 * <p>Distinct from the platform audit trail, which answers "who changed this and when". This
 * answers "what did the field mean at the time that value was written" — the question you have when
 * an attribute's {@code maxLength} was widened, or its type moved from TEXT to DECIMAL, and there
 * is data on both sides of the change with nothing else to interpret it by. Soft delete makes this
 * more important, not less: a field can be retired and revived years apart, and the values in
 * between were written under whichever definition was in force.
 *
 * <p>Whole snapshots rather than diffs, so reading the history at any point never requires
 * replaying every prior row to reconstruct state.
 *
 * <p>Not a {@code BaseEntity}: this table has no optimistic-locking version and no updated-at,
 * because nothing ever updates a row in it. A {@code @Version} column on an append-only log is a
 * column that is always zero and invites someone to write the update it implies is possible.
 */
@Getter
@Setter
@Entity
@Table(name = "layer_attribute_history", schema = "gis")
public class LayerAttributeHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "attribute_id", nullable = false, updatable = false)
    private UUID attributeId;

    @Column(name = "layer_id", nullable = false, updatable = false)
    private UUID layerId;

    /** Denormalised so history stays readable without joining a definition that may have changed. */
    @Column(name = "field_name", nullable = false, length = 63, updatable = false)
    private String fieldName;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 20, updatable = false)
    private AttributeChangeType changeType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "previous_state", columnDefinition = "jsonb")
    private Map<String, Object> previousState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_state", columnDefinition = "jsonb")
    private Map<String, Object> newState;

    /** The administrator's own words: why the field was widened, why it was retired. */
    @Column(name = "change_reason", length = 500, updatable = false)
    private String changeReason;

    @Column(name = "changed_by", updatable = false)
    private UUID changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt = Instant.now();
}

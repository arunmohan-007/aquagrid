package com.aquagrid.platform.iot.dataconfig.domain.model;

import com.aquagrid.platform.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * One selectable unit.
 *
 * <p>A lookup table rather than a Java enum, because the platform has been here before: the metric
 * unit lived in a {@code switch} inside {@code TelemetryIngestService} and its label in a TypeScript
 * map in the browser, which meant adding one required edits in two languages that nothing checked
 * for agreement. {@code MetricCatalog} fixed that for the eight metrics the platform ships with;
 * this fixes it for the units a tenant needs and the platform does not ship.
 *
 * <p>{@code organizationId} is nullable and that nullability is the design of the table. A null row
 * is platform-supplied and visible to every tenant — which is what lets V1405 seed the units without
 * knowing which organisations exist, now or later. A district that meters in kilolitres inserts its
 * own row against its own id and sees it alongside the shipped ones.
 */
@Getter
@Setter
@Entity
@Table(name = "unit_master", schema = "iot")
public class MeasurementUnit extends AuditableEntity {

    /** Null for platform-supplied units, which every tenant sees. */
    @Column(name = "organization_id", updatable = false)
    private UUID organizationId;

    /**
     * The code written onto {@code iot.device_readings.unit}, which is {@code VARCHAR(20)}. A unit
     * that cannot be written onto a reading is not a unit this platform can offer.
     */
    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @Column(name = "label", nullable = false, length = 80)
    private String label;

    /**
     * What is being measured.
     *
     * <p>Groups the picker, and stops "m" (length) being offered beside "m3" (volume) as though they
     * were alternatives for the same reading.
     */
    @Column(name = "quantity", nullable = false, length = 24)
    private String quantity;

    @Column(name = "description", length = 200)
    private String description;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 100;
}

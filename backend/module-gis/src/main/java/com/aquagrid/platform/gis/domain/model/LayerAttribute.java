package com.aquagrid.platform.gis.domain.model;

import com.aquagrid.platform.common.domain.TenantAwareEntity;
import com.aquagrid.platform.gis.domain.enums.AttributeDataType;
import com.aquagrid.platform.gis.domain.enums.AttributeStorage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * One field on one layer.
 *
 * <p>The catalogue row that makes the platform metadata-driven: import, export and the dynamic
 * forms that follow read their field list from these rows rather than from a table in code, so a
 * utility adding {@code consumer_no} to its meters gets it in the import mapper, the export and
 * the asset form without a release.
 *
 * <p>Two flags carry most of the semantics and are easy to confuse:
 *
 * <ul>
 *   <li>{@code active} is the soft delete. False means the field is retired: it disappears from
 *       import mapping, from exports and from forms, while every value already written under it
 *       stays in the attribute bag and comes back the moment it is reactivated. There is no hard
 *       delete anywhere in this module.</li>
 *   <li>{@code visible} is presentation. True means "show this by default" — in the export's
 *       column set and on forms. {@code lon} and {@code lat} are the worked example: active, so
 *       the import mapper offers them, but not visible, so an export does not emit two derived
 *       columns beside the geometry they were derived from.</li>
 * </ul>
 *
 * <p>{@code system} marks a field the application's own Java reads by name. Its label, description,
 * sample and visibility belong to the tenant; its name, type and mandatory flag do not, because a
 * rename breaks the code that reads it and a retype breaks the column it lives in. Everything an
 * administrator creates has this false and is editable in full.
 */
@Getter
@Setter
@Entity
@Table(name = "layer_attribute_master", schema = "gis")
public class LayerAttribute extends TenantAwareEntity {

    /**
     * The owning layer. Held as a raw id rather than a {@code @ManyToOne}: nothing in this module
     * navigates from an attribute to its layer's style or sort order, and a mapped association
     * would put a lazy proxy on every row of a list that is fetched a hundred at a time.
     */
    @Column(name = "layer_id", nullable = false, updatable = false)
    private UUID layerId;

    @Column(name = "field_name", nullable = false, length = 63)
    private String fieldName;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "description", length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_type", nullable = false, length = 20)
    private AttributeDataType dataType;

    @Column(name = "max_length")
    private Integer maxLength;

    @Column(name = "numeric_precision")
    private Integer numericPrecision;

    @Column(name = "numeric_scale")
    private Integer numericScale;

    @Column(name = "default_value", length = 255)
    private String defaultValue;

    @Column(name = "sample_value", length = 255)
    private String sampleValue;

    @Column(name = "is_mandatory", nullable = false)
    private boolean mandatory;

    @Column(name = "is_unique", nullable = false)
    private boolean uniqueValue;

    @Column(name = "is_editable", nullable = false)
    private boolean editable = true;

    @Column(name = "is_visible", nullable = false)
    private boolean visible = true;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "is_system", nullable = false, updatable = false)
    private boolean system;

    @Enumerated(EnumType.STRING)
    @Column(name = "storage", nullable = false, length = 10, updatable = false)
    private AttributeStorage storage = AttributeStorage.JSONB;

    /** The detail table, for {@link AttributeStorage#TYPE_TABLE}. Null otherwise. */
    @Column(name = "storage_table", length = 63, updatable = false)
    private String storageTable;

    /** The column or pseudo-field this attribute resolves to. Null for {@code JSONB}. */
    @Column(name = "storage_target", length = 63, updatable = false)
    private String storageTarget;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 100;

    /**
     * The key this attribute's value is read and written under.
     *
     * <p>For a JSONB attribute that is the field name; for anything backed by a column it is the
     * column's own name, which is not always the same — {@code valve_status} lives in
     * {@code gis.valves.status}, because the valve's position and the asset's lifecycle status are
     * two different things that would otherwise both want to be called "status" on one layer.
     */
    public String resolvedTarget() {
        return storageTarget != null ? storageTarget : fieldName;
    }
}

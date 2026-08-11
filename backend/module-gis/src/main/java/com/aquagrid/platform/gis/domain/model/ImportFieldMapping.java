package com.aquagrid.platform.gis.domain.model;

import com.aquagrid.platform.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * One saved source-column → attribute decision, part of a named mapping profile.
 *
 * <p>A utility receives the same contractor's deliverable every quarter with the same twenty column
 * headings. Re-mapping them by hand each time is where mis-mappings come from, so the mapping is
 * saved once under a profile name and offered back on the next import of that layer.
 *
 * <p>A null {@code attributeId} means "ignore this column", stored rather than omitted. The
 * difference between a column the operator decided to drop and one they never saw is the difference
 * between a reviewed mapping and an incomplete one, and only the stored decision can tell them
 * apart next quarter.
 */
@Getter
@Setter
@Entity
@Table(name = "import_field_mapping", schema = "gis")
public class ImportFieldMapping extends TenantAwareEntity {

    @Column(name = "layer_id", nullable = false, updatable = false)
    private UUID layerId;

    @Column(name = "profile_name", nullable = false, length = 120)
    private String profileName;

    /** The column heading exactly as it appears in the source file. */
    @Column(name = "source_field", nullable = false, length = 200)
    private String sourceField;

    /** The attribute it maps to, or null for "ignore this column". */
    @Column(name = "attribute_id")
    private UUID attributeId;
}

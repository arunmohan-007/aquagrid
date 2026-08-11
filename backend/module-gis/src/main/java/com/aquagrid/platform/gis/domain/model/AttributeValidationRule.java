package com.aquagrid.platform.gis.domain.model;

import com.aquagrid.platform.common.domain.TenantAwareEntity;
import com.aquagrid.platform.gis.domain.enums.ValidationRuleType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * A value constraint on one attribute — a range for a diameter, a pattern for a consumer number,
 * an allowed-value list for a material.
 *
 * <p>Rows rather than columns on {@link LayerAttribute}, because an attribute can carry several and
 * the set is open: a utility adds "diameter must be between 50 and 1200" without a release.
 * Evaluated by the importer in {@code sortOrder}, so the most informative failure is the one the
 * operator sees first.
 */
@Getter
@Setter
@Entity
@Table(name = "attribute_validation_rules", schema = "gis")
public class AttributeValidationRule extends TenantAwareEntity {

    @Column(name = "attribute_id", nullable = false, updatable = false)
    private UUID attributeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 20)
    private ValidationRuleType ruleType;

    /** A number, a regular expression or a comma-separated list, depending on {@code ruleType}. */
    @Column(name = "rule_value", nullable = false, length = 500)
    private String ruleValue;

    /**
     * What the operator sees when this rule rejects a row.
     *
     * <p>Optional, and the fallback is generated from the rule — but a rule that reports
     * "does not match ^[0-9]{8}$" is a rule nobody can act on, so the create form asks for this.
     */
    @Column(name = "error_message", length = 300)
    private String errorMessage;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 100;
}

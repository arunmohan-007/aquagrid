package com.aquagrid.platform.gis.domain.model;

import com.aquagrid.platform.common.domain.AuditableEntity;
import com.aquagrid.platform.gis.domain.enums.StyleOperator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One class of a classified style: a category, a numeric band, or an arbitrary predicate.
 *
 * <p>Relational rather than an array inside {@link LayerStyle}'s document, because these are the
 * rows an administrator adds, removes and reorders one at a time, and because each has to be
 * validated against the attribute catalogue individually — the field it names must exist and be
 * active in {@code gis.layer_attribute_master}, and its operator must suit that field's declared
 * data type.
 */
@Getter
@Setter
@Entity
@Table(name = "layer_style_rule", schema = "gis")
public class LayerStyleRule extends AuditableEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "style_id", nullable = false, updatable = false)
    private UUID styleId;

    /**
     * The Data Management field this rule tests.
     *
     * <p>Redundant with {@link LayerStyle#getClassifyField()} for categorical and graduated styles,
     * deliberately: rule-based styles let each rule name its own field, and a column that is
     * sometimes the authority and sometimes a copy is harder to read than one that always says what
     * it tests.
     */
    @Column(name = "field_name", nullable = false, length = 63)
    private String fieldName;

    @Enumerated(EnumType.STRING)
    @Column(name = "operator", nullable = false, length = 20)
    private StyleOperator operator = StyleOperator.EQ;

    /**
     * The single operand, or the lower bound of a {@code BETWEEN}.
     *
     * <p>Text for every data type. The attribute's declared type in
     * {@code gis.layer_attribute_master} is what says how to read it — keeping the type in one place
     * is the whole point of the catalogue, and a typed column here would be a second opinion about
     * it.
     */
    @Column(name = "value_1", length = 255)
    private String value1;

    /** The upper bound of a {@code BETWEEN}; unused otherwise. */
    @Column(name = "value_2", length = 255)
    private String value2;

    /**
     * The member list for {@code IN}.
     *
     * <p>A JSON array rather than a comma-separated string: a category value can legitimately
     * contain a comma ("Ward 3, North"), and splitting on one would silently turn a single category
     * into two that match nothing.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "value_list", columnDefinition = "jsonb")
    private List<String> valueList;

    /** What the legend calls this class. "Faulty" reads better than "status = FAULT". */
    @Column(name = "label", length = 120)
    private String label;

    /**
     * The symbology this class overrides the base with, in the same vocabulary as
     * {@link LayerStyle#getSymbol()}.
     *
     * <p>Keys absent here fall through to the base symbol, so a rule that only changes the colour
     * says only that — and a later change to the base width reaches every class rather than only
     * the ones that happened not to restate it.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "symbol", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> symbol = new HashMap<>();

    /**
     * Evaluation order.
     *
     * <p>Not cosmetic. MapLibre's {@code case} expression is first-match, so overlapping rules
     * resolve to whichever comes first — here, in the preview and on the map alike. Storing the
     * order is what keeps those three agreeing.
     */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 100;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}

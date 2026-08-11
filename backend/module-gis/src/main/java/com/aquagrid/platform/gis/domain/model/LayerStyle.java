package com.aquagrid.platform.gis.domain.model;

import com.aquagrid.platform.common.domain.AuditableEntity;
import com.aquagrid.platform.gis.domain.enums.StyleType;
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
import java.util.Map;
import java.util.UUID;

/**
 * One way of drawing a layer.
 *
 * <p>A layer may hold several — "Operational", "Condition survey", "Pressure zones" — of which one
 * is the default the map uses. Styles are metadata: nothing here is MapLibre's own vocabulary, and
 * {@code MapLibreStyleComposer} is the only thing that translates. Storing raw MapLibre paint
 * instead would weld the database to one renderer's spelling and let the style editor persist
 * expressions nobody validated.
 *
 * <p>{@link #symbol} and {@link #label} are JSONB documents rather than a table of properties. The
 * brief suggests a {@code layer_style_property} row per property; V1333 records why that was
 * declined — the values are heterogeneous, the read becomes a join and a pivot, and MapLibre's own
 * model is a JSON object, so an object is the honest representation. What is genuinely relational —
 * one row per category or band — is {@link LayerStyleRule}.
 */
@Getter
@Setter
@Entity
@Table(name = "layer_style", schema = "gis")
public class LayerStyle extends AuditableEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "layer_id", nullable = false, updatable = false)
    private UUID layerId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "style_type", nullable = false, length = 20)
    private StyleType styleType = StyleType.SIMPLE;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /**
     * Whether this is the style the map draws.
     *
     * <p>Exactly one active default per layer, guaranteed by a partial unique index rather than by
     * this service clearing the previous one first. A service that clears-then-sets is correct until
     * two administrators do it in the same second, and "which style does the map use" is a question
     * that must not have two answers.
     */
    @Column(name = "is_default", nullable = false)
    private boolean defaultStyle = false;

    /**
     * Zoom window for this style, within the layer's own.
     *
     * <p>A layer visible from z10 can carry a detailed style that only appears from z15 — which is
     * how a network reads as plain lines at district scale and as annotated, classified assets at
     * street scale, without being two layers that then have to be kept in step.
     */
    @Column(name = "min_zoom", nullable = false)
    private short minZoom = 0;

    @Column(name = "max_zoom", nullable = false)
    private short maxZoom = 24;

    /**
     * The base symbol, in AquaGrid's vocabulary — see {@code SymbolKeys}.
     *
     * <p>Holds the keys for all three geometry families at once rather than only the layer's own,
     * because a {@code GEOMETRY} layer genuinely carries all three: the facility layers hold
     * footprints and locations together, depending on how each project was surveyed, and a style
     * that could describe only one of them would leave the other unpainted.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "symbol", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> symbol = new HashMap<>();

    /**
     * Label configuration: {@code enabled}, {@code field}, {@code textSize}, {@code textColor},
     * {@code haloColor}, {@code haloWidth}, {@code minZoom}, {@code maxZoom}.
     *
     * <p>{@code field} names an attribute in {@code gis.layer_attribute_master} and is validated
     * against it on write. There is no second field list anywhere in this module — the label picker
     * reads Data Management's catalogue, so a field retired there stops being offerable here.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "label", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> label = new HashMap<>();

    /**
     * The attribute a {@code CATEGORICAL} or {@code GRADUATED} style classifies on. Null for
     * {@code SIMPLE}, and for {@code RULE_BASED} where each rule names its own field.
     */
    @Column(name = "classify_field", length = 63)
    private String classifyField;
}

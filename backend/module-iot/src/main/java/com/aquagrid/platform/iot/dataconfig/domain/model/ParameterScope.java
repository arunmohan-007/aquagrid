package com.aquagrid.platform.iot.dataconfig.domain.model;

/**
 * Who a parameter definition applies to.
 *
 * <p>Two levels of specificity over one vocabulary, not two kinds of thing — which is why both live
 * in {@code iot.device_data_parameter} rather than in a template table and an override table. A
 * second table would duplicate every column and force every reader to union them, and the first
 * divergence between the two copies would be a parameter that validates differently depending on
 * which screen created it.
 *
 * <p>Resolution is in {@code ParameterResolver}: the device type's rows first, then the device's own
 * rows of the same name replace them entire.
 */
public enum ParameterScope {

    /**
     * The template every device of a type inherits. A flow meter's {@code flow_rate},
     * {@code total_volume}, {@code pressure} and {@code signal_strength} are declared once here and
     * apply to every flow meter the tenant registers, including ones registered next year.
     */
    DEVICE_TYPE,

    /**
     * One device's own declaration, overriding its type's template where they collide.
     *
     * <p>The case this exists for is the ordinary one: a fleet is not uniform. One pump on a site
     * reports its temperature and the rest do not; one meter from a different vendor reports volume
     * in cubic metres where the template says litres. Without this, a tenant's only options would be
     * to widen the template until it describes nothing precisely, or to invent a device type per
     * device.
     */
    DEVICE;

    /** Resolves a name from the API or the database; null when it names no scope. */
    public static ParameterScope from(String name) {
        if (name == null) {
            return null;
        }
        for (ParameterScope scope : values()) {
            if (scope.name().equalsIgnoreCase(name.trim())) {
                return scope;
            }
        }
        return null;
    }
}

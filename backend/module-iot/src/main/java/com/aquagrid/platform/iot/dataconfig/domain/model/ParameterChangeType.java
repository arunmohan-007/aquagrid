package com.aquagrid.platform.iot.dataconfig.domain.model;

/**
 * The kinds of change {@code iot.device_parameter_history} records.
 *
 * <p>There is no {@code DELETED}. Deactivation is this module's only delete and it removes nothing —
 * every reading already written under a parameter stays exactly where it is, readable again the
 * moment the parameter is reactivated.
 */
public enum ParameterChangeType {
    CREATED,
    UPDATED,
    DEACTIVATED,
    REACTIVATED
}

package com.aquagrid.platform.iot.dataconfig.api;

import com.aquagrid.platform.iot.dataconfig.domain.model.DeviceDataParameter;
import com.aquagrid.platform.iot.dataconfig.domain.model.ParameterDataType;
import com.aquagrid.platform.iot.dataconfig.domain.model.ParameterScope;
import com.aquagrid.platform.iot.dataconfig.domain.model.QualityStatus;

import java.util.UUID;

/**
 * One parameter's definition, as consumers read it.
 *
 * <p>An immutable snapshot rather than the entity, for the reason {@code AttributeDefinition} gives
 * on the GIS side: handing a consumer the entity gives it a dirty-checked object it can modify by
 * accident — and this one is read on the reception path, inside a cache, from more than one thread.
 * A record cannot be modified by accident and is safe to share.
 *
 * @param id                the catalogue row, recorded on each reading it judges so a later widening
 *                          of the range does not make the historical verdict unreadable
 * @param scope             whether this came from the device type's template or the device's own row
 * @param parameterName     canonical name, as written to {@code iot.device_readings.metric}
 * @param displayName       what an operator reads
 * @param dataType          how the value should be read
 * @param unit              canonical unit code, or null where the parameter carries none
 * @param category          a {@code MetricCatalog.Category} name — the group it is displayed under
 * @param payloadKey        the key to match in an incoming payload, already resolved
 * @param mandatory         a packet without it is incomplete, never invalid
 * @param dashboardVisible  whether it appears on the device dashboard
 * @param useForAlarm       whether the alarm engine may act on it
 * @param useForReports     whether exports include it
 * @param minValue          lower bound, or null for unbounded
 * @param maxValue          upper bound, or null for unbounded
 * @param decimalPrecision  digits after the point, applied by rounding
 * @param sampleValue       a representative value, as the administrator typed it. Carried here
 *                          rather than left on the entity because the simulator generates a device's
 *                          non-metering parameters from it — the configuration is the model, so the
 *                          thing the configuration says this parameter looks like has to travel with
 *                          it
 * @param defaultValue      the value to assume where the parameter is configured and absent
 * @param sortOrder         display order within its device or device type
 */
public record ParameterDefinition(
        UUID id,
        ParameterScope scope,
        String parameterName,
        String displayName,
        ParameterDataType dataType,
        String unit,
        String category,
        String payloadKey,
        boolean mandatory,
        boolean dashboardVisible,
        boolean useForAlarm,
        boolean useForReports,
        Double minValue,
        Double maxValue,
        Integer decimalPrecision,
        String sampleValue,
        String defaultValue,
        int sortOrder
) {

    public static ParameterDefinition from(DeviceDataParameter parameter) {
        return new ParameterDefinition(
                parameter.getId(),
                parameter.getScope(),
                parameter.getParameterName(),
                parameter.getDisplayName(),
                parameter.getDataType(),
                parameter.getUnit(),
                parameter.getCategory(),
                parameter.matchKey(),
                parameter.isMandatory(),
                parameter.isDashboardVisible(),
                parameter.isUseForAlarm(),
                parameter.isUseForReports(),
                parameter.getMinValue(),
                parameter.getMaxValue(),
                parameter.getDecimalPrecision(),
                parameter.getSampleValue(),
                parameter.getDefaultValue(),
                parameter.getSortOrder());
    }

    /**
     * Judges one received value against this definition.
     *
     * <p>Returns a verdict; never throws, and never asks the caller to drop anything. That is the
     * module's central rule expressed as a method signature: there is no return value here that
     * means "discard this". A pressure of 47 bar on a 10 bar main comes back
     * {@link QualityStatus#OUT_OF_RANGE} and is stored, because it is the most important reading of
     * the day.
     *
     * @param value the value as it arrived, or null when the parameter was absent
     */
    public QualityStatus judge(Object value) {
        if (value == null) {
            // Absent and optional is not a verdict about a value — there is no value. Only a
            // mandatory parameter's absence is a finding, and it is the one this module records
            // rather than leaving as a gap in a series that says nothing.
            return mandatory ? QualityStatus.MISSING : QualityStatus.VALID;
        }
        if (!dataType.accepts(value)) {
            return QualityStatus.INVALID;
        }
        Double numeric = dataType.toReadingValue(value);
        if (numeric != null) {
            if (minValue != null && numeric < minValue) {
                return QualityStatus.OUT_OF_RANGE;
            }
            if (maxValue != null && numeric > maxValue) {
                return QualityStatus.OUT_OF_RANGE;
            }
        }
        return QualityStatus.VALID;
    }

    /** The stored form of a value: rounded to the declared precision, or null if not a reading. */
    public Double readingValue(Object value) {
        return dataType.round(dataType.toReadingValue(value), decimalPrecision);
    }
}

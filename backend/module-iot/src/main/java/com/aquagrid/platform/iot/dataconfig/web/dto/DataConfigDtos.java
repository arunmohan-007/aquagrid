package com.aquagrid.platform.iot.dataconfig.web.dto;

import com.aquagrid.platform.iot.dataconfig.api.ParameterDefinition;
import com.aquagrid.platform.iot.dataconfig.domain.model.DeviceDataParameter;
import com.aquagrid.platform.iot.dataconfig.domain.model.DeviceParameterHistory;
import com.aquagrid.platform.iot.dataconfig.domain.model.DiscoveredParameter;
import com.aquagrid.platform.iot.dataconfig.domain.model.MeasurementUnit;
import com.aquagrid.platform.iot.dataconfig.domain.model.ParameterDataType;
import com.aquagrid.platform.iot.dataconfig.domain.model.ParameterScope;
import com.aquagrid.platform.iot.dataconfig.domain.model.RawTelemetry;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Device Data Configuration wire contract.
 *
 * <p>Records rather than the entities, for the reason the rest of the platform gives: an entity on
 * the wire is a lazy-loading, dirty-checked object whose JSON shape is decided by JPA rather than by
 * anyone, and every field added to it silently becomes public API.
 */
public final class DataConfigDtos {

    private DataConfigDtos() {
    }

    // ---- Parameters ----------------------------------------------------------------------------

    /**
     * One catalogue row as the grid shows it.
     *
     * @param inherited true when this row came from the device type's template rather than from the
     *                  device itself. The grid needs this to show, on one device's screen, which
     *                  parameters are the type's and which the device overrides — without it, an
     *                  operator editing an inherited row would be surprised to change every device
     *                  of that type
     */
    public record ParameterDto(
            UUID id,
            String scope,
            String deviceType,
            UUID deviceId,
            String parameterName,
            String displayName,
            String description,
            String dataType,
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
            boolean active,
            int sortOrder,
            boolean inherited,
            Instant createdAt,
            UUID createdBy,
            Instant updatedAt,
            UUID updatedBy
    ) {

        /**
         * The resolved view of a parameter, from the snapshot the reception path uses.
         *
         * <p>The audit columns come back null, and deliberately: this is the effective-configuration
         * read, whose question is "what is this device running under", and answering it by fetching
         * every parameter's entity to fill in four timestamps would be one query per row on a screen
         * that already has the answer. The grid, which does show who changed what, reads the
         * entities.
         */
        public static ParameterDto fromDefinition(ParameterDefinition d) {
            return new ParameterDto(
                    d.id(),
                    d.scope().name(),
                    // The target columns are absent from the snapshot because the snapshot has
                    // already been resolved against one: asking "which device type is this for" of
                    // a definition already applied to a device answers a question nobody has.
                    null,
                    null,
                    d.parameterName(), d.displayName(), null, d.dataType().name(), d.unit(),
                    d.category(), d.payloadKey(), d.mandatory(), d.dashboardVisible(),
                    d.useForAlarm(), d.useForReports(), d.minValue(), d.maxValue(),
                    d.decimalPrecision(), d.sampleValue(), d.defaultValue(),
                    // Only active definitions are resolved at all — an inactive one is not part of
                    // what the device is running under, which is what this view describes.
                    true,
                    d.sortOrder(),
                    d.scope() == ParameterScope.DEVICE_TYPE,
                    null, null, null, null);
        }

        public static ParameterDto from(DeviceDataParameter p, UUID viewedFromDeviceId) {
            return new ParameterDto(
                    p.getId(),
                    p.getScope().name(),
                    p.getDeviceType(),
                    p.getDeviceId(),
                    p.getParameterName(),
                    p.getDisplayName(),
                    p.getDescription(),
                    p.getDataType().name(),
                    p.getUnit(),
                    p.getCategory(),
                    // The resolved match key, not the raw column: a client that had to reproduce
                    // "null means same as the name" would be a second copy of that rule.
                    p.matchKey(),
                    p.isMandatory(),
                    p.isDashboardVisible(),
                    p.isUseForAlarm(),
                    p.isUseForReports(),
                    p.getMinValue(),
                    p.getMaxValue(),
                    p.getDecimalPrecision(),
                    p.getSampleValue(),
                    p.getDefaultValue(),
                    p.isActive(),
                    p.getSortOrder(),
                    viewedFromDeviceId != null && p.getDeviceId() == null,
                    p.getCreatedAt(),
                    p.getCreatedBy(),
                    p.getUpdatedAt(),
                    p.getUpdatedBy());
        }
    }

    /**
     * Create request.
     *
     * <p>{@code scope} decides which of {@code deviceType} and {@code deviceId} is required. The
     * server checks rather than trusting the client, so a mismatch produces a sentence instead of a
     * constraint-violation stack trace.
     */
    public record CreateParameterRequest(
            String scope,
            String deviceType,
            UUID deviceId,
            String parameterName,
            String displayName,
            String description,
            String dataType,
            String unit,
            String category,
            String payloadKey,
            Boolean mandatory,
            Boolean dashboardVisible,
            Boolean useForAlarm,
            Boolean useForReports,
            Double minValue,
            Double maxValue,
            Integer decimalPrecision,
            String sampleValue,
            String defaultValue,
            Boolean active,
            Integer sortOrder,
            String changeReason,
            /** Set when raised from the Discovered Parameters screen, so that queue can close. */
            UUID discoveredParameterId
    ) {
    }

    /**
     * Update request. Every field is optional and <b>null means "leave alone"</b>, never "clear".
     *
     * <p>Send {@code NaN} for {@code minValue} or {@code maxValue} to remove a bound — an explicit
     * signal, because "I did not send a maximum" and "this parameter has no maximum" are different
     * statements and a nullable field cannot carry both.
     */
    public record UpdateParameterRequest(
            String displayName,
            String description,
            String dataType,
            String unit,
            String category,
            String payloadKey,
            Boolean mandatory,
            Boolean dashboardVisible,
            Boolean useForAlarm,
            Boolean useForReports,
            Double minValue,
            Double maxValue,
            Integer decimalPrecision,
            String sampleValue,
            String defaultValue,
            Integer sortOrder,
            String changeReason,
            Boolean confirmBreakingChange
    ) {
    }

    /** A deactivate/reactivate/ignore reason. Optional, and recorded on the history row. */
    public record ReasonRequest(String reason) {
    }

    // ---- Catalogue metadata --------------------------------------------------------------------

    /**
     * One selectable data type, with the configuration it accepts.
     *
     * <p>Served rather than hard-coded in the client so the form only offers facets the server will
     * honour — a range box on a TEXT parameter is a field whose value is silently discarded.
     */
    public record DataTypeDto(String value, String label, boolean numeric, boolean usesPrecision,
                              boolean storedAsReading) {

        public static DataTypeDto from(ParameterDataType type) {
            return new DataTypeDto(type.name(), humanise(type.name()), type.isNumeric(),
                    type.usesPrecision(), type.isReading());
        }
    }

    /** One reading category, from {@code MetricCatalog.Category}. */
    public record CategoryDto(String value, String label) {
    }

    /** One unit. {@code standard} marks a platform-supplied row a tenant may not edit. */
    public record UnitDto(UUID id, String code, String label, String quantity, String description,
                          boolean standard, boolean active, int sortOrder) {

        public static UnitDto from(MeasurementUnit unit) {
            return new UnitDto(unit.getId(), unit.getCode(), unit.getLabel(), unit.getQuantity(),
                    unit.getDescription(), unit.getOrganizationId() == null, unit.isActive(),
                    unit.getSortOrder());
        }
    }

    public record CreateUnitRequest(String code, String label, String quantity, String description) {
    }

    /**
     * A device type and how much of it is configured.
     *
     * <p>{@code activeParameters} is what makes the picker usable rather than decorative: "Flow
     * Meter — 5 parameters" tells an administrator at a glance which types have been curated and
     * which are still storing everything as UNKNOWN.
     */
    public record DeviceTypeSummaryDto(String value, String label, long activeParameters,
                                       long deviceCount) {
    }

    // ---- History -------------------------------------------------------------------------------

    public record HistoryEntryDto(Long id, UUID parameterId, String parameterName, String changeType,
                                  Map<String, Object> previousState, Map<String, Object> newState,
                                  String changeReason, UUID changedBy, Instant changedAt) {

        public static HistoryEntryDto from(DeviceParameterHistory h) {
            return new HistoryEntryDto(h.getId(), h.getParameterId(), h.getParameterName(),
                    h.getChangeType().name(), h.getPreviousState(), h.getNewState(),
                    h.getChangeReason(), h.getChangedBy(), h.getChangedAt());
        }
    }

    // ---- Discovery -----------------------------------------------------------------------------

    /** One parameter a device has sent that the catalogue does not describe. */
    public record DiscoveredParameterDto(
            UUID id,
            UUID deviceId,
            String deviceCode,
            String deviceType,
            String parameterName,
            String sampleValue,
            String detectedDataType,
            Instant firstSeenAt,
            Instant lastSeenAt,
            long occurrences,
            String status,
            UUID parameterId,
            Instant resolvedAt
    ) {

        public static DiscoveredParameterDto from(DiscoveredParameter d) {
            return new DiscoveredParameterDto(d.getId(), d.getDeviceId(), d.getDeviceCode(),
                    d.getDeviceType(), d.getParameterName(), d.getSampleValue(),
                    d.getDetectedDataType() == null ? null : d.getDetectedDataType().name(),
                    d.getFirstSeenAt(), d.getLastSeenAt(), d.getOccurrences(), d.getStatus().name(),
                    d.getParameterId(), d.getResolvedAt());
        }
    }

    // ---- Raw payloads --------------------------------------------------------------------------

    /**
     * One stored payload.
     *
     * <p>The payload is returned as the JSON object it was stored as, not as a string. A client that
     * had to parse a string to render a tree would be re-deriving structure the database already
     * holds — and would have to decide what to do when the parse failed, which for a column that is
     * JSONB cannot happen.
     */
    public record RawTelemetryDto(
            UUID id,
            UUID deviceId,
            String deviceCode,
            UUID assetId,
            String assetNumber,
            Instant deviceTimestamp,
            Instant receivedAt,
            String communicationType,
            String connectionMode,
            UUID messageId,
            String correlationId,
            String sourceIp,
            Map<String, Object> payload,
            String payloadEncoding,
            int payloadSize,
            String processingStatus,
            String processingError
    ) {

        public static RawTelemetryDto from(RawTelemetry r) {
            return new RawTelemetryDto(r.getId(), r.getDeviceId(), r.getDeviceCode(), r.getAssetId(),
                    r.getAssetNumber(), r.getDeviceTimestamp(), r.getReceivedAt(),
                    r.getCommunicationType(), r.getConnectionMode(), r.getMessageId(),
                    r.getCorrelationId(), r.getSourceIp(), r.getPayload(), r.getPayloadEncoding(),
                    r.getPayloadSize(), r.getProcessingStatus(), r.getProcessingError());
        }
    }

    /**
     * A device's fully resolved configuration.
     *
     * <p>Template and overrides already combined, as the reception path will see them — so an
     * operator can answer "what is this device actually running under" without doing the resolution
     * in their head from two lists.
     */
    public record EffectiveConfigDto(UUID deviceId, String deviceCode, String deviceType,
                                     List<ParameterDto> parameters, long pendingDiscoveries) {
    }

    /** {@code PUMP_CONTROLLER} → "Pump Controller", for the device-type picker. */
    public static String humaniseDeviceType(String constant) {
        return humanise(constant);
    }

    /** {@code LONG_INTEGER} → "Long Integer". */
    static String humanise(String constant) {
        String[] parts = constant.split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(part.charAt(0)).append(part.substring(1).toLowerCase());
        }
        return out.toString();
    }
}

package com.aquagrid.platform.iot.dataconfig.application.service;

import com.aquagrid.platform.common.audit.AuditCategory;
import com.aquagrid.platform.common.audit.AuditEvent;
import com.aquagrid.platform.common.audit.AuditEventTypes;
import com.aquagrid.platform.common.audit.AuditService;
import com.aquagrid.platform.common.audit.AuditSeverity;
import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.iot.dataconfig.application.command.ParameterCommands;
import com.aquagrid.platform.iot.dataconfig.domain.model.DeviceDataParameter;
import com.aquagrid.platform.iot.dataconfig.domain.model.DeviceParameterHistory;
import com.aquagrid.platform.iot.dataconfig.domain.model.DiscoveredParameter;
import com.aquagrid.platform.iot.dataconfig.domain.model.DiscoveryStatus;
import com.aquagrid.platform.iot.dataconfig.domain.model.ParameterChangeType;
import com.aquagrid.platform.iot.dataconfig.domain.model.ParameterDataType;
import com.aquagrid.platform.iot.dataconfig.domain.model.ParameterScope;
import com.aquagrid.platform.iot.dataconfig.infrastructure.persistence.DeviceDataParameterRepository;
import com.aquagrid.platform.iot.dataconfig.infrastructure.persistence.DeviceParameterHistoryRepository;
import com.aquagrid.platform.iot.dataconfig.infrastructure.persistence.DiscoveredParameterRepository;
import com.aquagrid.platform.iot.domain.model.Device;
import com.aquagrid.platform.iot.domain.model.MetricCatalog;
import com.aquagrid.platform.iot.infrastructure.persistence.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The parameter catalogue's only writer, and its read side for the screen.
 *
 * <p>What a device sends is not configuration in the ordinary sense. It is the contract every
 * dashboard already built, every alarm rule already armed and every report already scheduled was
 * written against — and, unlike the GIS attribute catalogue this is modelled on, the data it
 * describes has already arrived and cannot be re-requested. Changing a definition is therefore
 * closer to a migration than to a preference, and this class is where that is made explicit rather
 * than discovered later:
 *
 * <ul>
 *   <li><b>There is no delete.</b> Deactivation retires a parameter from dashboards, alarms and
 *       reports while every reading written under it stays exactly where it is, readable again the
 *       moment it is reactivated. Removing the definition would orphan data nothing else describes.
 *       The same is true one level down: the raw payloads in {@code iot.device_raw_telemetry} are
 *       untouched by anything in this class.</li>
 *   <li><b>Retyping and re-keying are allowed, confirmed and recorded.</b> Both reach readings that
 *       already exist — a retype changes how historical values should be read, and changing the
 *       payload key changes which field the parameter has been describing all along. Refusing
 *       outright would leave an administrator whose vendor renamed a field with no path but a
 *       support ticket, so both instead require {@code confirmBreakingChange} and the service
 *       answers an unconfirmed attempt with a description of what will happen — so the client can
 *       raise a dialog that says something specific rather than "Are you sure?".</li>
 *   <li><b>The name is immutable.</b> This is the one place stricter than the GIS catalogue, which
 *       does allow a rename. There, a rename rewrites the JSONB key on every affected asset in one
 *       statement and the data ends up consistent. Here, the equivalent would be an UPDATE across
 *       every historical row of {@code iot.device_readings} — the module's largest table and a
 *       future hypertable, where rewriting history is exactly what a time-series store is built not
 *       to do. Deactivate and create instead; both names then mean what they meant when they were
 *       written.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceDataConfigService {

    /** Same grammar as the CHECK in V1405 and as {@code FieldNamePolicy} on the GIS side. */
    private static final Pattern PARAMETER_NAME = Pattern.compile("^[a-z][a-z0-9_]*$");

    /**
     * The categories {@code MetricCatalog} already declares.
     *
     * <p>Derived from the enum rather than listed, so a category added there is offered here without
     * anyone remembering to. A category this table could hold and the telemetry screen could not
     * render would be a group nobody ever sees.
     */
    private static final Set<String> CATEGORIES = java.util.Arrays
            .stream(MetricCatalog.Category.values()).map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    private final DeviceDataParameterRepository repository;
    private final DeviceParameterHistoryRepository historyRepository;
    private final DiscoveredParameterRepository discoveryRepository;
    private final DeviceRepository deviceRepository;
    private final ParameterResolver resolver;
    private final AuditService auditService;

    // ---- Reads ---------------------------------------------------------------------------------

    /** The configuration grid's query. Includes inactive rows — see {@link #deactivate}. */
    @Transactional(readOnly = true)
    public Page<DeviceDataParameter> search(UUID organizationId, ParameterQuery query, Pageable pageable) {
        /*
         * Asked for one device, the grid must also show the type template that device inherits: the
         * union is what the device actually runs under, and showing only its overrides would present
         * an almost-empty grid for a fully configured device. So the device's type is resolved here
         * and passed to the query as a second, inherited filter.
         */
        String inheritedType = null;
        if (query.deviceId() != null) {
            inheritedType = deviceRepository.findByIdAndOrganizationId(query.deviceId(), organizationId)
                    .map(Device::getDeviceType)
                    .orElse(null);
        }
        return repository.search(organizationId,
                query.scope() == null ? null : query.scope().name(),
                blankToNull(query.deviceType()),
                query.deviceId(),
                inheritedType,
                blankToNull(query.search()),
                query.dataType() == null ? null : query.dataType().name(),
                blankToNull(query.category()),
                query.mandatory(), query.dashboardVisible(), query.useForAlarm(),
                query.useForReports(), query.active(),
                pageable);
    }

    @Transactional(readOnly = true)
    public DeviceDataParameter require(UUID parameterId, UUID organizationId) {
        return repository.findByIdAndOrganizationId(parameterId, organizationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No device parameter " + parameterId + " in this organisation."));
    }

    @Transactional(readOnly = true)
    public Page<DeviceParameterHistory> history(UUID parameterId, UUID organizationId, Pageable pageable) {
        require(parameterId, organizationId);
        return historyRepository.findByOrganizationIdAndParameterIdOrderByChangedAtDesc(
                organizationId, parameterId, pageable);
    }

    /**
     * How many active parameters each device type declares.
     *
     * <p>What makes the device-type picker usable rather than decorative: "Flow Meter — 5
     * parameters" tells an administrator at a glance which types have been curated and which are
     * still storing everything as UNKNOWN.
     */
    @Transactional(readOnly = true)
    public Map<String, Long> templateCounts(UUID organizationId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : repository.countActiveByDeviceType(organizationId)) {
            counts.put((String) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    // ---- Create --------------------------------------------------------------------------------

    @Transactional
    public DeviceDataParameter create(UUID organizationId, UUID actorId, String actorName,
                                      ParameterCommands.Create command) {
        ParameterScope scope = requireScope(command.scope());
        String name = normaliseName(command.parameterName());
        String target = resolveTarget(organizationId, scope, command.deviceType(), command.deviceId());

        existing(organizationId, scope, command.deviceType(), command.deviceId(), name)
                .ifPresent(clash -> {
                    throw new BusinessException(ErrorCode.RESOURCE_CONFLICT,
                            clash.isActive()
                                    ? "'" + name + "' is already configured for " + target + "."
                                    : "'" + name + "' exists for " + target + " but is inactive. "
                                      + "Reactivate it rather than creating a second parameter with the "
                                      + "same name — recreating it would silently adopt every reading "
                                      + "already stored under it.");
                });

        DeviceDataParameter parameter = new DeviceDataParameter();
        parameter.setOrganizationId(organizationId);
        parameter.setScope(scope);
        parameter.setDeviceType(scope == ParameterScope.DEVICE_TYPE ? command.deviceType() : null);
        parameter.setDeviceId(scope == ParameterScope.DEVICE ? command.deviceId() : null);
        parameter.setParameterName(name);
        parameter.setDisplayName(displayNameOr(command.displayName(), name));
        parameter.setDescription(blankToNull(command.description()));
        parameter.setDataType(requireDataType(command.dataType()));
        parameter.setUnit(blankToNull(command.unit()));
        parameter.setCategory(requireCategory(command.category()));
        parameter.setPayloadKey(normalisePayloadKey(command.payloadKey(), name));
        parameter.setMandatory(command.mandatory());
        parameter.setDashboardVisible(command.dashboardVisible());
        parameter.setUseForAlarm(command.useForAlarm());
        parameter.setUseForReports(command.useForReports());
        parameter.setMinValue(command.minValue());
        parameter.setMaxValue(command.maxValue());
        parameter.setDecimalPrecision(command.decimalPrecision());
        parameter.setSampleValue(blankToNull(command.sampleValue()));
        parameter.setDefaultValue(blankToNull(command.defaultValue()));
        parameter.setActive(command.active());
        parameter.setSortOrder(command.sortOrder() != null
                ? command.sortOrder()
                : repository.maxSortOrder(organizationId, parameter.getDeviceType(),
                        parameter.getDeviceId()) + 10);
        normaliseTypeFacets(parameter);

        DeviceDataParameter saved = repository.save(parameter);
        recordHistory(saved, ParameterChangeType.CREATED, null, snapshot(saved),
                command.changeReason(), actorId);
        closeDiscoveries(saved, command.discoveredParameterId(), actorId);
        resolver.invalidate(organizationId);

        audit(organizationId, actorId, actorName, AuditEventTypes.DEVICE_PARAMETER_CREATED, saved,
                "Configured parameter '" + name + "' for " + target,
                Map.of("dataType", saved.getDataType().name(),
                        "unit", String.valueOf(saved.getUnit()),
                        "mandatory", saved.isMandatory()));
        log.info("Device parameter {} created for {} in org {}", name, target, organizationId);
        return saved;
    }

    // ---- Update --------------------------------------------------------------------------------

    /**
     * Updates a parameter.
     *
     * <p>Nulls mean "leave alone" throughout — see {@code ParameterCommands.Update}. The two changes
     * that reach existing readings, a retype and a change of payload key, need
     * {@code confirmBreakingChange}; everything else applies directly.
     */
    @Transactional
    public DeviceDataParameter update(UUID parameterId, UUID organizationId, UUID actorId,
                                      String actorName, ParameterCommands.Update command) {
        DeviceDataParameter parameter = require(parameterId, organizationId);
        Map<String, Object> before = snapshot(parameter);

        ParameterDataType newType = command.dataType() == null
                ? parameter.getDataType()
                : command.dataType();
        String newKey = command.payloadKey() == null
                ? parameter.matchKey()
                : normalisePayloadKey(command.payloadKey(), parameter.getParameterName());

        boolean retyping = newType != parameter.getDataType();
        boolean rekeying = !newKey.equals(parameter.matchKey());
        if ((retyping || rekeying) && !command.confirmBreakingChange()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    breakingChangeWarning(parameter, newType, newKey, retyping, rekeying));
        }

        if (command.displayName() != null) parameter.setDisplayName(command.displayName().trim());
        if (command.description() != null) parameter.setDescription(blankToNull(command.description()));
        if (command.unit() != null) parameter.setUnit(blankToNull(command.unit()));
        if (command.category() != null) parameter.setCategory(requireCategory(command.category()));
        if (command.mandatory() != null) parameter.setMandatory(command.mandatory());
        if (command.dashboardVisible() != null) parameter.setDashboardVisible(command.dashboardVisible());
        if (command.useForAlarm() != null) parameter.setUseForAlarm(command.useForAlarm());
        if (command.useForReports() != null) parameter.setUseForReports(command.useForReports());
        if (command.decimalPrecision() != null) parameter.setDecimalPrecision(command.decimalPrecision());
        if (command.sampleValue() != null) parameter.setSampleValue(blankToNull(command.sampleValue()));
        if (command.defaultValue() != null) parameter.setDefaultValue(blankToNull(command.defaultValue()));
        if (command.sortOrder() != null) parameter.setSortOrder(command.sortOrder());
        // NaN is the "clear this bound" signal — see ParameterCommands.Update for why a nullable
        // field cannot express both "not sent" and "no maximum".
        if (command.minValue() != null) parameter.setMinValue(unbounded(command.minValue()));
        if (command.maxValue() != null) parameter.setMaxValue(unbounded(command.maxValue()));
        if (retyping) parameter.setDataType(newType);
        if (rekeying) parameter.setPayloadKey(normalisePayloadKey(newKey, parameter.getParameterName()));

        normaliseTypeFacets(parameter);
        validateRange(parameter);

        DeviceDataParameter saved = repository.save(parameter);
        recordHistory(saved, ParameterChangeType.UPDATED, before, snapshot(saved),
                command.changeReason(), actorId);
        resolver.invalidate(organizationId);

        audit(organizationId, actorId, actorName, AuditEventTypes.DEVICE_PARAMETER_UPDATED, saved,
                "Updated parameter '" + saved.getParameterName() + "' for " + targetOf(saved),
                Map.of("retyped", retyping, "rekeyed", rekeying,
                        "previousDataType", String.valueOf(before.get("dataType")),
                        "previousPayloadKey", String.valueOf(before.get("payloadKey"))));
        return saved;
    }

    /** The sentence the client turns into its confirmation dialog. */
    private static String breakingChangeWarning(DeviceDataParameter parameter, ParameterDataType newType,
                                                String newKey, boolean retyping, boolean rekeying) {
        List<String> effects = new ArrayList<>();
        if (retyping) {
            effects.add("Changing the type of '" + parameter.getParameterName() + "' from "
                    + parameter.getDataType() + " to " + newType + " does not convert readings that "
                    + "are already stored, and it re-decides what counts as valid: values recorded as "
                    + "VALID under the old type may not be readable as the new one. Existing rows keep "
                    + "the quality they were given; only readings from now on are judged as " + newType + ".");
        }
        if (rekeying) {
            effects.add("Changing the source key from '" + parameter.matchKey() + "' to '" + newKey
                    + "' means this parameter stops matching the field it has been describing. "
                    + "Historical readings are untouched, but '" + parameter.matchKey() + "' will "
                    + "start appearing as an unconfigured parameter on the Discovered list unless "
                    + "another parameter claims it.");
        }
        return String.join(" ", effects) + " Resubmit with confirmBreakingChange to proceed.";
    }

    // ---- Soft delete ---------------------------------------------------------------------------

    /**
     * Retires a parameter. The module's only delete, and it removes nothing.
     *
     * <p>Readings already written stay exactly as they are, with the quality they were given. What
     * changes is that the parameter stops being resolved on the reception path — so new readings
     * arrive with quality {@code UNKNOWN} — and stops appearing on dashboards, in alarm rules and in
     * reports. Reactivation restores all of it, and the historical readings come back with it, which
     * is the whole reason the definition is retired rather than dropped.
     *
     * <p>The field itself keeps arriving and keeps being stored. Deactivating a parameter is a
     * statement about attention, not about retention.
     */
    @Transactional
    public DeviceDataParameter deactivate(UUID parameterId, UUID organizationId, UUID actorId,
                                          String actorName, String reason) {
        DeviceDataParameter parameter = require(parameterId, organizationId);
        if (!parameter.isActive()) {
            return parameter;
        }
        Map<String, Object> before = snapshot(parameter);
        parameter.setActive(false);
        DeviceDataParameter saved = repository.save(parameter);

        recordHistory(saved, ParameterChangeType.DEACTIVATED, before, snapshot(saved), reason, actorId);
        resolver.invalidate(organizationId);
        audit(organizationId, actorId, actorName, AuditEventTypes.DEVICE_PARAMETER_DEACTIVATED, saved,
                "Deactivated parameter '" + saved.getParameterName() + "' for " + targetOf(saved)
                        + "; readings keep arriving and are stored, but are no longer validated, "
                        + "charted or reported",
                Map.of("reason", reason == null ? "" : reason));
        return saved;
    }

    @Transactional
    public DeviceDataParameter reactivate(UUID parameterId, UUID organizationId, UUID actorId,
                                          String actorName, String reason) {
        DeviceDataParameter parameter = require(parameterId, organizationId);
        if (parameter.isActive()) {
            return parameter;
        }
        Map<String, Object> before = snapshot(parameter);
        parameter.setActive(true);
        DeviceDataParameter saved = repository.save(parameter);

        recordHistory(saved, ParameterChangeType.REACTIVATED, before, snapshot(saved), reason, actorId);
        resolver.invalidate(organizationId);
        audit(organizationId, actorId, actorName, AuditEventTypes.DEVICE_PARAMETER_REACTIVATED, saved,
                "Reactivated parameter '" + saved.getParameterName() + "' for " + targetOf(saved),
                Map.of("reason", reason == null ? "" : reason));
        return saved;
    }

    // ---- Validation and normalisation ----------------------------------------------------------

    private static ParameterScope requireScope(ParameterScope scope) {
        if (scope == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A parameter must be configured either for a device type or for a specific device.");
        }
        return scope;
    }

    private static ParameterDataType requireDataType(ParameterDataType dataType) {
        if (dataType == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Data type is required.");
        }
        return dataType;
    }

    private static String requireCategory(String category) {
        String value = category == null || category.isBlank() ? "OTHER" : category.trim().toUpperCase();
        if (!CATEGORIES.contains(value)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "'" + category + "' is not a reading category. Choose one of: "
                            + String.join(", ", CATEGORIES) + ".");
        }
        return value;
    }

    /**
     * Checks the scope's target exists, and names it for the error messages.
     *
     * <p>The device is looked up rather than trusted: a device id from another tenant would
     * otherwise create a parameter this organisation can see and no device of theirs will ever use.
     */
    private String resolveTarget(UUID organizationId, ParameterScope scope, String deviceType,
                                 UUID deviceId) {
        if (scope == ParameterScope.DEVICE_TYPE) {
            if (deviceType == null || deviceType.isBlank()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "A device-type template needs a device type.");
            }
            return "device type " + deviceType;
        }
        if (deviceId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A device-specific parameter needs a device.");
        }
        Device device = deviceRepository.findByIdAndOrganizationId(deviceId, organizationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No device " + deviceId + " in this organisation."));
        return "device " + device.getDeviceCode();
    }

    private java.util.Optional<DeviceDataParameter> existing(UUID organizationId, ParameterScope scope,
                                                             String deviceType, UUID deviceId,
                                                             String name) {
        return scope == ParameterScope.DEVICE_TYPE
                ? repository.findTemplateByName(organizationId, deviceType, name)
                : repository.findOverrideByName(organizationId, deviceId, name);
    }

    /**
     * Normalises and checks a parameter name.
     *
     * <p>Lower-cased and underscore-separated, so {@code Water Level} and {@code waterLevel} both
     * become {@code water_level} rather than becoming two parameters. The grammar is enforced here
     * as well as by the CHECK in V1405 because this is where the good error message lives; the
     * constraint is the last line of defence against a row arriving by some other route.
     */
    private static String normaliseName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Parameter name is required.");
        }
        String name = raw.trim()
                // camelCase → snake_case before lower-casing, or waterLevel would become waterlevel.
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase()
                .replaceAll("[\\s\\-.]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        if (!PARAMETER_NAME.matcher(name).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "'" + raw + "' is not a usable parameter name. A name must start with a letter and "
                            + "contain only lower-case letters, digits and underscores — it becomes a "
                            + "column heading in exports and a series name in charts.");
        }
        if (name.length() > 60) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "'" + name + "' is " + name.length() + " characters; readings store at most 60.");
        }
        return name;
    }

    /** Null when the key is the same as the name — see {@code DeviceDataParameter.payloadKey}. */
    private static String normalisePayloadKey(String payloadKey, String parameterName) {
        String key = blankToNull(payloadKey);
        return key == null || key.equals(parameterName) ? null : key;
    }

    /** {@code water_level} → "Water Level", so a display name is never left blank. */
    private static String displayNameOr(String displayName, String parameterName) {
        if (displayName != null && !displayName.isBlank()) {
            return displayName.trim();
        }
        StringBuilder out = new StringBuilder();
        for (String part : parameterName.split("_")) {
            if (part.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    /**
     * Clears the facets that do not apply to the type.
     *
     * <p>A minimum left on a parameter that has become TEXT is not harmless: it is shown in the
     * grid, exported in the parameter list, and read by whatever consumes the catalogue next.
     * Storing only the facets the type actually has keeps "what does this parameter allow"
     * answerable from the row rather than from the row plus a rule about which columns to ignore.
     */
    private static void normaliseTypeFacets(DeviceDataParameter parameter) {
        ParameterDataType type = parameter.getDataType();
        if (!type.isNumeric()) {
            parameter.setMinValue(null);
            parameter.setMaxValue(null);
        }
        if (!type.usesPrecision()) {
            parameter.setDecimalPrecision(null);
        } else if (parameter.getDecimalPrecision() != null
                && (parameter.getDecimalPrecision() < 0 || parameter.getDecimalPrecision() > 10)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Decimal precision must be between 0 and 10.");
        }
        validateRange(parameter);
    }

    private static void validateRange(DeviceDataParameter parameter) {
        Double min = parameter.getMinValue();
        Double max = parameter.getMaxValue();
        if (min != null && max != null && min > max) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Minimum (" + min + ") is above maximum (" + max + "), so no value could ever be "
                            + "valid — every reading would be recorded OUT_OF_RANGE.");
        }
    }

    private static Double unbounded(Double value) {
        return value != null && value.isNaN() ? null : value;
    }

    // ---- Discovery hand-off --------------------------------------------------------------------

    /**
     * Closes the discovery rows a new definition answers.
     *
     * <p>Configuring a device-type template answers the question for every device of that type at
     * once, so all of their pending rows close together. Leaving them open would have the queue keep
     * asking about a parameter that is already defined — the fastest way to make a queue nobody
     * reads.
     *
     * <p>Matched on the payload key, not the canonical name, because a discovery row records the key
     * verbatim as it arrived: the row says {@code totalVolume}, and the parameter that answers it is
     * called {@code volume}.
     */
    private void closeDiscoveries(DeviceDataParameter parameter, UUID explicitDiscoveryId, UUID actorId) {
        List<String> keys = List.of(parameter.matchKey(), parameter.getParameterName());
        List<DiscoveredParameter> pending = new ArrayList<>(discoveryRepository.findPendingByNames(
                parameter.getOrganizationId(), keys,
                parameter.getScope() == ParameterScope.DEVICE_TYPE ? parameter.getDeviceType() : null,
                parameter.getScope() == ParameterScope.DEVICE ? parameter.getDeviceId() : null));

        // The row the administrator actually clicked Configure on, even if its device type has since
        // changed and the query above no longer reaches it. Without this the screen would appear to
        // ignore the button that opened the form.
        if (explicitDiscoveryId != null
                && pending.stream().noneMatch(row -> row.getId().equals(explicitDiscoveryId))) {
            discoveryRepository.findByIdAndOrganizationId(explicitDiscoveryId, parameter.getOrganizationId())
                    .filter(row -> row.getStatus() == DiscoveryStatus.PENDING)
                    .ifPresent(pending::add);
        }

        Instant now = Instant.now();
        for (DiscoveredParameter row : pending) {
            row.setStatus(DiscoveryStatus.CONFIGURED);
            row.setParameterId(parameter.getId());
            row.setResolvedBy(actorId);
            row.setResolvedAt(now);
        }
        discoveryRepository.saveAll(pending);
    }

    // ---- Shared --------------------------------------------------------------------------------

    private void recordHistory(DeviceDataParameter parameter, ParameterChangeType type,
                               Map<String, Object> before, Map<String, Object> after,
                               String reason, UUID actorId) {
        DeviceParameterHistory entry = new DeviceParameterHistory();
        entry.setOrganizationId(parameter.getOrganizationId());
        entry.setParameterId(parameter.getId());
        entry.setParameterName(parameter.getParameterName());
        entry.setScope(parameter.getScope());
        entry.setDeviceType(parameter.getDeviceType());
        entry.setDeviceId(parameter.getDeviceId());
        entry.setChangeType(type);
        entry.setPreviousState(before);
        entry.setNewState(after);
        entry.setChangeReason(blankToNull(reason));
        entry.setChangedBy(actorId);
        historyRepository.save(entry);
    }

    /**
     * The whole definition as a map, for the history table.
     *
     * <p>{@code LinkedHashMap} rather than {@code Map.of}: the values are nullable, which
     * {@code Map.of} rejects, and the insertion order makes the stored JSON readable to a human
     * reading the history straight out of the database.
     */
    private static Map<String, Object> snapshot(DeviceDataParameter p) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("parameterName", p.getParameterName());
        state.put("displayName", p.getDisplayName());
        state.put("description", p.getDescription());
        state.put("dataType", p.getDataType() == null ? null : p.getDataType().name());
        state.put("unit", p.getUnit());
        state.put("category", p.getCategory());
        state.put("payloadKey", p.getPayloadKey());
        state.put("mandatory", p.isMandatory());
        state.put("dashboardVisible", p.isDashboardVisible());
        state.put("useForAlarm", p.isUseForAlarm());
        state.put("useForReports", p.isUseForReports());
        state.put("minValue", p.getMinValue());
        state.put("maxValue", p.getMaxValue());
        state.put("decimalPrecision", p.getDecimalPrecision());
        state.put("sampleValue", p.getSampleValue());
        state.put("defaultValue", p.getDefaultValue());
        state.put("active", p.isActive());
        state.put("sortOrder", p.getSortOrder());
        return state;
    }

    private static String targetOf(DeviceDataParameter parameter) {
        return parameter.getScope() == ParameterScope.DEVICE_TYPE
                ? "device type " + parameter.getDeviceType()
                : "device " + parameter.getDeviceId();
    }

    private void audit(UUID organizationId, UUID actorId, String actorName, String eventType,
                       DeviceDataParameter parameter, String message, Map<String, Object> metadata) {
        Map<String, Object> full = new LinkedHashMap<>(metadata);
        full.put("scope", parameter.getScope().name());
        full.put("parameterName", parameter.getParameterName());
        full.put("deviceType", String.valueOf(parameter.getDeviceType()));
        full.put("deviceId", String.valueOf(parameter.getDeviceId()));
        auditService.record(AuditEvent.builder()
                .organizationId(organizationId)
                .actorUserId(actorId)
                .actorUsername(actorName)
                .eventType(eventType)
                .category(AuditCategory.CONFIGURATION)
                .severity(AuditSeverity.INFO)
                .resourceType("iot.device_data_parameter")
                .resourceId(parameter.getId().toString())
                .success(true)
                .message(message)
                .metadata(full)
                .build());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // ---- Query type ----------------------------------------------------------------------------

    /** The configuration grid's filter set. Every field is optional; null means "do not filter". */
    public record ParameterQuery(
            ParameterScope scope,
            String deviceType,
            UUID deviceId,
            String search,
            ParameterDataType dataType,
            String category,
            Boolean mandatory,
            Boolean dashboardVisible,
            Boolean useForAlarm,
            Boolean useForReports,
            Boolean active
    ) {
    }
}

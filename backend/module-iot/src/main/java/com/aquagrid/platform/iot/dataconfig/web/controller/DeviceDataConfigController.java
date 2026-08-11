package com.aquagrid.platform.iot.dataconfig.web.controller;

import com.aquagrid.platform.common.web.ApiPaths;
import com.aquagrid.platform.common.web.PageResponse;
import com.aquagrid.platform.iot.dataconfig.api.DeviceParameterApi;
import com.aquagrid.platform.iot.dataconfig.api.ParameterDefinition;
import com.aquagrid.platform.iot.dataconfig.application.command.ParameterCommands;
import com.aquagrid.platform.iot.dataconfig.application.service.DeviceDataConfigService;
import com.aquagrid.platform.iot.dataconfig.application.service.ParameterDiscoveryService;
import com.aquagrid.platform.iot.dataconfig.application.service.UnitCatalogService;
import com.aquagrid.platform.iot.dataconfig.domain.model.DeviceDataParameter;
import com.aquagrid.platform.iot.dataconfig.domain.model.ParameterDataType;
import com.aquagrid.platform.iot.dataconfig.domain.model.ParameterScope;
import com.aquagrid.platform.iot.dataconfig.web.dto.DataConfigDtos.CategoryDto;
import com.aquagrid.platform.iot.dataconfig.web.dto.DataConfigDtos.CreateParameterRequest;
import com.aquagrid.platform.iot.dataconfig.web.dto.DataConfigDtos.CreateUnitRequest;
import com.aquagrid.platform.iot.dataconfig.web.dto.DataConfigDtos.DataTypeDto;
import com.aquagrid.platform.iot.dataconfig.web.dto.DataConfigDtos.DeviceTypeSummaryDto;
import com.aquagrid.platform.iot.dataconfig.web.dto.DataConfigDtos.EffectiveConfigDto;
import com.aquagrid.platform.iot.dataconfig.web.dto.DataConfigDtos.HistoryEntryDto;
import com.aquagrid.platform.iot.dataconfig.web.dto.DataConfigDtos.ParameterDto;
import com.aquagrid.platform.iot.dataconfig.web.dto.DataConfigDtos.ReasonRequest;
import com.aquagrid.platform.iot.dataconfig.web.dto.DataConfigDtos.UnitDto;
import com.aquagrid.platform.iot.dataconfig.web.dto.DataConfigDtos.UpdateParameterRequest;
import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.iot.domain.model.Device;
import com.aquagrid.platform.iot.domain.model.MetricCatalog;
import com.aquagrid.platform.iot.infrastructure.persistence.DeviceRepository;
import com.aquagrid.platform.security.core.AuthenticatedPrincipal;
import com.aquagrid.platform.security.core.Permissions;
import com.aquagrid.platform.security.core.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Device Data Configuration — the parameter catalogue API.
 *
 * <p>Read is {@code iot:data-config:read}; every write is {@code iot:data-config:manage}. Both are
 * separate from {@code iot:device:manage} because registering a device and defining what its
 * readings mean are different jobs — see {@code Permissions} and V1109.
 *
 * <p>{@code /data-types}, {@code /categories} and {@code /units} exist so the form never hard-codes
 * what the server will accept. The platform has removed two client-side copies of a server list
 * already — the metric label map in the browser and the GIS importer's {@code TARGET_FIELDS} array —
 * both because the copies eventually disagreed and the disagreement surfaced as a validation error
 * the operator could not act on, the form having offered a value the server refuses.
 */
@Tag(name = "Device data configuration",
        description = "What each device is expected to send, and what to do with it")
@RestController
@RequestMapping(value = ApiPaths.DEVICE_DATA_CONFIG, produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class DeviceDataConfigController {

    private final DeviceDataConfigService configService;
    private final ParameterDiscoveryService discoveryService;
    private final UnitCatalogService unitCatalogService;
    private final DeviceParameterApi parameterApi;
    private final DeviceRepository deviceRepository;

    // ---- Parameters ----------------------------------------------------------------------------

    @GetMapping("/parameters")
    @PreAuthorize("hasAuthority('" + Permissions.DATA_CONFIG_READ + "')")
    @Operation(summary = "List configured parameters",
            description = """
                    Every filter the screen offers. Asked for a device, the result includes the
                    parameters that device inherits from its type as well as its own overrides,
                    because the union is what the device actually runs under.

                    Inactive parameters are included unless `active` says otherwise: a catalogue
                    that hid its own soft deletes would give an administrator no way to tell "never
                    existed" from "retired last March", and no way to revive one.""")
    public PageResponse<ParameterDto> listParameters(
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String deviceType,
            @RequestParam(required = false) UUID deviceId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String dataType,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Boolean mandatory,
            @RequestParam(required = false) Boolean dashboardVisible,
            @RequestParam(required = false) Boolean useForAlarm,
            @RequestParam(required = false) Boolean useForReports,
            @RequestParam(required = false) Boolean active,
            @PageableDefault(size = 25, sort = {"sortOrder", "parameterName"},
                    direction = Sort.Direction.ASC) Pageable pageable) {
        UUID organizationId = SecurityUtils.requirePrincipal().organizationId();
        var query = new DeviceDataConfigService.ParameterQuery(
                ParameterScope.from(scope), deviceType, deviceId, search,
                ParameterDataType.from(dataType), category, mandatory, dashboardVisible,
                useForAlarm, useForReports, active);
        return PageResponse.of(configService.search(organizationId, query, pageable)
                .map(parameter -> ParameterDto.from(parameter, deviceId)));
    }

    @GetMapping("/parameters/{parameterId}")
    @PreAuthorize("hasAuthority('" + Permissions.DATA_CONFIG_READ + "')")
    @Operation(summary = "Get a configured parameter")
    public ParameterDto getParameter(@PathVariable UUID parameterId) {
        return ParameterDto.from(
                configService.require(parameterId, SecurityUtils.requirePrincipal().organizationId()),
                null);
    }

    @PostMapping("/parameters")
    @PreAuthorize("hasAuthority('" + Permissions.DATA_CONFIG_MANAGE + "')")
    @Operation(summary = "Configure a parameter",
            description = """
                    Declares a parameter for a device type (the template every device of that type
                    inherits) or for one device (an override).

                    Configuring a parameter never changes what the platform accepts from a device —
                    it changes what the platform does with what it already accepts. Readings already
                    stored under this name keep the quality they were given; new ones are validated,
                    given the configured unit and become available to dashboards, alarms and
                    reports.""")
    @ResponseStatus(HttpStatus.CREATED)
    public ParameterDto createParameter(@RequestBody CreateParameterRequest request) {
        AuthenticatedPrincipal principal = SecurityUtils.requirePrincipal();
        DeviceDataParameter saved = configService.create(
                principal.organizationId(), principal.userId(), principal.username(),
                new ParameterCommands.Create(
                        ParameterScope.from(request.scope()),
                        request.deviceType(),
                        request.deviceId(),
                        request.parameterName(),
                        request.displayName(),
                        request.description(),
                        requireDataType(request.dataType()),
                        request.unit(),
                        request.category(),
                        request.payloadKey(),
                        Boolean.TRUE.equals(request.mandatory()),
                        // Defaults chosen so an administrator who fills in only the name and type
                        // gets a parameter that is visible and reportable. A parameter created
                        // invisible is one nobody notices they created.
                        request.dashboardVisible() == null || request.dashboardVisible(),
                        Boolean.TRUE.equals(request.useForAlarm()),
                        request.useForReports() == null || request.useForReports(),
                        request.minValue(),
                        request.maxValue(),
                        request.decimalPrecision(),
                        request.sampleValue(),
                        request.defaultValue(),
                        request.active() == null || request.active(),
                        request.sortOrder(),
                        request.changeReason(),
                        request.discoveredParameterId()));
        return ParameterDto.from(saved, null);
    }

    @PutMapping("/parameters/{parameterId}")
    @PreAuthorize("hasAuthority('" + Permissions.DATA_CONFIG_MANAGE + "')")
    @Operation(summary = "Update a configured parameter",
            description = """
                    Omitted fields are left alone; they are not cleared. Send NaN for `minValue` or
                    `maxValue` to remove a bound.

                    Changing the data type or the source key requires `confirmBreakingChange`,
                    because both reach readings that already exist. An unconfirmed attempt is
                    answered with a description of exactly what will happen, so the dialog can say
                    something specific rather than "Are you sure?".""")
    public ParameterDto updateParameter(@PathVariable UUID parameterId,
                                        @RequestBody UpdateParameterRequest request) {
        AuthenticatedPrincipal principal = SecurityUtils.requirePrincipal();
        DeviceDataParameter saved = configService.update(parameterId, principal.organizationId(),
                principal.userId(), principal.username(),
                new ParameterCommands.Update(
                        request.displayName(),
                        request.description(),
                        ParameterDataType.from(request.dataType()),
                        request.unit(),
                        request.category(),
                        request.payloadKey(),
                        request.mandatory(),
                        request.dashboardVisible(),
                        request.useForAlarm(),
                        request.useForReports(),
                        request.minValue(),
                        request.maxValue(),
                        request.decimalPrecision(),
                        request.sampleValue(),
                        request.defaultValue(),
                        request.sortOrder(),
                        request.changeReason(),
                        Boolean.TRUE.equals(request.confirmBreakingChange())));
        return ParameterDto.from(saved, null);
    }

    @PostMapping("/parameters/{parameterId}/deactivate")
    @PreAuthorize("hasAuthority('" + Permissions.DATA_CONFIG_MANAGE + "')")
    @Operation(summary = "Retire a parameter",
            description = """
                    The module's only delete, and it removes nothing. Readings already stored keep
                    the quality they were given, the device keeps sending the field and the platform
                    keeps storing it. What stops is validation, charting and reporting.

                    Reactivating restores all three, and the historical readings come back with it —
                    which is the whole reason the definition is retired rather than dropped.""")
    public ParameterDto deactivate(@PathVariable UUID parameterId,
                                   @RequestBody(required = false) ReasonRequest request) {
        AuthenticatedPrincipal principal = SecurityUtils.requirePrincipal();
        return ParameterDto.from(configService.deactivate(parameterId, principal.organizationId(),
                principal.userId(), principal.username(), reasonOf(request)), null);
    }

    @PostMapping("/parameters/{parameterId}/reactivate")
    @PreAuthorize("hasAuthority('" + Permissions.DATA_CONFIG_MANAGE + "')")
    @Operation(summary = "Bring a retired parameter back")
    public ParameterDto reactivate(@PathVariable UUID parameterId,
                                   @RequestBody(required = false) ReasonRequest request) {
        AuthenticatedPrincipal principal = SecurityUtils.requirePrincipal();
        return ParameterDto.from(configService.reactivate(parameterId, principal.organizationId(),
                principal.userId(), principal.username(), reasonOf(request)), null);
    }

    @GetMapping("/parameters/{parameterId}/history")
    @PreAuthorize("hasAuthority('" + Permissions.DATA_CONFIG_READ + "')")
    @Operation(summary = "How this parameter's definition has changed",
            description = """
                    Whole snapshots either side of each change, so a reading written two years ago
                    can be read against the definition in force when it was written. A parameter
                    whose unit moved from L/min to m3/hr has data on both sides of the change and
                    nothing else to interpret it by.""")
    public PageResponse<HistoryEntryDto> history(@PathVariable UUID parameterId,
                                                 @PageableDefault(size = 25) Pageable pageable) {
        return PageResponse.of(configService
                .history(parameterId, SecurityUtils.requirePrincipal().organizationId(), pageable)
                .map(HistoryEntryDto::from));
    }

    // ---- Targets and metadata ------------------------------------------------------------------

    @GetMapping("/device-types")
    @PreAuthorize("hasAuthority('" + Permissions.DATA_CONFIG_READ + "')")
    @Operation(summary = "Device types, with how much of each is configured",
            description = """
                    The same vocabulary the device register uses — this module defines no device
                    types of its own. `activeParameters` is what makes the picker useful: it says at
                    a glance which types have been curated and which are still storing everything as
                    UNKNOWN.""")
    public List<DeviceTypeSummaryDto> deviceTypes() {
        UUID organizationId = SecurityUtils.requirePrincipal().organizationId();
        Map<String, Long> templateCounts = configService.templateCounts(organizationId);
        Map<String, Long> deviceCounts = deviceRepository.countByDeviceType(organizationId).stream()
                .collect(Collectors.toMap(row -> (String) row[0], row -> ((Number) row[1]).longValue()));
        return DEVICE_TYPES.stream()
                .map(type -> new DeviceTypeSummaryDto(type,
                        com.aquagrid.platform.iot.dataconfig.web.dto.DataConfigDtos
                                .humaniseDeviceType(type),
                        templateCounts.getOrDefault(type, 0L),
                        deviceCounts.getOrDefault(type, 0L)))
                .toList();
    }

    /**
     * The device-type vocabulary, mirroring the CHECK on {@code iot.devices.device_type} (V1401) and
     * {@code DeviceManagementService}. Listed rather than derived because device types are values in
     * this platform, not rows — there is no table to read them from.
     */
    private static final List<String> DEVICE_TYPES = List.of(
            "WATER_METER", "BULK_FLOW_METER", "PRESSURE_SENSOR", "LEVEL_SENSOR",
            "QUALITY_SENSOR", "VALVE_CONTROLLER", "PUMP_CONTROLLER", "ENERGY_METER",
            "GATEWAY", "OTHER");

    @GetMapping("/data-types")
    @PreAuthorize("hasAuthority('" + Permissions.DATA_CONFIG_READ + "')")
    @Operation(summary = "Selectable data types and the configuration each accepts")
    public List<DataTypeDto> dataTypes() {
        return Arrays.stream(ParameterDataType.values()).map(DataTypeDto::from).toList();
    }

    @GetMapping("/categories")
    @PreAuthorize("hasAuthority('" + Permissions.DATA_CONFIG_READ + "')")
    @Operation(summary = "Reading categories",
            description = """
                    The groups the telemetry screen already displays readings in. Served from the
                    same enum that screen reads, so a category cannot be configured that nothing
                    would ever render.""")
    public List<CategoryDto> categories() {
        return Arrays.stream(MetricCatalog.Category.values())
                .map(category -> new CategoryDto(category.name(), category.label()))
                .toList();
    }

    @GetMapping("/units")
    @PreAuthorize("hasAuthority('" + Permissions.DATA_CONFIG_READ + "')")
    @Operation(summary = "Selectable units",
            description = """
                    Platform-supplied units and this organisation's own additions, grouped by what
                    they measure. Rows in a lookup table, not a Java enum, so a district that meters
                    in kilolitres can add one without a release.""")
    public List<UnitDto> units(@RequestParam(defaultValue = "true") boolean activeOnly) {
        return unitCatalogService.list(SecurityUtils.requirePrincipal().organizationId(), activeOnly)
                .stream().map(UnitDto::from).toList();
    }

    @PostMapping("/units")
    @PreAuthorize("hasAuthority('" + Permissions.DATA_CONFIG_MANAGE + "')")
    @Operation(summary = "Add a unit for this organisation",
            description = "Platform-supplied units cannot be edited or shadowed — the same code must "
                    + "not mean two things in one column.")
    @ResponseStatus(HttpStatus.CREATED)
    public UnitDto createUnit(@RequestBody CreateUnitRequest request) {
        return UnitDto.from(unitCatalogService.create(
                SecurityUtils.requirePrincipal().organizationId(),
                request.code(), request.label(), request.quantity(), request.description()));
    }

    // ---- Effective configuration ---------------------------------------------------------------

    @GetMapping("/devices/{deviceId}/effective")
    @PreAuthorize("hasAuthority('" + Permissions.DATA_CONFIG_READ + "')")
    @Operation(summary = "What one device is actually running under",
            description = """
                    Template and overrides already combined, exactly as the reception path resolves
                    them — so "what is this device configured to report" is answerable without
                    performing the resolution by eye from two lists.

                    `pendingDiscoveries` is the counterweight: parameters this device has actually
                    sent that nothing here describes.""")
    public EffectiveConfigDto effective(@PathVariable UUID deviceId) {
        UUID organizationId = SecurityUtils.requirePrincipal().organizationId();
        Device device = deviceRepository.findByIdAndOrganizationId(deviceId, organizationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No device " + deviceId + " in this organisation."));

        List<ParameterDto> parameters = parameterApi
                .effectiveForDevice(organizationId, deviceId, device.getDeviceType())
                .values().stream()
                .sorted(Comparator.comparingInt(ParameterDefinition::sortOrder)
                        .thenComparing(ParameterDefinition::parameterName))
                .map(ParameterDto::fromDefinition)
                .toList();

        return new EffectiveConfigDto(device.getId(), device.getDeviceCode(), device.getDeviceType(),
                parameters, discoveryService.pendingCount(organizationId));
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private static ParameterDataType requireDataType(String raw) {
        ParameterDataType type = ParameterDataType.from(raw);
        if (type == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "'" + raw + "' is not a supported data type. Choose one of: "
                            + Arrays.stream(ParameterDataType.values()).map(Enum::name)
                            .collect(Collectors.joining(", ")) + ".");
        }
        return type;
    }

    private static String reasonOf(ReasonRequest request) {
        return request == null ? null : request.reason();
    }
}

package com.aquagrid.platform.iot.web.controller;

import com.aquagrid.platform.common.web.ApiPaths;
import com.aquagrid.platform.common.web.ClientIpResolver;
import com.aquagrid.platform.common.web.PageResponse;
import com.aquagrid.platform.iot.application.service.DeviceManagementService;
import com.aquagrid.platform.iot.web.dto.DeviceDto;
import com.aquagrid.platform.security.core.Permissions;
import com.aquagrid.platform.security.core.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

/**
 * Device registry API (Module 6).
 *
 * <p>Tenant-scoped reads of the device catalogue, plus registration. Registration is gated by
 * {@code iot:device:manage}; the simulator (Phase 6) registers devices through this endpoint with
 * the simulator's service credential.
 *
 * <p>{@code GET /communication-types} exists so a client never hard-codes which fields belong to
 * which technology: it is served from the same enum the server validates against.
 */
@Tag(name = "Devices", description = "Device registry and telemetry")
@RestController
@RequestMapping(value = ApiPaths.DEVICES, produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceManagementService deviceManagementService;

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.DEVICE_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "List devices in the current tenant")
    public PageResponse<DeviceDto> listDevices(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String transport,
            @RequestParam(required = false) String deviceType,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 25) Pageable pageable) {
        return PageResponse.of(deviceManagementService.listDevices(
                SecurityUtils.requirePrincipal().organizationId(),
                status, transport, deviceType, source, protocol, search, pageable));
    }

    /**
     * The communication-type catalogue driving the conditional half of the registration form.
     *
     * <p>Gated on read, not manage: the same catalogue labels a device's communication block on a
     * read-only detail view.
     */
    @GetMapping("/communication-types")
    @PreAuthorize("hasAuthority('" + Permissions.DEVICE_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "List communication types and the fields each one requires")
    public List<DeviceDto.CommunicationTypeDefinition> communicationTypes() {
        return deviceManagementService.communicationTypes();
    }

    @GetMapping("/{deviceId}")
    @PreAuthorize("hasAuthority('" + Permissions.DEVICE_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get a device")
    public DeviceDto getDevice(@PathVariable UUID deviceId) {
        return deviceManagementService.getDevice(deviceId,
                SecurityUtils.requirePrincipal().organizationId());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.DEVICE_MANAGE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Register a device")
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceDto register(@RequestBody DeviceDto.RegistrationRequest request) {
        return deviceManagementService.register(
                SecurityUtils.requirePrincipal().organizationId(),
                SecurityUtils.currentUserId().orElse(null),
                request);
    }

    @PutMapping("/{deviceId}")
    @PreAuthorize("hasAuthority('" + Permissions.DEVICE_MANAGE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Update a registered device")
    public DeviceDto update(@PathVariable UUID deviceId,
                            @RequestBody DeviceDto.RegistrationRequest request) {
        return deviceManagementService.update(
                deviceId,
                SecurityUtils.requirePrincipal().organizationId(),
                SecurityUtils.currentUserId().orElse(null),
                request);
    }
}

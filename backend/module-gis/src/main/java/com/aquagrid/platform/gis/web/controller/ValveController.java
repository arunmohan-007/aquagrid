package com.aquagrid.platform.gis.web.controller;

import com.aquagrid.platform.common.web.ApiPaths;
import com.aquagrid.platform.common.web.ClientIpResolver;
import com.aquagrid.platform.gis.application.service.ValveService;
import com.aquagrid.platform.gis.web.dto.ValveDto;
import com.aquagrid.platform.security.core.Permissions;
import com.aquagrid.platform.security.core.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Valve CRUD, operation workflow, and operation history.
 *
 * <p>Operation is gated by {@code iot:device:command} (the same permission that gates downlinks) —
 * operating a physical valve is a command to field infrastructure, conceptually identical to
 * sending a device a setpoint. A viewer can read valve state; only an operator can change it.
 */
@Tag(name = "Valves", description = "Valve management and operation")
@RestController
@RequestMapping(value = ApiPaths.ASSETS, produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ValveController {

    private final ValveService valveService;
    private final ClientIpResolver clientIpResolver;

    @GetMapping("/{assetId}/valve")
    @PreAuthorize("hasAuthority('" + Permissions.ASSET_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get the valve record for an asset")
    public ValveDto.ValveDetailDto get(@PathVariable UUID assetId) {
        return valveService.get(assetId, SecurityUtils.requirePrincipal().organizationId());
    }

    @PutMapping(value = "/{assetId}/valve", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.ASSET_UPDATE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Create or update the valve record")
    public ValveDto.ValveDetailDto put(@PathVariable UUID assetId,
                                       @Valid @RequestBody ValveDto.ValveRequest request) {
        return valveService.upsert(assetId, SecurityUtils.requirePrincipal().organizationId(), request);
    }

    @PostMapping(value = "/{assetId}/valve/operate", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.DEVICE_COMMAND + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Operate a valve (OPEN/CLOSED)",
            description = """
                    Records the state transition in an append-only operation log with operator, reason
                    and timestamp. Idempotent on the target state.""")
    public ValveDto.OperationDto operate(@PathVariable UUID assetId,
                                         @Valid @RequestBody ValveDto.OperateRequest request,
                                         HttpServletRequest httpRequest) {
        var principal = SecurityUtils.requirePrincipal();
        return valveService.operate(assetId, principal.organizationId(), principal.userId(),
                clientIpResolver.resolve(httpRequest), request);
    }

    @GetMapping("/{assetId}/valve/operations")
    @PreAuthorize("hasAuthority('" + Permissions.ASSET_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Valve operation history (append-only audit)")
    public List<ValveDto.OperationDto> history(@PathVariable UUID assetId) {
        return valveService.history(assetId, SecurityUtils.requirePrincipal().organizationId());
    }
}

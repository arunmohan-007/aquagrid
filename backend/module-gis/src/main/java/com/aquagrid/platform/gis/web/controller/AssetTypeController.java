package com.aquagrid.platform.gis.web.controller;

import com.aquagrid.platform.common.web.ApiPaths;
import com.aquagrid.platform.gis.application.service.AssetTypeService;
import com.aquagrid.platform.gis.web.dto.AssetTypeDtos;
import com.aquagrid.platform.security.core.Permissions;
import com.aquagrid.platform.security.core.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Asset type detail endpoints (Tank, Reservoir, PumpStation).
 *
 * <p>One controller covers all three: the shape (GET detail, PUT upsert) is identical, and grouping
 * them keeps the route table compact. Each method delegates to {@link AssetTypeService}, which
 * asserts the parent asset is the matching type before touching the type row.
 *
 * <p>Routes are nested under the asset id so the relationship is unambiguous: {@code /assets/{id}/tank}
 * is the tank record for that asset, not a free-standing tank resource.
 */
@Tag(name = "Asset Types", description = "Tank, reservoir and pump-station engineering data")
@RestController
@RequestMapping(value = ApiPaths.ASSETS, produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AssetTypeController {

    private final AssetTypeService assetTypeService;

    // --- Tank ----------------------------------------------------------------------------------

    @GetMapping("/{assetId}/tank")
    @PreAuthorize("hasAuthority('" + Permissions.ASSET_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get the tank record for an asset")
    public AssetTypeDtos.TankDto getTank(@PathVariable UUID assetId) {
        return assetTypeService.getTank(assetId, SecurityUtils.requirePrincipal().organizationId());
    }

    @PutMapping(value = "/{assetId}/tank", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.ASSET_UPDATE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Create or update the tank record",
            description = "The parent asset must be of type TANK.")
    public AssetTypeDtos.TankDto putTank(@PathVariable UUID assetId,
                                         @Valid @RequestBody AssetTypeDtos.TankRequest request) {
        return assetTypeService.upsertTank(assetId, SecurityUtils.requirePrincipal().organizationId(), request);
    }

    // --- Reservoir -----------------------------------------------------------------------------

    @GetMapping("/{assetId}/reservoir")
    @PreAuthorize("hasAuthority('" + Permissions.ASSET_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get the reservoir record for an asset")
    public AssetTypeDtos.ReservoirDto getReservoir(@PathVariable UUID assetId) {
        return assetTypeService.getReservoir(assetId, SecurityUtils.requirePrincipal().organizationId());
    }

    @PutMapping(value = "/{assetId}/reservoir", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.ASSET_UPDATE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Create or update the reservoir record")
    public AssetTypeDtos.ReservoirDto putReservoir(@PathVariable UUID assetId,
                                                   @Valid @RequestBody AssetTypeDtos.ReservoirRequest request) {
        return assetTypeService.upsertReservoir(assetId, SecurityUtils.requirePrincipal().organizationId(), request);
    }

    // --- Pump Station -------------------------------------------------------------------------

    @GetMapping("/{assetId}/pump-station")
    @PreAuthorize("hasAuthority('" + Permissions.ASSET_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get the pump-station record for an asset")
    public AssetTypeDtos.PumpStationDto getPumpStation(@PathVariable UUID assetId) {
        return assetTypeService.getPumpStation(assetId, SecurityUtils.requirePrincipal().organizationId());
    }

    @PutMapping(value = "/{assetId}/pump-station", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.ASSET_UPDATE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Create or update the pump-station record")
    public AssetTypeDtos.PumpStationDto putPumpStation(@PathVariable UUID assetId,
                                                       @Valid @RequestBody AssetTypeDtos.PumpStationRequest request) {
        return assetTypeService.upsertPumpStation(assetId, SecurityUtils.requirePrincipal().organizationId(), request);
    }
}

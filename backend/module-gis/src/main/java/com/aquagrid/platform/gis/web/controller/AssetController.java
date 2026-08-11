package com.aquagrid.platform.gis.web.controller;

import com.aquagrid.platform.common.web.ApiPaths;
import com.aquagrid.platform.common.web.ClientIpResolver;
import com.aquagrid.platform.common.web.PageResponse;
import com.aquagrid.platform.gis.application.service.AssetService;
import com.aquagrid.platform.gis.web.dto.AssetDto;
import com.aquagrid.platform.security.core.Permissions;
import com.aquagrid.platform.security.core.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Asset register CRUD.
 *
 * <p>Tenant-scoped: the organisation is always the principal's, never the request's. Geometry is
 * accepted as GeoJSON and serialised back as GeoJSON, so a browser client passes it straight between
 * OpenLayers and the API without a transform.
 */
@Tag(name = "Assets", description = "Spatial asset register")
@RestController
@RequestMapping(value = ApiPaths.ASSETS, produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;
    private final ClientIpResolver clientIpResolver;

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.ASSET_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "List assets (paginated, filtered)")
    public PageResponse<AssetDto> list(
            @RequestParam(required = false) String assetType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 25) Pageable pageable) {
        return PageResponse.of(assetService.list(
                SecurityUtils.requirePrincipal().organizationId(), assetType, search, pageable));
    }

    @GetMapping("/{assetId}")
    @PreAuthorize("hasAuthority('" + Permissions.ASSET_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get an asset")
    public AssetDto get(@PathVariable UUID assetId) {
        return assetService.get(assetId, SecurityUtils.requirePrincipal().organizationId());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.ASSET_CREATE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Create an asset")
    @ResponseStatus(HttpStatus.CREATED)
    public AssetDto create(@Valid @RequestBody AssetDto.AssetRequest request, HttpServletRequest httpRequest) {
        var principal = SecurityUtils.requirePrincipal();
        return assetService.create(principal.organizationId(), principal.userId(),
                clientIpResolver.resolve(httpRequest), request);
    }

    @PatchMapping(value = "/{assetId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.ASSET_UPDATE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Update an asset")
    public AssetDto update(@PathVariable UUID assetId,
                           @Valid @RequestBody AssetDto.AssetRequest request,
                           HttpServletRequest httpRequest) {
        var principal = SecurityUtils.requirePrincipal();
        return assetService.update(assetId, principal.organizationId(), principal.userId(),
                clientIpResolver.resolve(httpRequest), request);
    }

    @DeleteMapping("/{assetId}")
    @PreAuthorize("hasAuthority('" + Permissions.ASSET_DELETE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Delete an asset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID assetId, HttpServletRequest httpRequest) {
        var principal = SecurityUtils.requirePrincipal();
        assetService.delete(assetId, principal.organizationId(), principal.userId(),
                clientIpResolver.resolve(httpRequest));
    }
}

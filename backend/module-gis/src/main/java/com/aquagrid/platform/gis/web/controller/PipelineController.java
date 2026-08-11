package com.aquagrid.platform.gis.web.controller;

import com.aquagrid.platform.common.web.ApiPaths;
import com.aquagrid.platform.gis.application.service.NetworkTopologyService;
import com.aquagrid.platform.gis.application.service.PipelineService;
import com.aquagrid.platform.gis.web.dto.PipelineDto;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Pipeline CRUD + topology control.
 *
 * <p>The pipeline write path snaps endpoints to network nodes and rebuilds the pgRouting edge table
 * in the same transaction, so a saved pipe is immediately traceable. A manual rebuild endpoint is
 * exposed for operational recovery (e.g. after a bulk import bypassed the per-pipe rebuild).
 */
@Tag(name = "Pipelines", description = "Pipeline network management and topology")
@RestController
@RequestMapping(value = ApiPaths.ASSETS, produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class PipelineController {

    private final PipelineService pipelineService;
    private final NetworkTopologyService topologyService;

    @GetMapping("/{assetId}/pipeline")
    @PreAuthorize("hasAuthority('" + Permissions.ASSET_READ + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get the pipeline record for an asset")
    public PipelineDto.PipelineDetailDto get(@PathVariable UUID assetId) {
        return pipelineService.get(assetId, SecurityUtils.requirePrincipal().organizationId());
    }

    @PutMapping(value = "/{assetId}/pipeline", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.ASSET_UPDATE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Create or update a pipeline",
            description = """
                    Accepts a GeoJSON LineString. Endpoints snap to the nearest network junction
                    within 1m (creating nodes where none exist), and the pgRouting edge table is
                    rebuilt so the pipe is immediately traceable.""")
    public PipelineDto.PipelineDetailDto put(@PathVariable UUID assetId,
                                             @Valid @RequestBody PipelineDto.PipelineRequest request) {
        return pipelineService.upsert(assetId, SecurityUtils.requirePrincipal().organizationId(), request);
    }

    @PostMapping("/network/rebuild")
    @PreAuthorize("hasAuthority('" + Permissions.NETWORK_TRACE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Force a topology rebuild",
            description = "Rebuilds the pgRouting edge table. Use after a bulk import or to recover.")
    public Map<String, Object> rebuild() {
        UUID orgId = SecurityUtils.requirePrincipal().organizationId();
        topologyService.rebuild(orgId);
        return Map.of("status", "REBUILT");
    }
}

package com.aquagrid.platform.gis.web.controller;

import com.aquagrid.platform.common.web.ApiPaths;
import com.aquagrid.platform.gis.application.service.NetworkTraceService;
import com.aquagrid.platform.gis.infrastructure.persistence.NetworkTraceRepository.TraceVertex;
import com.aquagrid.platform.gis.web.dto.PipelineDto;
import com.aquagrid.platform.security.core.Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Network tracing endpoint.
 *
 * <p>One POST takes a source node, a direction and a distance budget, and returns every reachable
 * node with its accumulated cost and geometry. The frontend highlights these on the map — this is
 * the "what's upstream/downstream of this break?" question that isolation and contamination analysis
 * depend on.
 *
 * <p>Gated by {@code gis:network:trace} — a distinct permission from asset read/write, because trace
 * results reveal network topology that not every viewer should see.
 */
@Tag(name = "Network", description = "Pipeline network tracing")
@RestController
@RequestMapping(value = ApiPaths.GIS + "/network", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class NetworkTraceController {

    private final NetworkTraceService traceService;

    @PostMapping("/trace")
    @PreAuthorize("hasAuthority('" + Permissions.NETWORK_TRACE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Trace reachable network nodes from a source",
            description = """
                    Returns every node reachable within the distance budget. Direction UP walks against
                    the edge flow (what feeds this point); DOWN walks with it (what this point feeds).
                    One-way mains are impassable against their direction.""")
    public List<PipelineDto.TraceVertexDto> trace(@Valid @RequestBody PipelineDto.TraceRequest request) {
        NetworkTraceService.Direction dir = "UP".equalsIgnoreCase(request.direction())
                ? NetworkTraceService.Direction.UP
                : NetworkTraceService.Direction.DOWN;
        List<TraceVertex> result = traceService.traceReachable(
                request.sourceNodeId(), dir, request.maxDistanceM());
        return result.stream()
                .map(v -> new PipelineDto.TraceVertexDto(v.nodeId(), v.cost(), v.geometryWkt()))
                .toList();
    }

    @PostMapping("/shortest-path")
    @PreAuthorize("hasAuthority('" + Permissions.NETWORK_TRACE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Shortest path between two nodes, by pipe length")
    public List<PipelineDto.TraceVertexDto> shortestPath(@RequestBody ShortestPathRequest request) {
        List<TraceVertex> result = traceService.shortestPath(request.fromNodeId(), request.toNodeId());
        return result.stream()
                .map(v -> new PipelineDto.TraceVertexDto(v.nodeId(), v.cost(), v.geometryWkt()))
                .toList();
    }

    public record ShortestPathRequest(java.util.UUID fromNodeId, java.util.UUID toNodeId) {
    }
}

package com.aquagrid.platform.gis.web.controller;

import com.aquagrid.platform.common.web.ApiPaths;
import com.aquagrid.platform.gis.application.service.IsolationTraceService;
import com.aquagrid.platform.gis.web.dto.IsolationResult;
import com.aquagrid.platform.security.core.Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Isolation-valve tracing — the control-room question answered during a main break:
 * "which valves must I close to isolate this section?"
 *
 * <p>Gated by {@code gis:network:trace} (same as the pipeline trace) because the result reveals
 * network topology and operational state.
 */
@Tag(name = "Network", description = "Isolation-valve tracing")
@RestController
@RequestMapping(value = ApiPaths.GIS + "/isolation", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class IsolationTraceController {

    private final IsolationTraceService isolationTraceService;

    @PostMapping("/trace")
    @PreAuthorize("hasAuthority('" + Permissions.NETWORK_TRACE + "')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Trace the isolation perimeter from a break point",
            description = """
                    Walks the network in both directions from the source node (bounded by maxDistanceM),
                    halting at CLOSED valves. Returns the valves to close (currently OPEN on the
                    perimeter), the already-closed valves, and the affected node count for impact
                    assessment.""")
    public IsolationResult trace(@RequestBody IsolationTraceRequest request) {
        return isolationTraceService.trace(request.sourceNodeId(), request.maxDistanceM());
    }

    public record IsolationTraceRequest(
            UUID sourceNodeId,
            /** Max walk distance in metres; bounds the trace so it cannot walk the whole network. */
            double maxDistanceM
    ) {
    }
}

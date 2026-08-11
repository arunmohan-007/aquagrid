package com.aquagrid.platform.gis.web.dto;

import java.util.List;
import java.util.UUID;

/**
 * The result of an isolation-valve trace.
 *
 * <p>The operator-facing question — "which valves do I close?" — is answered by {@code valvesToClose}.
 * The supporting fields (perimeter size, already-closed valves, upstream/downstream reach) give the
 * context an operator needs to act confidently: the customer impact is bounded by the affected node
 * count, and already-closed valves confirm the existing isolation state.
 */
public record IsolationResult(
        int affectedNodes,
        List<ValveSummary> valvesToClose,
        List<ValveSummary> alreadyClosedValves,
        int downstreamNodes,
        int upstreamNodes
) {
    public record ValveSummary(
            UUID assetId,
            UUID nodeId,
            String valveType,
            String status,
            String normalState
    ) {
    }
}

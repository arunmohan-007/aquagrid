package com.aquagrid.platform.gis.application.service;

import com.aquagrid.platform.gis.domain.model.Valve;
import com.aquagrid.platform.gis.infrastructure.persistence.NetworkTraceRepository.TraceVertex;
import com.aquagrid.platform.gis.infrastructure.persistence.ValveRepository;
import com.aquagrid.platform.gis.web.dto.IsolationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Isolation-valve tracing — the control room's core question during a main break:
 * <i>"which valves must I close to isolate this section?"</i>
 *
 * <p>The algorithm reuses Module 11's network reachability, then resolves the valve perimeter:
 * <ol>
 *   <li>Walk downstream from the break point (every node reachable without crossing a CLOSED valve).</li>
 *   <li>Collect every valve whose node was reached — those are the valves on the boundary.</li>
 *   <li>The valves to operate are the ones currently OPEN among them: closing them isolates the
 *       section. Valves already CLOSED contribute nothing (the walk already stopped there).</li>
 * </ol>
 *
 * <p>This is a BFS-with-barriers expressed as a reachability query plus a valve lookup, not a custom
 * graph algorithm — which is the point of building the topology layer (Module 11) first. The
 * isolation logic is policy, the graph machinery is pgRouting.
 *
 * <p>The result also reports the <b>affected assets</b> within the isolated section (meters,
 * connections) so the operator knows the customer impact before closing anything. That join is a
 * Module 5/7 concern; here we return the node set and let those modules enrich it later.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IsolationTraceService {

    private final NetworkTraceService networkTraceService;
    private final ValveRepository valveRepository;

    @Transactional(readOnly = true)
    public IsolationResult trace(UUID sourceNodeId, double maxDistanceM) {
        // Walk the network in BOTH directions from the break — a main break affects upstream and
        // downstream sections, and isolation valves may be on either side.
        List<TraceVertex> downstream = networkTraceService.traceReachable(
                sourceNodeId, NetworkTraceService.Direction.DOWN, maxDistanceM);
        List<TraceVertex> upstream = networkTraceService.traceReachable(
                sourceNodeId, NetworkTraceService.Direction.UP, maxDistanceM);

        Set<UUID> reachedNodes = new HashSet<>();
        downstream.forEach(v -> reachedNodes.add(UUID.fromString(v.nodeId())));
        upstream.forEach(v -> reachedNodes.add(UUID.fromString(v.nodeId())));

        // Every valve whose node is in the reached set is on the isolation perimeter.
        List<Valve> perimeterValves = reachedNodes.isEmpty()
                ? List.of()
                : valveRepository.findByNodeIn(new ArrayList<>(reachedNodes));

        // The valves to OPERATE are those currently OPEN — closing them completes the isolation.
        // CLOSED valves already form part of the boundary (the walk stopped there).
        List<Valve> valvesToClose = perimeterValves.stream()
                .filter(v -> "OPEN".equals(v.getStatus()))
                .toList();
        List<Valve> alreadyClosed = perimeterValves.stream()
                .filter(v -> "CLOSED".equals(v.getStatus()))
                .toList();

        log.info("Isolation trace from {}: {} nodes reached, {} valves on perimeter ({} to close, {} already closed)",
                sourceNodeId, reachedNodes.size(), perimeterValves.size(),
                valvesToClose.size(), alreadyClosed.size());

        return new IsolationResult(
                reachedNodes.size(),
                valvesToClose.stream().map(this::toSummary).collect(Collectors.toList()),
                alreadyClosed.stream().map(this::toSummary).collect(Collectors.toList()),
                downstream.size(), upstream.size());
    }

    private IsolationResult.ValveSummary toSummary(Valve v) {
        return new IsolationResult.ValveSummary(v.getAssetId(), v.getNodeId(),
                v.getValveType(), v.getStatus(), v.getNormalState());
    }
}

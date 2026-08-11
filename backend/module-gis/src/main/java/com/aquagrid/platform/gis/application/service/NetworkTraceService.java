package com.aquagrid.platform.gis.application.service;

import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.gis.infrastructure.persistence.NetworkTraceRepository;
import com.aquagrid.platform.gis.infrastructure.persistence.NetworkTraceRepository.TraceVertex;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Pipeline network tracing, powered by pgRouting.
 *
 * <p>Two trace modes cover the operational questions:
 * <ul>
 *   <li><b>Reachability (up/down)</b> — {@code pgr_drivingDistance} from a source, bounded by a
 *       cost (distance) limit. "What is upstream of this break?" and "what is downstream of this
 *       contamination point?" are both reachability queries, distinguished only by which direction
 *       the edges are traversed.</li>
 *   <li><b>Shortest path</b> — {@code pgr_dijkstra} between two nodes. "What is the feed path from
 *       the reservoir to this DMA?"</li>
 * </ul>
 *
 * <p>Direction is encoded in the edge table's {@code reverse_cost}: a one-way pipe has
 * {@code reverse_cost = -1}, so a downstream trace cannot travel "up" a one-way main. The rebuild
 * keeps this honest.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NetworkTraceService {

    private final NetworkTraceRepository traceRepository;

    /**
     * Traces everything reachable from a source node, within a cost (distance) limit.
     *
     * @param sourceNodeId the UUID of the network node to trace from
     * @param direction    UP (against the edge direction) or DOWN (with it)
     * @param maxDistanceM the cost budget in metres; bounds the trace so it cannot walk the whole network
     */
    @Transactional(readOnly = true)
    public List<TraceVertex> traceReachable(UUID sourceNodeId, Direction direction, double maxDistanceM) {
        long pgrSource = traceRepository.resolveVertexId(sourceNodeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Network node " + sourceNodeId + " has no graph vertex (rebuild topology)."));
        return switch (direction) {
            case DOWN -> traceRepository.drivingDistanceForward(pgrSource, maxDistanceM);
            case UP -> traceRepository.drivingDistanceReverse(pgrSource, maxDistanceM);
        };
    }

    /** Shortest path between two nodes, by pipe length. */
    @Transactional(readOnly = true)
    public List<TraceVertex> shortestPath(UUID fromNodeId, UUID toNodeId) {
        long from = traceRepository.resolveVertexId(fromNodeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Source node " + fromNodeId + " has no graph vertex."));
        long to = traceRepository.resolveVertexId(toNodeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Target node " + toNodeId + " has no graph vertex."));
        return traceRepository.shortestPath(from, to);
    }

    public enum Direction { UP, DOWN }
}

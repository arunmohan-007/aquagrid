package com.aquagrid.platform.gis.application.service;

import com.aquagrid.platform.gis.domain.model.NetworkNode;
import com.aquagrid.platform.gis.infrastructure.persistence.NetworkNodeRepository;
import com.aquagrid.platform.gis.infrastructure.persistence.PipelineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds and refreshes the pipeline network topology.
 *
 * <p>The two operations every pipe write triggers:
 * <ol>
 *   <li><b>Snap</b> — given a pipe endpoint (lon, lat), find the nearest existing junction within
 *       tolerance, or create a new one. This is what makes separately-drawn pipes connect into a
 *       graph: two endpoints within tolerance snap to the same node.</li>
 *   <li><b>Rebuild</b> — regenerate the {@code gis.pipe_network} pgRouting edge table from the
 *       current pipes. Idempotent and cheap for typical network sizes (thousands of pipes); a
 *       million-pipe network would switch to incremental updates, but that is premature now.</li>
 * </ol>
 *
 * <p>Tolerance is configurable; 1 metre is the default — close enough to connect survey-grade GPS
 * endpoints, tight enough not to bridge parallel mains a few metres apart.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NetworkTopologyService {

    private static final double DEFAULT_SNAP_TOLERANCE_METRES = 1.0;
    private static final GeometryFactory FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    private final NetworkNodeRepository nodeRepository;
    private final PipelineRepository pipelineRepository;

    /**
     * Snaps to an existing junction or creates one. Returns the node id.
     *
     * <p>Creating a node when none is within tolerance is what grows the network: every distinct
     * endpoint location becomes a vertex, and pipes sharing a near-coincident endpoint share a vertex.
     */
    @Transactional
    public UUID snapOrCreate(UUID organizationId, double lon, double lat, String nodeType) {
        Optional<NetworkNode> existing = nodeRepository.findNearestWithin(organizationId, lon, lat,
                DEFAULT_SNAP_TOLERANCE_METRES);
        if (existing.isPresent()) {
            return existing.get().getId();
        }
        NetworkNode node = new NetworkNode();
        node.setOrganizationId(organizationId);
        node.setGeom(point(lon, lat));
        node.setNodeType(nodeType != null ? nodeType : "JUNCTION");
        node.setCreatedAt(Instant.now());
        nodeRepository.save(node);
        return node.getId();
    }

    /** Rebuilds the pgRouting edge table for a tenant. Called after every pipe write. */
    @Transactional
    public void rebuild(UUID organizationId) {
        pipelineRepository.rebuildEdgeTable(organizationId);
        log.debug("Rebuilt pipe_network edge table for tenant {}", organizationId);
    }

    private static Point point(double lon, double lat) {
        return FACTORY.createPoint(new Coordinate(lon, lat));
    }
}

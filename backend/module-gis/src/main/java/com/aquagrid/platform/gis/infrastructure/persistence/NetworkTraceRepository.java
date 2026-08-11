package com.aquagrid.platform.gis.infrastructure.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * pgRouting trace queries, via {@code NamedParameterJdbcTemplate}.
 *
 * <p>Native SQL because JPQL has no {@code pgr_*} functions — these are PostgreSQL extensions, not
 * JPA concerns. The results are projected into {@link TraceVertex} records (node id, distance,
 * geometry as WKT), which the controller renders.
 *
 * <p>pgRouting's {@code pgr_drivingDistance} returns every vertex within the cost budget from the
 * source. "Downstream" traverses edges forward (source→target); "upstream" traverses them reversed
 * (target→source), which is why we swap the edge's source/target in the reverse variant.
 */
@Slf4j
@Repository
public class NetworkTraceRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public NetworkTraceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Resolves a network node's pgRouting BIGINT vertex id. */
    public Optional<Long> resolveVertexId(UUID nodeId) {
        try {
            Long id = jdbc.queryForObject(
                    "SELECT pgr_vertex_id FROM gis.network_nodes WHERE id = :nodeId",
                    new MapSqlParameterSource("nodeId", nodeId),
                    Long.class);
            return Optional.ofNullable(id);
        } catch (Exception e) {
            log.warn("Could not resolve vertex id for node {}: {}", nodeId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Forward reachability: every node downstream of the source, within the cost budget.
     * Uses pgr_drivingDistance on the forward edge direction (source → target).
     */
    public List<TraceVertex> drivingDistanceForward(long source, double maxDistance) {
        String sql = """
                SELECT v.node_id::text, v.start_vid AS source_vertex, v.agg_cost AS cost,
                       ST_AsText(n.geom) AS wkt
                FROM pgr_drivingDistance(
                    'SELECT id, source, target, cost, reverse_cost FROM gis.pipe_network',
                    :source, :maxDist, false) v
                JOIN gis.network_nodes n ON n.pgr_vertex_id = v.node
                ORDER BY v.agg_cost
                """;
        return runTrace(sql, source, maxDistance);
    }

    /**
     * Reverse reachability: every node upstream of the source. Same query, but the edge table is
     * presented with source/target swapped so "driving distance" walks against the flow.
     */
    public List<TraceVertex> drivingDistanceReverse(long source, double maxDistance) {
        String sql = """
                SELECT v.node_id::text, v.start_vid AS source_vertex, v.agg_cost AS cost,
                       ST_AsText(n.geom) AS wkt
                FROM pgr_drivingDistance(
                    'SELECT id, target AS source, source AS target, reverse_cost AS cost, cost AS reverse_cost FROM gis.pipe_network',
                    :source, :maxDist, false) v
                JOIN gis.network_nodes n ON n.pgr_vertex_id = v.node
                ORDER BY v.agg_cost
                """;
        return runTrace(sql, source, maxDistance);
    }

    /** Shortest path by pipe length, via pgr_dijkstra. */
    public List<TraceVertex> shortestPath(long from, long to) {
        String sql = """
                SELECT v.node_id::text, :from AS source_vertex, v.agg_cost AS cost,
                       ST_AsText(n.geom) AS wkt
                FROM pgr_dijkstra(
                    'SELECT id, source, target, cost, reverse_cost FROM gis.pipe_network',
                    :from, :to, false) v
                JOIN gis.network_nodes n ON n.pgr_vertex_id = v.node
                WHERE v.agg_cost IS NOT NULL
                ORDER BY v.seq
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", from)
                .addValue("to", to);
        return jdbc.query(sql, params, (rs, i) -> new TraceVertex(
                rs.getString("node_id"),
                rs.getLong("source_vertex"),
                rs.getDouble("cost"),
                rs.getString("wkt")));
    }

    private List<TraceVertex> runTrace(String sql, long source, double maxDistance) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("source", source)
                .addValue("maxDist", maxDistance);
        return jdbc.query(sql, params, (rs, i) -> new TraceVertex(
                rs.getString("node_id"),
                rs.getLong("source_vertex"),
                rs.getDouble("cost"),
                rs.getString("wkt")));
    }

    /** One vertex on a trace result: the network node id, the accumulated cost, and its geometry. */
    public record TraceVertex(String nodeId, long sourceVertex, double cost, String geometryWkt) {
    }
}

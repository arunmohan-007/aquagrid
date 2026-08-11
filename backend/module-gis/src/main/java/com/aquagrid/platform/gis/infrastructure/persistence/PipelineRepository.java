package com.aquagrid.platform.gis.infrastructure.persistence;

import com.aquagrid.platform.gis.domain.model.Pipeline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PipelineRepository extends JpaRepository<Pipeline, UUID> {

    /**
     * Loads every pipe for a tenant with its nodes, for the topology rebuild. One query, not N+1.
     */
    @Query("SELECT p FROM Pipeline p WHERE p.assetId IN (" +
            "SELECT a.id FROM Asset a WHERE a.organizationId = :organizationId)")
    List<Pipeline> findAllForTenant(@Param("organizationId") UUID organizationId);

    /**
     * Rebuilds the pgRouting edge table for a tenant in one statement.
     *
     * <p>Translates each pipe into a pgr edge: {@code source}/{@code target} are the nodes' stable
     * {@code pgr_vertex_id} (BIGINT, the integer pgRouting requires), and {@code cost} is the length.
     * {@code reverse_cost} is -1 for one-way pipes, making them impassable backwards.
     */
    @Modifying
    @Query(value = """
            DELETE FROM gis.pipe_network WHERE organization_id = :organizationId;
            INSERT INTO gis.pipe_network (organization_id, pipe_id, source, target, cost, reverse_cost, geom)
            SELECT :organizationId, p.asset_id,
                   fn.pgr_vertex_id,
                   tn.pgr_vertex_id,
                   COALESCE(p.length_m, 0),
                   CASE WHEN p.flow_direction IN ('FROM_TO','TO_FROM') THEN -1
                        ELSE COALESCE(p.length_m, 0) END,
                   p.geom
            FROM gis.pipelines p
            JOIN gis.network_nodes fn ON fn.id = p.from_node_id
            JOIN gis.network_nodes tn ON tn.id = p.to_node_id
            WHERE p.from_node_id IS NOT NULL AND p.to_node_id IS NOT NULL;
            """, nativeQuery = true)
    void rebuildEdgeTable(@Param("organizationId") UUID organizationId);
}

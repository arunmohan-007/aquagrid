package com.aquagrid.platform.gis.infrastructure.persistence;

import com.aquagrid.platform.gis.domain.model.Valve;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ValveRepository extends JpaRepository<Valve, UUID> {

    /**
     * All valves whose node is in the given set — the valves reachable by an isolation trace.
     * Resolves "which valves sit on these nodes?" after the graph walk identifies the perimeter.
     */
    @Query("SELECT v FROM Valve v WHERE v.nodeId IN :nodeIds")
    List<Valve> findByNodeIn(@Param("nodeIds") List<UUID> nodeIds);

    /** Valves currently CLOSED — the existing isolation perimeter. */
    List<Valve> findByStatus(String status);
}

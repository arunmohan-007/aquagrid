package com.aquagrid.platform.gis.infrastructure.persistence;

import com.aquagrid.platform.gis.domain.model.NetworkNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface NetworkNodeRepository extends JpaRepository<NetworkNode, UUID> {

    /**
     * Snaps to the nearest existing node within tolerance (metres), or empty if none.
     *
     * <p>Uses {@code ST_DWithin} on geography for an accurate metres-based tolerance — degrees would
     * be meaningless near the equator versus the poles. The KNN GiST index on {@code geom_3857} is
     * leveraged by ordering, but the distance predicate is the filter.
     */
    @Query(value = """
            SELECT * FROM gis.network_nodes n
            WHERE n.organization_id = :organizationId
              AND ST_DWithin(n.geom, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326), :toleranceMeters)
            ORDER BY n.geom <-> ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)
            LIMIT 1
            """, nativeQuery = true)
    Optional<NetworkNode> findNearestWithin(@Param("organizationId") UUID organizationId,
                                            @Param("lon") double lon,
                                            @Param("lat") double lat,
                                            @Param("toleranceMeters") double toleranceMeters);

    /** All nodes for a tenant, for trace result rendering and validation. */
    @Query("SELECT n FROM NetworkNode n WHERE n.organizationId = :organizationId")
    java.util.List<NetworkNode> findAllForTenant(@Param("organizationId") UUID organizationId);
}

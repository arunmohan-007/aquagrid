package com.aquagrid.platform.gis.infrastructure.persistence;

import com.aquagrid.platform.gis.domain.model.LayerStyleRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface LayerStyleRuleRepository extends JpaRepository<LayerStyleRule, UUID> {

    List<LayerStyleRule> findByStyleIdOrderBySortOrderAscIdAsc(UUID styleId);

    /**
     * Active rules for several styles at once, ordered as they will be evaluated.
     *
     * <p>The map composes every layer's style in one pass; fetching rules per style inside that loop
     * is the N+1 that turns opening the console into one query per layer. The ordering is part of
     * the contract, not a convenience: MapLibre's {@code case} is first-match, so rules must arrive
     * in the order the administrator arranged them or the composed expression means something else.
     */
    @Query("""
            SELECT r FROM LayerStyleRule r
            WHERE r.styleId IN :styleIds AND r.active = true
            ORDER BY r.sortOrder ASC, r.id ASC
            """)
    List<LayerStyleRule> findActiveForStyles(@Param("styleIds") Collection<UUID> styleIds);

    void deleteByStyleId(UUID styleId);
}

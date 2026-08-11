package com.aquagrid.platform.gis.infrastructure.persistence;

import com.aquagrid.platform.gis.domain.model.MapSymbol;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MapSymbolRepository extends JpaRepository<MapSymbol, UUID> {

    List<MapSymbol> findByOrganizationIdOrderByNameAsc(UUID organizationId);

    Optional<MapSymbol> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** Case-insensitive, matching {@code uq_map_symbol_org_name}. */
    @Query("SELECT s FROM MapSymbol s WHERE s.organizationId = :organizationId "
            + "AND lower(s.name) = lower(:name)")
    Optional<MapSymbol> findByNameIgnoreCase(@Param("organizationId") UUID organizationId,
                                             @Param("name") String name);
}

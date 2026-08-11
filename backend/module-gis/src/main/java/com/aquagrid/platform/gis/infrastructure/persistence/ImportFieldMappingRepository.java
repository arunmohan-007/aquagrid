package com.aquagrid.platform.gis.infrastructure.persistence;

import com.aquagrid.platform.gis.domain.model.ImportFieldMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Saved import mapping profiles. */
@Repository
public interface ImportFieldMappingRepository extends JpaRepository<ImportFieldMapping, UUID> {

    List<ImportFieldMapping> findByOrganizationIdAndLayerIdAndProfileName(
            UUID organizationId, UUID layerId, String profileName);

    /** The profile names available for a layer, for the "reuse a saved mapping" dropdown. */
    @Query("""
            SELECT DISTINCT m.profileName FROM ImportFieldMapping m
            WHERE m.organizationId = :organizationId AND m.layerId = :layerId
            ORDER BY 1
            """)
    List<String> findProfileNames(@Param("organizationId") UUID organizationId,
                                  @Param("layerId") UUID layerId);

    /**
     * Clears a profile before it is rewritten.
     *
     * <p>Saving a profile replaces it wholesale rather than merging: a column dropped from this
     * quarter's deliverable must disappear from the profile, and a merge would keep mapping a
     * column that is no longer in the file — which looks like a working mapping until someone
     * checks why a field is always empty.
     */
    void deleteByOrganizationIdAndLayerIdAndProfileName(UUID organizationId, UUID layerId,
                                                        String profileName);
}

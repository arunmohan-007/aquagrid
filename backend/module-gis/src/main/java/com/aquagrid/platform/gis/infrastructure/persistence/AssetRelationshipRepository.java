package com.aquagrid.platform.gis.infrastructure.persistence;

import com.aquagrid.platform.gis.domain.model.AssetRelationship;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AssetRelationshipRepository extends JpaRepository<AssetRelationship, AssetRelationship.Pk> {

    /** Everything that is a child of the given asset (the asset CONTAINS/FED_BY them). */
    @Query("""
            SELECT r FROM AssetRelationship r
            WHERE r.parentId = :assetId
            ORDER BY r.relationshipType, r.childId
            """)
    List<AssetRelationship> findChildren(@Param("assetId") UUID assetId);

    /** Everything that is a parent of the given asset (the asset is CONTAINED-IN/FED-BY them). */
    @Query("""
            SELECT r FROM AssetRelationship r
            WHERE r.childId = :assetId
            ORDER BY r.relationshipType, r.parentId
            """)
    List<AssetRelationship> findParents(@Param("assetId") UUID assetId);
}

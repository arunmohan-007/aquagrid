package com.aquagrid.platform.gis.infrastructure.persistence;

import com.aquagrid.platform.gis.domain.model.AttributeValidationRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Per-attribute validation rules. */
@Repository
public interface AttributeValidationRuleRepository extends JpaRepository<AttributeValidationRule, UUID> {

    List<AttributeValidationRule> findByAttributeIdAndActiveTrueOrderBySortOrderAsc(UUID attributeId);

    /**
     * Every active rule for a set of attributes, in one round trip.
     *
     * <p>An import evaluates rules per value per row. Fetching them per attribute inside that loop
     * is the N+1 that turns a 50k-row file into 50k × columns queries; the importer loads the whole
     * rule set once, up front, alongside the attribute definitions.
     */
    @Query("""
            SELECT r FROM AttributeValidationRule r
            WHERE r.organizationId = :organizationId
              AND r.attributeId IN :attributeIds
              AND r.active = true
            ORDER BY r.sortOrder ASC
            """)
    List<AttributeValidationRule> findActiveForAttributes(@Param("organizationId") UUID organizationId,
                                                          @Param("attributeIds") Collection<UUID> attributeIds);

    List<AttributeValidationRule> findByOrganizationIdAndAttributeIdOrderBySortOrderAsc(
            UUID organizationId, UUID attributeId);
}

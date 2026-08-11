package com.aquagrid.platform.gis.infrastructure.persistence;

import com.aquagrid.platform.gis.domain.model.LayerAttributeHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Append-only attribute definition history. No update or delete path exists by design. */
@Repository
public interface LayerAttributeHistoryRepository extends JpaRepository<LayerAttributeHistory, Long> {

    Page<LayerAttributeHistory> findByOrganizationIdAndAttributeIdOrderByChangedAtDesc(
            UUID organizationId, UUID attributeId, Pageable pageable);
}

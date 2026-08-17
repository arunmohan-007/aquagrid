package com.aquagrid.platform.gis.infrastructure.persistence;

import com.aquagrid.platform.gis.domain.model.ImportRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Persisted bulk import run history, one row per run. */
@Repository
public interface ImportRunRepository extends JpaRepository<ImportRun, UUID> {

    Page<ImportRun> findByOrganizationIdOrderByStartedAtDesc(UUID organizationId, Pageable pageable);

    Optional<ImportRun> findByIdAndOrganizationId(UUID id, UUID organizationId);
}

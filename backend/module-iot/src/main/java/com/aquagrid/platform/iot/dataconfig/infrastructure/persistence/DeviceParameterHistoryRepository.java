package com.aquagrid.platform.iot.dataconfig.infrastructure.persistence;

import com.aquagrid.platform.iot.dataconfig.domain.model.DeviceParameterHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Append-only definition history. Nothing here updates or deletes. */
@Repository
public interface DeviceParameterHistoryRepository extends JpaRepository<DeviceParameterHistory, Long> {

    Page<DeviceParameterHistory> findByOrganizationIdAndParameterIdOrderByChangedAtDesc(
            UUID organizationId, UUID parameterId, Pageable pageable);
}

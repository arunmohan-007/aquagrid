package com.aquagrid.platform.gis.infrastructure.persistence;

import com.aquagrid.platform.gis.domain.model.ValveOperation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ValveOperationRepository extends JpaRepository<ValveOperation, Long> {

    List<ValveOperation> findByValveAssetIdOrderByOperatedAtDesc(UUID valveAssetId);
}

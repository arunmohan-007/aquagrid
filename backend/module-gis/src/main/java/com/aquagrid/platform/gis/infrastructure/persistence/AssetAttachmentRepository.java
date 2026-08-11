package com.aquagrid.platform.gis.infrastructure.persistence;

import com.aquagrid.platform.gis.domain.model.AssetAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AssetAttachmentRepository extends JpaRepository<AssetAttachment, UUID> {

    List<AssetAttachment> findByAssetIdOrderByUploadedAtDesc(UUID assetId);
}

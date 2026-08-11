package com.aquagrid.platform.gis.application.service;

import com.aquagrid.platform.common.audit.AuditCategory;
import com.aquagrid.platform.common.audit.AuditEvent;
import com.aquagrid.platform.common.audit.AuditService;
import com.aquagrid.platform.common.error.ResourceNotFoundException;
import com.aquagrid.platform.gis.domain.model.Asset;
import com.aquagrid.platform.gis.domain.model.AssetAttachment;
import com.aquagrid.platform.gis.infrastructure.persistence.AssetAttachmentRepository;
import com.aquagrid.platform.gis.storage.ObjectStoragePort;
import com.aquagrid.platform.gis.storage.ObjectStoragePort.StoredObject;
import com.aquagrid.platform.gis.web.dto.AssetDto.AttachmentSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Asset attachment management.
 *
 * <p>Stores bytes in object storage and metadata in Postgres — the split that keeps the database
 * small and the storage tier independently scalable. The storage key is generated from the asset id
 * and a UUID so it is opaque, stable, and never collides.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentService {

    private final AssetAttachmentRepository attachmentRepository;
    private final AssetService assetService;
    private final ObjectStoragePort storage;
    private final AuditService auditService;

    @Transactional
    public AttachmentSummary upload(UUID assetId, UUID organizationId, UUID actorId,
                                    String fileName, String contentType, long sizeBytes,
                                    InputStream content) {
        Asset asset = assetService.requireInTenant(assetId, organizationId);
        String storageKey = assetId + "/" + UUID.randomUUID() + "/" + fileName;

        storage.put(storageKey, contentType, content);

        AssetAttachment attachment = new AssetAttachment();
        attachment.setAssetId(assetId);
        attachment.setFileName(fileName);
        attachment.setContentType(contentType);
        attachment.setSizeBytes(sizeBytes);
        attachment.setStorageKey(storageKey);
        attachment.setUploadedBy(actorId);
        attachment.setUploadedAt(Instant.now());
        attachmentRepository.save(attachment);

        auditService.record(AuditEvent.builder()
                .organizationId(organizationId)
                .actorUserId(actorId)
                .eventType("ASSET_ATTACHMENT_UPLOADED")
                .category(AuditCategory.DATA)
                .resourceType("Asset")
                .resourceId(assetId.toString())
                .success(true)
                .message("Attachment '%s' uploaded to asset %s".formatted(fileName, asset.getAssetCode()))
                .build());
        return AttachmentSummary.from(attachment);
    }

    @Transactional(readOnly = true)
    public List<AttachmentSummary> list(UUID assetId, UUID organizationId) {
        assetService.requireInTenant(assetId, organizationId);
        return attachmentRepository.findByAssetIdOrderByUploadedAtDesc(assetId).stream()
                .map(AttachmentSummary::from)
                .toList();
    }

    /**
     * Fetches the stored object for download. Caller is responsible for streaming and closing.
     */
    @Transactional(readOnly = true)
    public StoredObject download(UUID attachmentId, UUID organizationId) {
        AssetAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Attachment", attachmentId));
        assetService.requireInTenant(attachment.getAssetId(), organizationId);
        return storage.get(attachment.getStorageKey());
    }

    @Transactional
    public void delete(UUID attachmentId, UUID organizationId, UUID actorId) {
        AssetAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Attachment", attachmentId));
        assetService.requireInTenant(attachment.getAssetId(), organizationId);
        storage.delete(attachment.getStorageKey());
        attachmentRepository.delete(attachment);
        log.info("Deleted attachment {} from asset {}", attachmentId, attachment.getAssetId());
    }
}

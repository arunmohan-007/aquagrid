package com.aquagrid.platform.gis.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadata for one file attached to an asset.
 *
 * <p>The bytes live in object storage under {@code storageKey}; this row is the index the UI and
 * queries use. Keeping the bytes out of Postgres keeps the database small, backups fast, and lets
 * the storage tier scale independently of the transactional tier.
 */
@Getter
@Setter
@Entity
@Table(name = "asset_attachments", schema = "gis")
public class AssetAttachment {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "asset_id", nullable = false, updatable = false)
    private UUID assetId;

    @Column(name = "file_name", nullable = false, length = 300)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 120)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** Opaque key object storage uses to retrieve the bytes. Stable; never a URL. */
    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "uploaded_by", updatable = false)
    private UUID uploadedBy;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;
}

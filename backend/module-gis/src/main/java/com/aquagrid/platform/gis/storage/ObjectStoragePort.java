package com.aquagrid.platform.gis.storage;

import java.io.InputStream;

/**
 * Object storage SPI.
 *
 * <p>Asset attachments (photos, as-builts, manuals) are stored here, not in Postgres. The bytes are
 * large, write-once and served directly to browsers — the workload object storage is built for, and
 * the wrong one to put through a transactional database.
 *
 * <p>The port abstracts the backend so a single-node customer install can use the filesystem (or
 * MinIO in the docker-compose) and a cloud SKU can use S3, with zero changes to the asset service.
 * Mirrors the {@code InboundTransportAdapter} pattern: pluggable detail behind a narrow interface.
 */
public interface ObjectStoragePort {

    /**
     * Stores a blob and returns the opaque storage key to persist.
     *
     * @param key          the key to store under (caller-chosen, e.g. asset-id/uuid-filename)
     * @param contentType  MIME type, surfaced on download
     * @param content      the bytes
     */
    void put(String key, String contentType, InputStream content);

    /**
     * Retrieves a blob. Returns the content and its content type.
     *
     * @throws com.aquagrid.platform.common.error.ResourceNotFoundException if the key is absent
     */
    StoredObject get(String key);

    /** Deletes a blob. Idempotent: deleting an absent key succeeds. */
    void delete(String key);

    record StoredObject(String contentType, InputStream content) {
    }
}

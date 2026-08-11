package com.aquagrid.platform.gis.storage;

import com.aquagrid.platform.common.error.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Filesystem-backed object storage.
 *
 * <p>The default when no object-storage backend (MinIO/S3) is configured. Writes blobs to a local
 * directory, keyed by the storage key. Adequate for single-node installs and development; a
 * multi-node deployment must use shared storage (MinIO/S3), activated by registering a bean that
 * shadows this one.
 *
 * <p>Registered as the fallback by {@code GisModuleConfig#filesystemObjectStorage}, which carries
 * the {@code @ConditionalOnMissingBean} — that condition is only honoured on {@code @Bean} methods,
 * never on a component-scanned class, so this type is deliberately not a {@code @Component}. If a
 * real object-storage bean appears (e.g. a {@code MinioObjectStorage} registered when
 * {@code aquagrid.storage.type=minio}), this implementation steps aside automatically.
 */
@Slf4j
public class FilesystemObjectStorage implements ObjectStoragePort {

    private final Path root;

    public FilesystemObjectStorage(String root) {
        this.root = Paths.get(root);
        try {
            Files.createDirectories(this.root);
            log.info("Filesystem object storage at {}", this.root.toAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("Could not initialise attachment storage at " + root, e);
        }
    }

    @Override
    public void put(String key, String contentType, InputStream content) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store attachment " + key, e);
        }
    }

    @Override
    public StoredObject get(String key) {
        Path source = resolve(key);
        if (!Files.isRegularFile(source)) {
            throw new ResourceNotFoundException("Attachment", key);
        }
        try {
            String contentType = Files.probeContentType(source);
            return new StoredObject(contentType != null ? contentType : "application/octet-stream",
                    Files.newInputStream(source));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read attachment " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        Path target = resolve(key);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("Could not delete attachment {}: {}", key, e.getMessage());
        }
    }

    private Path resolve(String key) {
        // Defence against path traversal: the key is a generated opaque id, but reject anything
        // that attempts to escape the root. A crafted key reaching ../etc would be a defect.
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Invalid storage key: " + key);
        }
        return resolved;
    }
}

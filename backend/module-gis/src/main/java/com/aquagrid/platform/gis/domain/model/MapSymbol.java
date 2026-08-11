package com.aquagrid.platform.gis.domain.model;

import com.aquagrid.platform.common.domain.AuditableEntity;
import com.aquagrid.platform.gis.domain.enums.SymbolFormat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * One symbol in a tenant's uploaded icon library.
 *
 * <p>Metadata only: the bytes live in object storage under {@link #storageKey}, the same split
 * {@link AssetAttachment} uses and for the same reason — image bytes are write-once, served straight
 * to browsers, and have no business in a transactional database.
 *
 * <p>The map references a symbol by the id MapLibre registers it under, which is
 * {@code ag-sym-<id>}. That prefix is shared with the seven built-in shapes ({@code ag-circle} and
 * friends), so the composer needs no branch: it emits {@code "ag-" + icon} whether the icon is a
 * built-in name or {@code sym-<id>}, and the client registers both kinds under the same convention.
 */
@Getter
@Setter
@Entity
@Table(name = "map_symbol", schema = "gis")
public class MapSymbol extends AuditableEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false, length = 10)
    private SymbolFormat format;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** Opaque key into {@code ObjectStoragePort}. Never built from user input. */
    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    /**
     * Whether the image is a tintable silhouette.
     *
     * <p>Decides how the client registers it: {@code sdf: true} makes MapLibre paint it in the
     * style's colour — including one computed by an attribute-based rule — while {@code false} draws
     * it exactly as uploaded and makes a classified style unable to recolour it. This is the one
     * property of an uploaded symbol that changes what styling can do with it.
     */
    @Column(name = "is_sdf", nullable = false)
    private boolean sdf = true;

    @Column(name = "width_px")
    private Integer widthPx;

    @Column(name = "height_px")
    private Integer heightPx;

    /** The name the style stores and the composer prefixes: {@code sym-<id>}. */
    public String iconName() {
        return "sym-" + getId();
    }
}

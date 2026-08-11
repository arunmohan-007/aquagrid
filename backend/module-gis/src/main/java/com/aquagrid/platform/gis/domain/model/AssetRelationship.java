package com.aquagrid.platform.gis.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A typed, directed edge in the asset relationship graph.
 *
 * <p>One edge table expresses every relationship kind: a connection CONTAINS a meter, a pipe is
 * FED_BY a reservoir, a valve is CONNECTED_TO a pipe. This keeps "show me everything related to
 * asset X" a single indexed join rather than a union across per-kind tables.
 */
@Getter
@Setter
@Entity
@Table(name = "asset_relationships", schema = "gis")
@IdClass(AssetRelationship.Pk.class)
public class AssetRelationship {

    @Id
    @Column(name = "parent_id", nullable = false, updatable = false)
    private UUID parentId;

    @Id
    @Column(name = "child_id", nullable = false, updatable = false)
    private UUID childId;

    @Id
    @Column(name = "relationship_type", nullable = false, updatable = false)
    private String relationshipType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Composite key — the (parent, child, type) triple is the identity. */
    public record Pk(UUID parentId, UUID childId, String relationshipType) implements Serializable {
        public Pk {
            Objects.requireNonNull(parentId);
            Objects.requireNonNull(childId);
            Objects.requireNonNull(relationshipType);
        }
    }
}

-- =====================================================================================
-- Module 23 — Asset attachments and relationships
-- Owner : module-gis (version range V1300–V1399)
--
-- `gis.assets` (the supertype) was created in V1300. This migration adds the two things the
-- CRUD module needs that the supertype does not carry: file attachments (metadata only — the
-- bytes live in object storage) and a parent/child relationship graph.
--
-- Design notes:
--  * Attachment bytes never enter Postgres. A multi-MB photo per asset multiplied across a
--    utility's thousands of assets would bloat the DB, slow backups, and gain nothing — the
--    metadata is what queries need, the bytes are what object storage serves.
--  * Relationships are a self-referential graph on gis.assets, typed so the same edge table
--    expresses "meter belongs to connection" (CONTAINS), "connection fed by pipe"
--    (FED_BY), "valve connected to pipe" (CONNECTED_TO). One table, one join.
-- =====================================================================================

CREATE TABLE gis.asset_attachments
(
    id            UUID PRIMARY KEY                  DEFAULT gen_random_uuid(),
    asset_id      UUID         NOT NULL,
    file_name     VARCHAR(300) NOT NULL,
    content_type  VARCHAR(120) NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    -- The opaque key object storage uses to retrieve the bytes (e.g. MinIO/S3 object key).
    -- Never a URL: URLs expire, keys are stable and storage-resolved at read time.
    storage_key   VARCHAR(500) NOT NULL,
    uploaded_by   UUID,
    uploaded_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_attachments_asset
        FOREIGN KEY (asset_id) REFERENCES gis.assets (id) ON DELETE CASCADE
);

CREATE INDEX ix_attachments_asset ON gis.asset_attachments (asset_id, uploaded_at DESC);

COMMENT ON TABLE gis.asset_attachments IS
    'Asset file metadata. Bytes live in object storage (MinIO/S3); only metadata is stored here.';

-- ---- Asset relationship graph -----------------------------------------------------------
-- A typed, directed edge: parent --[type]--> child. One row per relationship.
-- e.g. (connection) -[CONTAINS]-> (meter), (pipe) -[FED_BY]-> (reservoir).
CREATE TABLE gis.asset_relationships
(
    parent_id          UUID        NOT NULL,
    child_id           UUID        NOT NULL,
    relationship_type  VARCHAR(30) NOT NULL,

    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_asset_relationships PRIMARY KEY (parent_id, child_id, relationship_type),
    CONSTRAINT fk_relationships_parent
        FOREIGN KEY (parent_id) REFERENCES gis.assets (id) ON DELETE CASCADE,
    CONSTRAINT fk_relationships_child
        FOREIGN KEY (child_id) REFERENCES gis.assets (id) ON DELETE RESTRICT,
    CONSTRAINT ck_relationship_type CHECK (relationship_type IN
        ('CONTAINS', 'CONNECTED_TO', 'FED_BY', 'PART_OF', 'MEASURED_BY')),
    CONSTRAINT ck_relationship_not_self CHECK (parent_id <> child_id)
);

CREATE INDEX ix_relationships_child ON gis.asset_relationships (child_id);

COMMENT ON TABLE gis.asset_relationships IS
    'Typed parent/child graph between assets. One edge table for all relationship kinds.';

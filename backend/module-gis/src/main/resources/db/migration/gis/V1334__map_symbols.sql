-- =====================================================================================
-- Uploaded map symbols — a tenant's own icon library
-- Owner : module-gis (version range V1300–V1399)
--
-- Layer Style Management ships seven marker shapes drawn by the client at runtime. They cover the
-- common cases and none of the specific ones: a utility that has used the same valve glyph on its
-- drawings for twenty years wants that glyph, and "circle, square or diamond" is not an answer.
--
-- Design decisions:
--
--  * **Bytes in object storage, metadata here.** The same split AssetAttachment uses, for the same
--    reasons — the bytes are write-once, served directly to browsers, and have no business in a
--    transactional database. `storage_key` is the opaque key; ObjectStoragePort owns where it lands,
--    so a single-node install writes to disk and a cloud SKU writes to S3 with no change here.
--
--  * **`is_sdf` is the difference between a tintable silhouette and a picture.** MapLibre draws a
--    normal image in its own colours and an SDF image in `icon-color`. That is not a rendering
--    detail an administrator can be expected to know, so it is asked as a question about their file
--    — "one colour, takes the layer's colour" versus "full colour, drawn as-is" — and stored as the
--    flag the renderer actually needs. It also decides whether an attribute-based style can colour
--    the symbol at all: a non-SDF icon ignores a classified expression entirely.
--
--  * **Per tenant.** A symbol library is a utility's own cartographic vocabulary. There is no shared
--    system library, because a symbol that appeared in every tenant's picker would be one nobody
--    could remove.
--
--  * **No delete of a symbol in use.** Enforced in the service rather than by a constraint: a style
--    references a symbol by name inside a JSONB document, which no foreign key can see. The service
--    checks and refuses with the list of styles using it, which is more useful than a constraint
--    violation would have been anyway.
-- =====================================================================================

CREATE TABLE gis.map_symbol
(
    id              UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL,

    name            VARCHAR(120) NOT NULL,
    description     VARCHAR(500),

    /*
     * SVG or a raster format. SVG is preferred and is what the upload form nudges towards: a symbol
     * is drawn at every zoom and at several sizes, and a raster one is either soft when scaled up or
     * wasteful when scaled down.
     */
    format          VARCHAR(10)  NOT NULL,
    content_type    VARCHAR(100) NOT NULL,
    size_bytes      BIGINT       NOT NULL,
    storage_key     VARCHAR(500) NOT NULL,

    /*
     * Whether the image is a tintable silhouette. True means the client registers it with
     * `sdf: true` and MapLibre paints it in the style's colour — including a colour computed by an
     * attribute-based rule. False means it is drawn exactly as uploaded and a classified style
     * cannot recolour it.
     */
    is_sdf          BOOLEAN      NOT NULL DEFAULT TRUE,

    /*
     * The size the symbol was authored at, used to rasterise it faithfully. Nullable because an SVG
     * need not declare one — a viewBox with no width/height is legal and common — in which case the
     * client rasterises at its own default and `icon-size` does the rest.
     */
    width_px        INTEGER,
    height_px       INTEGER,

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by      UUID,
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_map_symbol_organization
        FOREIGN KEY (organization_id) REFERENCES core.organizations (id) ON DELETE CASCADE,
    CONSTRAINT ck_map_symbol_format CHECK (format IN ('SVG', 'PNG')),
    -- A symbol is loaded into the map's image atlas on every style load. Something enormous is a
    -- mistake worth refusing at the last line of defence as well as at the upload.
    CONSTRAINT ck_map_symbol_size CHECK (size_bytes > 0 AND size_bytes <= 1048576),
    CONSTRAINT ck_map_symbol_dimensions CHECK (
        (width_px IS NULL OR width_px BETWEEN 1 AND 4096)
            AND (height_px IS NULL OR height_px BETWEEN 1 AND 4096))
);

/*
 * Names are unique per tenant, case-insensitively. The name is what an administrator picks from a
 * dropdown; two symbols called "Gate valve" and "gate valve" is a library nobody can use.
 */
CREATE UNIQUE INDEX uq_map_symbol_org_name ON gis.map_symbol (organization_id, lower(name));
CREATE INDEX ix_map_symbol_org ON gis.map_symbol (organization_id, created_at DESC);

CREATE TRIGGER trg_map_symbol_updated_at
    BEFORE UPDATE ON gis.map_symbol
    FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

COMMENT ON TABLE gis.map_symbol IS
    'A tenant''s uploaded icon library. Bytes live in object storage; this is the metadata and the storage key.';
COMMENT ON COLUMN gis.map_symbol.is_sdf IS
    'True for a single-colour silhouette the map tints with the style colour (including a classified one). False for a full-colour image drawn as uploaded.';

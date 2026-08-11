-- =====================================================================================
-- Layer Management — gis.layers becomes the layer registry
-- Owner : module-gis (version range V1300–V1399)
--
-- V1330 established that `gis.layers` IS the layer master and that a parallel `layer_master`
-- table would be a second list of the same layers, drifting from the one the map, the tile
-- endpoint and the import hub already read. That decision stands, so this migration adds the
-- registry columns to `gis.layers` rather than introducing a table beside it.
--
-- Three things are load-bearing here.
--
--  * **A layer is not an asset type.** Everything so far has treated the two as interchangeable,
--    and LayerRepository's own javadoc already flagged the day that breaks: "a utility splits its
--    meters into domestic and bulk layers". `gis.assets.layer_id` makes the relationship explicit,
--    so a layer's features are the rows that say they belong to it rather than every row that
--    happens to share its type. Tile, extent and count queries become exact, and two layers over
--    one asset type stop drawing each other's features.
--
--  * **Still no runtime DDL.** A new layer is an INSERT here, backed by the `gis.assets` supertype
--    that already provides geometry(4326), the generated Web-Mercator column, both GiST indexes
--    and the GIN-indexed attribute bag. Creating a physical table per layer would mean the web
--    tier holding DDL rights on its own schema — the privilege V1330 declined for attributes, and
--    it is no more appropriate for layers. `feature_table` and `geometry_column` are recorded
--    anyway, so the registry describes where a layer's geometry lives instead of assuming it, and
--    a future externally-managed table is describable without a schema change.
--
--  * **`geometry_type` and `srid` are declarations, not DDL.** They are what the write path checks
--    an incoming geometry against and what the preview, the import wizard and the style editor
--    read to know what a layer draws. Enforcement lives in Java (GeometryType.accepts) because the
--    column they are checked against is a bare `geometry` shared by every layer.
--
-- Nothing existing is recreated, no geometry is touched, and every new column has a default that
-- reproduces today's behaviour.
-- =====================================================================================

-- ---- Registry columns on the layer master ----------------------------------------------
-- Naming note: `code` is the layer name (the stable machine identifier the tile endpoint and the
-- MapLibre source id use), `title` is the display name and `is_visible` is visible-by-default.
-- Those three predate this module and are not renamed — a rename would rewrite the tile URLs the
-- client has already cached to say the same thing in different words.

ALTER TABLE gis.layers
    ADD COLUMN IF NOT EXISTS category            VARCHAR(60),
    ADD COLUMN IF NOT EXISTS geometry_type       VARCHAR(20)  NOT NULL DEFAULT 'GEOMETRY',
    ADD COLUMN IF NOT EXISTS crs_authority       VARCHAR(20)  NOT NULL DEFAULT 'EPSG',
    ADD COLUMN IF NOT EXISTS srid                INTEGER      NOT NULL DEFAULT 4326,
    ADD COLUMN IF NOT EXISTS feature_table       VARCHAR(120) NOT NULL DEFAULT 'gis.assets',
    ADD COLUMN IF NOT EXISTS geometry_column     VARCHAR(63)  NOT NULL DEFAULT 'geom',
    ADD COLUMN IF NOT EXISTS status              VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS is_editable         BOOLEAN      NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS is_queryable        BOOLEAN      NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS is_searchable       BOOLEAN      NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS import_enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS export_enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS vector_tile_enabled BOOLEAN      NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS min_zoom            SMALLINT     NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS max_zoom            SMALLINT     NOT NULL DEFAULT 24,
    /*
     * A system layer is one the platform's own code names: the dashboard sums PIPELINE length,
     * the network trace walks PIPELINE and VALVE, the importer's catalogue targets these types.
     * Its labels, visibility, styling and zoom range belong to the tenant; its code and asset type
     * do not, and it cannot be archived out from under the code that reads it. Layers an
     * administrator creates have this false and are theirs entirely.
     */
    ADD COLUMN IF NOT EXISTS is_system           BOOLEAN      NOT NULL DEFAULT FALSE;

ALTER TABLE gis.layers DROP CONSTRAINT IF EXISTS ck_layers_status;
ALTER TABLE gis.layers
    ADD CONSTRAINT ck_layers_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED'));

ALTER TABLE gis.layers DROP CONSTRAINT IF EXISTS ck_layers_geometry_type;
ALTER TABLE gis.layers
    ADD CONSTRAINT ck_layers_geometry_type CHECK (geometry_type IN
        ('POINT', 'MULTIPOINT', 'LINESTRING', 'MULTILINESTRING',
         'POLYGON', 'MULTIPOLYGON', 'GEOMETRY', 'GEOMETRYCOLLECTION'));

/*
 * The SRID must be one PostGIS actually knows, which is a row in public.spatial_ref_sys — but the
 * check is a service-layer lookup, not a foreign key. `spatial_ref_sys` is owned by whoever ran
 * CREATE EXTENSION postgis, and declaring a reference to it needs the REFERENCES privilege on that
 * table; the application's migration role has SELECT on it everywhere and ownership on it nowhere,
 * so an FK here would apply cleanly in development and fail the deployment that matters. The CHECK
 * catches the nonsense values, LayerManagementService catches the plausible-but-unknown ones, and
 * the CRS list offered to the client is a query against spatial_ref_sys rather than a hard-coded
 * pair of options — the database already holds every projection it can honour, including the local
 * grids a state utility adds itself.
 */
ALTER TABLE gis.layers DROP CONSTRAINT IF EXISTS ck_layers_srid;
ALTER TABLE gis.layers
    ADD CONSTRAINT ck_layers_srid CHECK (srid BETWEEN 1 AND 999999);

-- Zoom range is a MapLibre concept and MapLibre's ceiling is 24. A max below the min is a layer
-- that can never draw, which is a configuration mistake worth refusing rather than rendering.
ALTER TABLE gis.layers DROP CONSTRAINT IF EXISTS ck_layers_zoom;
ALTER TABLE gis.layers
    ADD CONSTRAINT ck_layers_zoom CHECK (
        min_zoom BETWEEN 0 AND 24 AND max_zoom BETWEEN 0 AND 24 AND min_zoom <= max_zoom);

/*
 * Layer codes become MapLibre source ids and a path segment of the tile URL, so the grammar is
 * narrower than the column: lower-case, digits and hyphens. Enforced here as well as in Java for
 * the reason V1330 gives about field names — the service produces the good error message, this
 * stops a bad row arriving by any other route.
 */
ALTER TABLE gis.layers DROP CONSTRAINT IF EXISTS ck_layers_code;
ALTER TABLE gis.layers
    ADD CONSTRAINT ck_layers_code CHECK (code ~ '^[a-z][a-z0-9-]*$');

CREATE INDEX IF NOT EXISTS ix_layers_org_status ON gis.layers (organization_id, status);

COMMENT ON TABLE gis.layers IS
    'The layer registry. Names the layers the map renders, the layers the attribute catalogue hangs off, and the layers Layer Style Management styles.';
COMMENT ON COLUMN gis.layers.feature_table IS
    'Where this layer''s geometry lives. Always gis.assets today; recorded rather than assumed so an externally-managed table is describable without a schema change.';
COMMENT ON COLUMN gis.layers.geometry_type IS
    'Declared geometry, checked against incoming features by GeometryType.accepts. Not DDL: the underlying column is a bare geometry shared by every layer.';
COMMENT ON COLUMN gis.layers.is_system IS
    'True for layers the platform''s own code names. Labels, visibility, styling and zoom are the tenant''s; the code and asset type are not, and it cannot be archived.';

-- ---- A custom layer's asset type -------------------------------------------------------
-- Layers an administrator invents have no place in the AssetType vocabulary: that enum is the
-- physical discriminator the typed detail tables, the network trace and the dashboard dispatch on,
-- and growing it per layer would mean a release per layer — exactly what this module exists to
-- avoid. CUSTOM is added once, is inert in every one of those code paths, and layer_id below is
-- what actually separates one custom layer's features from another's.
ALTER TABLE gis.assets DROP CONSTRAINT IF EXISTS ck_assets_type;
ALTER TABLE gis.assets
    ADD CONSTRAINT ck_assets_type CHECK (asset_type IN
        ('METER', 'VALVE', 'PIPELINE', 'HYDRANT', 'TANK', 'RESERVOIR',
         'PUMP_STATION', 'OPEN_WELL', 'BORE_WELL', 'DMA', 'PANCHAYAT',
         'SERVICE_CONNECTION', 'SENSOR', 'CUSTOM'));

ALTER TABLE gis.layers DROP CONSTRAINT IF EXISTS ck_layers_asset_type;
ALTER TABLE gis.layers
    ADD CONSTRAINT ck_layers_asset_type CHECK (asset_type IN
        ('METER', 'VALVE', 'PIPELINE', 'HYDRANT', 'TANK', 'RESERVOIR',
         'PUMP_STATION', 'OPEN_WELL', 'BORE_WELL', 'DMA', 'PANCHAYAT',
         'SERVICE_CONNECTION', 'SENSOR', 'CUSTOM'));

-- ---- Which layer a feature belongs to ---------------------------------------------------
/*
 * Nullable, and it stays nullable.
 *
 * The backfill below covers every asset of every tenant that has layer rows, which after V1300,
 * V1323 and V1324 is every tenant present when those migrations ran. A tenant provisioned since
 * has no layer rows at all — a gap V1331 already records and which is fixed for the catalogue and
 * for this column at the same time, whenever it is fixed. Making the column NOT NULL would turn
 * that latent gap into a hard failure on the next asset insert, which is a worse answer than a
 * null the readers already handle.
 *
 * Readers therefore resolve a layer's features as "rows claimed by this layer, plus unclaimed rows
 * of its asset type". That is exactly today's behaviour for unclaimed rows and exact for claimed
 * ones, so nothing that works now stops working and everything written from here on is precise.
 */
ALTER TABLE gis.assets ADD COLUMN IF NOT EXISTS layer_id UUID;

ALTER TABLE gis.assets DROP CONSTRAINT IF EXISTS fk_assets_layer;
ALTER TABLE gis.assets
    ADD CONSTRAINT fk_assets_layer FOREIGN KEY (layer_id)
        REFERENCES gis.layers (id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS ix_assets_org_layer ON gis.assets (organization_id, layer_id);

COMMENT ON COLUMN gis.assets.layer_id IS
    'The registry layer this feature belongs to. Null for rows written before Layer Management, or for a tenant with no layer rows; readers fall back to asset_type for those.';

/*
 * Backfill: the primary layer for each asset type, which is the lowest sort_order — the same rule
 * LayerMetadataApi.layerIdForAssetType already applies. A tenant that has since split a type across
 * two layers gets everything on the first; re-assigning is an editing decision no migration should
 * make on the operator's behalf.
 */
UPDATE gis.assets a
SET layer_id = l.id
FROM (
    SELECT DISTINCT ON (organization_id, asset_type) organization_id, asset_type, id
    FROM gis.layers
    ORDER BY organization_id, asset_type, sort_order, code
) AS l
WHERE a.layer_id IS NULL
  AND a.organization_id = l.organization_id
  AND a.asset_type = l.asset_type;

-- ---- Describe the layers that already exist ---------------------------------------------
/*
 * Registration of the existing estate, which is the whole of §1 of the brief: every spatial layer
 * already in the database is described here rather than recreated. No table is created, no row of
 * geometry is touched, and the layers keep their ids — so every saved import mapping, every
 * attribute in gis.layer_attribute_master and every tile URL already in a browser cache keeps
 * working.
 *
 * Geometry types are seeded from what the import hub actually accepts for each type. The facility
 * layers are GEOMETRY rather than POINT because the hub deliberately offers both a footprint and a
 * location variant for tanks, reservoirs, pump stations and open wells — a project holds whichever
 * way it was surveyed, and declaring POINT would reject half of them. Bore wells are the exception
 * the hub itself makes: a borehole is a shaft, so there is no outline to digitise.
 */
UPDATE gis.layers l SET
    category      = s.category,
    geometry_type = s.geometry_type,
    is_system     = TRUE,
    min_zoom      = s.min_zoom
FROM (VALUES
    ('PIPELINE',           'Pipe Network',  'MULTILINESTRING', 0::smallint),
    ('METER',              'Point Assets',  'POINT',           0::smallint),
    ('VALVE',              'Point Assets',  'POINT',           0::smallint),
    ('HYDRANT',            'Point Assets',  'POINT',           0::smallint),
    ('SENSOR',             'Point Assets',  'POINT',           0::smallint),
    -- Service connections are the densest layer a utility owns — one per household. Drawing them
    -- at district zoom is a million circles nobody can read, so the registry says so once here
    -- rather than every client deciding for itself.
    ('SERVICE_CONNECTION', 'Point Assets',  'POINT',           14::smallint),
    ('TANK',               'Facilities',    'GEOMETRY',        0::smallint),
    ('RESERVOIR',          'Facilities',    'GEOMETRY',        0::smallint),
    ('PUMP_STATION',       'Facilities',    'GEOMETRY',        0::smallint),
    ('OPEN_WELL',          'Facilities',    'GEOMETRY',        0::smallint),
    ('BORE_WELL',          'Facilities',    'POINT',           0::smallint),
    ('DMA',                'Boundaries',    'MULTIPOLYGON',    0::smallint),
    ('PANCHAYAT',          'Boundaries',    'MULTIPOLYGON',    0::smallint)
) AS s(asset_type, category, geometry_type, min_zoom)
WHERE l.asset_type = s.asset_type;

-- Anything the list above missed — a layer a tenant added by hand — is still a real layer and must
-- appear in the registry rather than fall through it with an empty category.
UPDATE gis.layers SET category = 'Other' WHERE category IS NULL;

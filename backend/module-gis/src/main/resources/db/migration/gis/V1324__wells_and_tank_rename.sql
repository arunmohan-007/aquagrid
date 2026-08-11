-- =====================================================================================
-- Open wells and bore wells; "Tanks" becomes "Over Head Tanks"
--
-- Wells are a groundwater source, not a storage structure, but they sit in Facilities because
-- that is where an operator looks for "a thing at a place that produces or holds water". They
-- get no per-type detail table (unlike gis.tanks and friends): depth, yield and water level are
-- attribute-bag fields until there is a report that needs to query them.
--
-- The rename is label-only. AssetType.TANK stays TANK — renaming the enum value would mean
-- rewriting every existing row, the CHECK constraints and any stored mapping, to change a word
-- that only ever appears in the UI.
--
-- Both CHECK constraints enumerate the asset types and neither can be appended to in place, so
-- each is dropped and recreated with the full list. Keep this list in sync with AssetType.java.
-- =====================================================================================

ALTER TABLE gis.assets DROP CONSTRAINT IF EXISTS ck_assets_type;
ALTER TABLE gis.assets
    ADD CONSTRAINT ck_assets_type CHECK (asset_type IN
        ('METER', 'VALVE', 'PIPELINE', 'HYDRANT', 'TANK', 'RESERVOIR',
         'PUMP_STATION', 'OPEN_WELL', 'BORE_WELL', 'DMA', 'PANCHAYAT',
         'SERVICE_CONNECTION', 'SENSOR'));

ALTER TABLE gis.layers DROP CONSTRAINT IF EXISTS ck_layers_asset_type;
ALTER TABLE gis.layers
    ADD CONSTRAINT ck_layers_asset_type CHECK (asset_type IN
        ('METER', 'VALVE', 'PIPELINE', 'HYDRANT', 'TANK', 'RESERVOIR',
         'PUMP_STATION', 'OPEN_WELL', 'BORE_WELL', 'DMA', 'PANCHAYAT',
         'SERVICE_CONNECTION', 'SENSOR'));

-- ---- Layer rows for every tenant -------------------------------------------------------
-- Sort order places them directly after pump stations, keeping Facilities contiguous in the
-- layers panel.
INSERT INTO gis.layers (organization_id, code, title, asset_type, is_visible, sort_order)
SELECT o.id, l.code, l.title, l.asset_type::varchar, l.is_visible, l.sort_order
FROM core.organizations o
CROSS JOIN (VALUES
    ('open-wells', 'Open Wells', 'OPEN_WELL', FALSE, 72),
    ('bore-wells', 'Bore Wells', 'BORE_WELL', FALSE, 74)
) AS l(code, title, asset_type, is_visible, sort_order)
ON CONFLICT (organization_id, code) DO NOTHING;

-- ---- Relabel the tank layer ------------------------------------------------------------
-- Scoped to the seeded title so a tenant who has already renamed the layer themselves keeps
-- their own wording.
UPDATE gis.layers SET title = 'Over Head Tanks'
WHERE asset_type = 'TANK' AND title = 'Tanks';

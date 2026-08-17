-- A field an operator marks so the bulk importer knows how to recognise a row it has already
-- seen. Deliberately its own flag rather than a reuse of is_unique: "no two assets may share this
-- value" and "use this value to decide whether an import row is new" are two different questions
-- that happen to often agree — asset_code is both — but do not always. A meter serial number can
-- be the natural re-import key on a layer without the operator wanting Postgres-style uniqueness
-- enforced on every other write path.
ALTER TABLE gis.layer_attribute_master
    ADD COLUMN duplicate_check BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN gis.layer_attribute_master.duplicate_check IS
    'True when the bulk importer should use this field (alongside asset_code) to recognise a row that already exists, rather than only counting a repeat as a new asset.';

-- asset_code is every layer's built-in identity field and is already the first thing the importer
-- checks for a match, independent of this flag. Marking it true here is documentation of that
-- fact for the Data Management grid, not a behaviour change.
UPDATE gis.layer_attribute_master
SET duplicate_check = TRUE
WHERE is_system = TRUE
  AND field_name = 'asset_code';

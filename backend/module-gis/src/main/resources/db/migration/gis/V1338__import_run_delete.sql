-- Lets an operator delete the assets a bulk import run created, from the import history.
--
-- Two things this needs that did not exist before:
--
-- 1. A way to find exactly which assets a run created. gis.import_run's row_details only logs the
--    file's row numbers for rows that were replaced, skipped or failed (V1336) — never the asset a
--    plain insert produced, for any run. import_run_id closes that going forward: every asset a run
--    creates from here on is stamped with it, so "delete this run's data" becomes an exact filter
--    instead of a guess. It is set only on the create path, never on a replace, so deleting a run
--    can never remove an asset that predates it and merely had a field updated.
--
--    Nullable, and stays that way: every asset written before this migration, and every asset
--    created by hand rather than by an import, has no run to point at.
--
-- 2. Somewhere on import_run to record that its data was removed, so the history list can show that
--    state and grey the action out rather than let it be pressed twice.
ALTER TABLE gis.assets
    ADD COLUMN import_run_id UUID REFERENCES gis.import_run (id) ON DELETE SET NULL;

CREATE INDEX ix_assets_import_run ON gis.assets (import_run_id) WHERE import_run_id IS NOT NULL;

ALTER TABLE gis.import_run
    ADD COLUMN data_deleted_at   TIMESTAMPTZ,
    ADD COLUMN data_deleted_by   UUID,
    ADD COLUMN deleted_row_count INT,
    -- Runs persisted before this migration have no import_run_id on the assets they created, so a
    -- delete for them falls back to a best-effort match (tenant + asset type + layer + actor +
    -- the run's own start/finish window) instead of an exact one. The UI reads this to warn the
    -- operator the count is an estimate rather than a certainty before they confirm.
    ADD COLUMN deleted_row_estimated BOOLEAN;

COMMENT ON COLUMN gis.assets.import_run_id IS
    'The bulk import run that created this asset, if any. Never set on a row an import only replaced.';
COMMENT ON COLUMN gis.import_run.data_deleted_at IS
    'When this run''s imported assets were deleted from the import history, or null if never.';

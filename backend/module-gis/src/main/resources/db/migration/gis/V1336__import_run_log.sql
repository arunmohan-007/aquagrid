-- Persisted history of bulk import runs.
--
-- BulkImportService's live job status (imported/replaced/skipped/failed counts, per-row outcomes)
-- lived only in an in-process ConcurrentHashMap, so it vanished the moment the polling UI closed
-- or the app restarted. This table is the durable copy: one row per import run, written when the
-- run finishes, so an operator can look up "what did last Tuesday's meter import actually do"
-- weeks later.
CREATE TABLE gis.import_run
(
    id              UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL,
    -- The in-memory job id the client polled while the run was live. Kept so a finished run can
    -- still be found from the id the UI already has, without forcing the poll loop to know about
    -- the persisted row until the run completes.
    job_id          UUID,
    actor_user_id   UUID,
    actor_username  VARCHAR(150),
    layer_id        UUID,
    asset_type      VARCHAR(60)  NOT NULL,
    file_name       VARCHAR(300),
    format          VARCHAR(20)  NOT NULL,
    state           VARCHAR(20)  NOT NULL DEFAULT 'RUNNING',
    total_rows      INT          NOT NULL DEFAULT 0,
    imported_rows   INT          NOT NULL DEFAULT 0,
    replaced_rows   INT          NOT NULL DEFAULT 0,
    skipped_rows    INT          NOT NULL DEFAULT 0,
    failed_rows     INT          NOT NULL DEFAULT 0,
    error_message   TEXT,
    -- Per-row outcome for every row that was not a plain new-row import: {row, outcome, message}.
    -- Plain imports are covered by imported_rows alone — logging one entry per successful row of a
    -- 50k-row file would make this column the size of the file being imported.
    row_details     JSONB        NOT NULL DEFAULT '[]',
    started_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by      UUID,
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_import_run_organization
        FOREIGN KEY (organization_id) REFERENCES core.organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_import_run_layer
        FOREIGN KEY (layer_id) REFERENCES gis.layers (id) ON DELETE SET NULL
);

CREATE INDEX ix_import_run_org_started ON gis.import_run (organization_id, started_at DESC);
CREATE INDEX ix_import_run_job_id ON gis.import_run (job_id);

CREATE TRIGGER trg_import_run_updated_at
    BEFORE UPDATE ON gis.import_run
    FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

COMMENT ON TABLE gis.import_run IS
    'One row per bulk import run: file, actor, per-outcome counts and per-row detail for rows that were replaced, skipped or failed.';

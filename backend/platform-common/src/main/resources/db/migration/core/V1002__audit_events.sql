-- =====================================================================================
-- audit.audit_events — the immutable audit trail
--
-- BIGSERIAL rather than UUID: this table is write-heavy, insert-ordered and never
-- addressed by an external client, so index locality matters more than opaque ids.
--
-- Append-only is enforced by a trigger, not merely by convention: a defect in application
-- code, or a compromised application role, must not be able to erase evidence.
-- Module 30 converts this into a monthly-partitioned, hash-chained table.
-- =====================================================================================

CREATE TABLE audit.audit_events
(
    id              BIGSERIAL PRIMARY KEY,
    organization_id UUID,
    actor_user_id   UUID,
    actor_username  VARCHAR(320),
    event_type      VARCHAR(100)             NOT NULL,
    category        VARCHAR(30)              NOT NULL,
    severity        VARCHAR(20)              NOT NULL DEFAULT 'INFO',
    resource_type   VARCHAR(100),
    resource_id     VARCHAR(100),
    success         BOOLEAN                  NOT NULL DEFAULT TRUE,
    message         VARCHAR(1000),
    client_ip       INET,
    user_agent      VARCHAR(512),
    trace_id        VARCHAR(64),
    metadata        JSONB                    NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ              NOT NULL DEFAULT now(),

    CONSTRAINT ck_audit_events_category CHECK (category IN
        ('AUTHENTICATION', 'AUTHORIZATION', 'DATA', 'CONFIGURATION', 'SECURITY', 'SYSTEM')),
    CONSTRAINT ck_audit_events_severity CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL'))
);

-- Query patterns: "what did this user do", "what happened in this tenant last week",
-- "show me every critical security event", "trace this request end to end".
CREATE INDEX ix_audit_events_org_time    ON audit.audit_events (organization_id, created_at DESC);
CREATE INDEX ix_audit_events_actor_time  ON audit.audit_events (actor_user_id, created_at DESC);
CREATE INDEX ix_audit_events_type_time   ON audit.audit_events (event_type, created_at DESC);
CREATE INDEX ix_audit_events_trace       ON audit.audit_events (trace_id);
CREATE INDEX ix_audit_events_critical    ON audit.audit_events (created_at DESC)
    WHERE severity = 'CRITICAL';
CREATE INDEX ix_audit_events_metadata    ON audit.audit_events USING GIN (metadata);

CREATE TRIGGER trg_audit_events_append_only
    BEFORE UPDATE OR DELETE ON audit.audit_events
    FOR EACH ROW EXECUTE FUNCTION core.reject_mutation();

COMMENT ON TABLE audit.audit_events IS
    'Append-only audit trail. UPDATE and DELETE are rejected by trigger.';

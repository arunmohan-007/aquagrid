-- =====================================================================================
-- identity.login_attempts — every authentication attempt, successful or not
--
-- Separate from audit.audit_events on purpose: this table is queried on the hot login
-- path (lockout evaluation and threat analytics) with a very specific access pattern,
-- whereas the audit trail is an append-only compliance artefact read by humans. Keeping
-- them apart lets each be indexed and retained for its own workload.
--
-- BIGSERIAL, append-only, monthly partitioning added in Module 30.
-- =====================================================================================

CREATE TABLE identity.login_attempts
(
    id              BIGSERIAL PRIMARY KEY,
    organization_id UUID,
    user_id         UUID,
    -- The identifier as supplied by the client. Retained even when it matches no user,
    -- because enumeration attempts are exactly what this column is for.
    identifier      VARCHAR(320) NOT NULL,
    outcome         VARCHAR(30)  NOT NULL,
    failure_reason  VARCHAR(60),
    client_ip       INET,
    user_agent      VARCHAR(512),
    mfa_used        BOOLEAN      NOT NULL DEFAULT FALSE,
    trace_id        VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_login_attempts_user
        FOREIGN KEY (user_id) REFERENCES identity.users (id) ON DELETE SET NULL,
    CONSTRAINT ck_login_attempts_outcome CHECK (outcome IN
        ('SUCCESS', 'MFA_PENDING', 'INVALID_CREDENTIALS', 'UNKNOWN_IDENTIFIER',
         'ACCOUNT_LOCKED', 'ACCOUNT_DISABLED', 'ACCOUNT_PENDING', 'ORGANIZATION_INACTIVE',
         'MFA_FAILED', 'RATE_LIMITED'))
);

CREATE INDEX ix_login_attempts_identifier_time ON identity.login_attempts (identifier, created_at DESC);
CREATE INDEX ix_login_attempts_ip_time ON identity.login_attempts (client_ip, created_at DESC);
CREATE INDEX ix_login_attempts_user_time ON identity.login_attempts (user_id, created_at DESC);
CREATE INDEX ix_login_attempts_failures ON identity.login_attempts (created_at DESC)
    WHERE outcome <> 'SUCCESS';

CREATE TRIGGER trg_login_attempts_append_only
    BEFORE UPDATE OR DELETE ON identity.login_attempts
    FOR EACH ROW EXECUTE FUNCTION core.reject_mutation();

-- =====================================================================================
-- Credential and session lifecycle tables
-- =====================================================================================

-- ---- Refresh tokens ------------------------------------------------------------------
-- Rotation with reuse detection.
--
--   * Only the SHA-256 hash is stored. A database dump therefore yields no usable
--     sessions. SHA-256 rather than BCrypt is correct here: the token is 256 bits of
--     CSPRNG output with no exploitable structure, so a slow KDF buys nothing while a
--     fast digest lets the lookup use a unique index instead of a table scan.
--   * family_id groups every token descended from one login. When a token that has
--     already been rotated or revoked is presented, the token has leaked: the whole
--     family is revoked and a CRITICAL audit event is raised.
--   * replaced_by_id preserves the rotation chain for forensics.
CREATE TABLE identity.refresh_tokens
(
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID        NOT NULL,
    family_id      UUID        NOT NULL,
    token_hash     CHAR(64)    NOT NULL,
    issued_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ NOT NULL,
    last_used_at   TIMESTAMPTZ,
    revoked_at     TIMESTAMPTZ,
    revoked_reason VARCHAR(30),
    replaced_by_id UUID,

    client_ip      INET,
    user_agent     VARCHAR(512),
    device_label   VARCHAR(120),

    CONSTRAINT fk_refresh_tokens_user
        FOREIGN KEY (user_id) REFERENCES identity.users (id) ON DELETE CASCADE,
    CONSTRAINT fk_refresh_tokens_replaced_by
        FOREIGN KEY (replaced_by_id) REFERENCES identity.refresh_tokens (id) ON DELETE SET NULL,
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT ck_refresh_tokens_reason CHECK (revoked_reason IS NULL OR revoked_reason IN
        ('LOGOUT', 'LOGOUT_ALL', 'ROTATED', 'REUSE_DETECTED', 'PASSWORD_CHANGED',
         'ADMIN_REVOKED', 'MFA_CHANGED', 'EXPIRED', 'USER_DISABLED')),
    CONSTRAINT ck_refresh_tokens_expiry CHECK (expires_at > issued_at)
);

CREATE INDEX ix_refresh_tokens_user_active ON identity.refresh_tokens (user_id)
    WHERE revoked_at IS NULL;
CREATE INDEX ix_refresh_tokens_family ON identity.refresh_tokens (family_id);
-- Supports the scheduled reaper that deletes expired rows.
CREATE INDEX ix_refresh_tokens_expiry ON identity.refresh_tokens (expires_at);

COMMENT ON COLUMN identity.refresh_tokens.token_hash IS
    'Lowercase hex SHA-256 of the opaque token. The token itself is never persisted.';

-- ---- Password reset ------------------------------------------------------------------
CREATE TABLE identity.password_reset_tokens
(
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID        NOT NULL,
    token_hash   CHAR(64)    NOT NULL,
    issued_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    used_at      TIMESTAMPTZ,
    requested_ip INET,

    CONSTRAINT fk_password_reset_tokens_user
        FOREIGN KEY (user_id) REFERENCES identity.users (id) ON DELETE CASCADE,
    CONSTRAINT uq_password_reset_tokens_hash UNIQUE (token_hash),
    CONSTRAINT ck_password_reset_tokens_expiry CHECK (expires_at > issued_at)
);

CREATE INDEX ix_password_reset_tokens_user ON identity.password_reset_tokens (user_id)
    WHERE used_at IS NULL;
CREATE INDEX ix_password_reset_tokens_expiry ON identity.password_reset_tokens (expires_at);

-- ---- Password history ----------------------------------------------------------------
-- Enforces "do not reuse your last N passwords". Stores full hashes, so the comparison is
-- a BCrypt match against each entry, never a reversible transformation.
CREATE TABLE identity.password_history
(
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID         NOT NULL,
    password_hash VARCHAR(120) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_password_history_user
        FOREIGN KEY (user_id) REFERENCES identity.users (id) ON DELETE CASCADE
);

CREATE INDEX ix_password_history_user_time ON identity.password_history (user_id, created_at DESC);

-- ---- MFA recovery codes ----------------------------------------------------------------
-- Single use, hashed at rest, issued as a batch at enrolment and shown exactly once.
CREATE TABLE identity.mfa_recovery_codes
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL,
    code_hash  CHAR(64)    NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    used_at    TIMESTAMPTZ,
    used_ip    INET,

    CONSTRAINT fk_mfa_recovery_codes_user
        FOREIGN KEY (user_id) REFERENCES identity.users (id) ON DELETE CASCADE,
    CONSTRAINT uq_mfa_recovery_codes_hash UNIQUE (code_hash)
);

CREATE INDEX ix_mfa_recovery_codes_user ON identity.mfa_recovery_codes (user_id)
    WHERE used_at IS NULL;

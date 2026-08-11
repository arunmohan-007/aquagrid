-- =====================================================================================
-- Module 2 — User & Role Management
-- Owner : module-identity (version range V1100–V1199)
--
-- Adds the invitation flow. Users, roles, permissions and the join tables already exist
-- from V1100/V1103 — Module 2 does not redefine them. The only new table is the pending
-- invitation, which lets an administrator provision a user who has not yet set a password.
--
-- Design notes:
--  * The invitation token is opaque; only its SHA-256 hash is stored (mirroring refresh
--    tokens). A database dump does not yield working invitations.
--  * A partial unique index makes "at most one outstanding invitation per email" cheap to
--    enforce and query, without blocking a future re-invitation after expiry/revocation.
--  * organization_id + role codes are denormalised onto the row so the activation step can
--    materialise the user without a multi-table read-modify-write on the invitation.
-- =====================================================================================

CREATE TABLE identity.user_invitations
(
    id              UUID PRIMARY KEY                  DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL,
    email           CITEXT       NOT NULL,
    full_name       VARCHAR(200) NOT NULL,
    username        CITEXT       NOT NULL,
    job_title       VARCHAR(120),
    phone           VARCHAR(40),

    -- SHA-256 of the opaque invitation token. The plaintext is shown once, to the inviter,
    -- and delivered by the notification centre (Module 20). It is never stored.
    token_hash      CHAR(64)     NOT NULL,

    -- The role codes to grant on activation. Stored as a JSON array of strings rather than a
    -- join table because an invitation is a transient document: once activated it is consumed
    -- and the grants materialise on identity.user_roles.
    role_codes      JSONB        NOT NULL DEFAULT '[]'::jsonb,

    invited_by      UUID         NOT NULL,
    invited_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ  NOT NULL,
    accepted_at     TIMESTAMPTZ,
    accepted_by     UUID,
    revoked_at      TIMESTAMPTZ,
    revoked_by      UUID,
    client_ip       INET,

    CONSTRAINT fk_invitations_organization
        FOREIGN KEY (organization_id) REFERENCES core.organizations (id) ON DELETE RESTRICT,
    CONSTRAINT fk_invitations_invited_by
        FOREIGN KEY (invited_by) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_invitations_email_format
        CHECK (email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'),
    CONSTRAINT ck_invitations_username_format
        CHECK (username ~ '^[A-Za-z0-9._-]{3,60}$'),
    -- An invitation is in one of three lifecycle states. The CHECK keeps the table honest
    -- even under direct SQL: a row cannot be both accepted and revoked.
    CONSTRAINT ck_invitations_lifecycle CHECK (
        (accepted_at IS NULL AND accepted_by IS NULL)
        AND ((revoked_at IS NULL) = (revoked_by IS NULL))
    )
);

-- At most one outstanding (not accepted, not revoked) invitation per email within a tenant.
-- Multiple outstanding invitations across different tenants are permitted because email is
-- globally unique but a person may legitimately be invited to more than one organisation
-- during a migration.
CREATE UNIQUE INDEX uq_invitations_outstanding_email_org
    ON identity.user_invitations (organization_id, email)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;

CREATE INDEX ix_invitations_token_hash
    ON identity.user_invitations (token_hash)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;
CREATE INDEX ix_invitations_org_status
    ON identity.user_invitations (organization_id, invited_at DESC);

COMMENT ON TABLE identity.user_invitations IS
    'Pending user invitations. Consumed exactly once on activation; the token is never stored in plaintext.';
COMMENT ON COLUMN identity.user_invitations.token_hash IS
    'SHA-256 of the opaque invitation token. Mirrors the refresh-token scheme.';

-- =====================================================================================
-- Identity core — users, roles and permissions
-- Owner : module-identity (version range V1100–V1199)
-- =====================================================================================

-- ---- Permissions: the authorisation vocabulary --------------------------------------
-- Global (not tenant-owned) so that @PreAuthorize can reference stable compile-time
-- constants. Each module adds its own rows in its own migration.
CREATE TABLE identity.permissions
(
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(120) NOT NULL,
    domain      VARCHAR(40)  NOT NULL,
    resource    VARCHAR(60)  NOT NULL,
    action      VARCHAR(40)  NOT NULL,
    description VARCHAR(300),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_permissions_code UNIQUE (code),
    CONSTRAINT ck_permissions_code_format CHECK (code ~ '^[a-z0-9-]+:[a-z0-9-]+:[a-z0-9-]+$')
);

COMMENT ON TABLE identity.permissions IS
    'Global catalogue of permission codes in domain:resource:action form';

-- ---- Roles --------------------------------------------------------------------------
-- organization_id NULL => a system role shipped with the product, identical for every
-- tenant and immutable through the API. Non-null => a tenant-authored custom role.
CREATE TABLE identity.roles
(
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID,
    code            VARCHAR(60)  NOT NULL,
    name            VARCHAR(120) NOT NULL,
    description     VARCHAR(300),
    is_system       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by      UUID,
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_roles_organization
        FOREIGN KEY (organization_id) REFERENCES core.organizations (id) ON DELETE CASCADE,
    CONSTRAINT ck_roles_code_format CHECK (code ~ '^[A-Z][A-Z0-9_]{1,59}$'),
    CONSTRAINT ck_roles_system_is_global CHECK (NOT is_system OR organization_id IS NULL)
);

-- Two partial unique indexes rather than one composite: a UNIQUE constraint treats NULLs
-- as distinct, which would silently permit duplicate system roles.
CREATE UNIQUE INDEX uq_roles_system_code ON identity.roles (code)
    WHERE organization_id IS NULL;
CREATE UNIQUE INDEX uq_roles_tenant_code ON identity.roles (organization_id, code)
    WHERE organization_id IS NOT NULL;

CREATE TRIGGER trg_roles_updated_at
    BEFORE UPDATE ON identity.roles
    FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

CREATE TABLE identity.role_permissions
(
    role_id       UUID        NOT NULL,
    permission_id UUID        NOT NULL,
    granted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_role_permissions PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permissions_role
        FOREIGN KEY (role_id) REFERENCES identity.roles (id) ON DELETE CASCADE,
    CONSTRAINT fk_role_permissions_permission
        FOREIGN KEY (permission_id) REFERENCES identity.permissions (id) ON DELETE CASCADE
);

CREATE INDEX ix_role_permissions_permission ON identity.role_permissions (permission_id);

-- ---- Users --------------------------------------------------------------------------
CREATE TABLE identity.users
(
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id       UUID         NOT NULL,

    username              CITEXT       NOT NULL,
    email                 CITEXT       NOT NULL,
    email_verified_at     TIMESTAMPTZ,

    password_hash         VARCHAR(120) NOT NULL,
    password_updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    must_change_password  BOOLEAN      NOT NULL DEFAULT FALSE,

    full_name             VARCHAR(200) NOT NULL,
    job_title             VARCHAR(120),
    phone                 VARCHAR(40),
    avatar_url            VARCHAR(500),

    status                VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    failed_login_attempts INTEGER      NOT NULL DEFAULT 0,
    lockout_until         TIMESTAMPTZ,

    mfa_enabled           BOOLEAN      NOT NULL DEFAULT FALSE,
    mfa_secret            TEXT,
    mfa_confirmed_at      TIMESTAMPTZ,

    last_login_at         TIMESTAMPTZ,
    last_login_ip         INET,

    timezone              VARCHAR(60),
    locale                VARCHAR(20),
    preferences           JSONB        NOT NULL DEFAULT '{}'::jsonb,

    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by            UUID,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by            UUID,
    version               BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_users_organization
        FOREIGN KEY (organization_id) REFERENCES core.organizations (id) ON DELETE RESTRICT,
    CONSTRAINT ck_users_status CHECK (status IN ('PENDING', 'ACTIVE', 'DISABLED', 'LOCKED')),
    CONSTRAINT ck_users_email_format CHECK (email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'),
    CONSTRAINT ck_users_username_format CHECK (username ~ '^[A-Za-z0-9._-]{3,60}$'),
    CONSTRAINT ck_users_failed_attempts CHECK (failed_login_attempts >= 0),
    -- MFA cannot be "enabled" without a stored secret; this invariant is what makes
    -- "mfa_enabled = true" trustworthy as an authorisation decision.
    CONSTRAINT ck_users_mfa_consistent CHECK (NOT mfa_enabled OR mfa_secret IS NOT NULL)
);

-- Globally unique email: enables login by email with no tenant hint, and makes password
-- reset unambiguous. Username is unique only within a tenant, because utilities reuse
-- staff codes across organisations.
CREATE UNIQUE INDEX uq_users_email ON identity.users (email);
CREATE UNIQUE INDEX uq_users_org_username ON identity.users (organization_id, username);
CREATE INDEX ix_users_org_status ON identity.users (organization_id, status);
CREATE INDEX ix_users_lockout ON identity.users (lockout_until) WHERE lockout_until IS NOT NULL;
CREATE INDEX ix_users_name_trgm ON identity.users USING GIN (full_name gin_trgm_ops);

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON identity.users
    FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

COMMENT ON COLUMN identity.users.mfa_secret IS
    'AES-256-GCM ciphertext of the TOTP seed. Never stored in plaintext.';
COMMENT ON COLUMN identity.users.password_hash IS
    'Spring Security delegating format, e.g. {bcrypt}$2a$12$... The algorithm prefix is what makes a future migration to Argon2id possible without a flag day.';

CREATE TABLE identity.user_roles
(
    user_id     UUID        NOT NULL,
    role_id     UUID        NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    assigned_by UUID,

    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user
        FOREIGN KEY (user_id) REFERENCES identity.users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role
        FOREIGN KEY (role_id) REFERENCES identity.roles (id) ON DELETE CASCADE
);

CREATE INDEX ix_user_roles_role ON identity.user_roles (role_id);

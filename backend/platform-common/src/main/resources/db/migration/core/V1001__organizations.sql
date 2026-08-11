-- =====================================================================================
-- core.organizations — the tenant root
--
-- Owned by the platform kernel rather than by Module 3, because authentication cannot
-- exist without a tenant. Module 3 (Organization Management) extends this table with
-- branding, contacts and licensing; it does not redefine it.
--
-- The spatial columns are present from the very first migration on purpose: a tenant's
-- default map extent is an identity concern (returned by /auth/me), and retrofitting
-- geometry onto the tenant root later would touch every module that joins to it.
-- =====================================================================================

CREATE TABLE core.organizations
(
    id                  UUID PRIMARY KEY                  DEFAULT gen_random_uuid(),
    parent_id           UUID,
    code                CITEXT                   NOT NULL,
    name                VARCHAR(200)             NOT NULL,
    legal_name          VARCHAR(300),
    type                VARCHAR(40)              NOT NULL,
    status              VARCHAR(20)              NOT NULL DEFAULT 'ACTIVE',

    -- Contact
    contact_email       CITEXT,
    contact_phone       VARCHAR(40),
    address_line1       VARCHAR(200),
    address_line2       VARCHAR(200),
    city                VARCHAR(120),
    state               VARCHAR(120),
    country_code        CHAR(2),
    postal_code         VARCHAR(20),

    -- Locale defaults inherited by every user of the tenant
    timezone            VARCHAR(60)              NOT NULL DEFAULT 'UTC',
    locale              VARCHAR(20)              NOT NULL DEFAULT 'en',
    currency_code       CHAR(3)                  NOT NULL DEFAULT 'INR',
    unit_system         VARCHAR(20)              NOT NULL DEFAULT 'METRIC',

    -- GIS bootstrap: where the map opens, and the tenant's service area
    centroid            geometry(Point, 4326),
    boundary            geometry(MultiPolygon, 4326),
    default_zoom        SMALLINT                 NOT NULL DEFAULT 12,

    settings            JSONB                    NOT NULL DEFAULT '{}'::jsonb,

    created_at          TIMESTAMPTZ              NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_at          TIMESTAMPTZ              NOT NULL DEFAULT now(),
    updated_by          UUID,
    version             BIGINT                   NOT NULL DEFAULT 0,

    CONSTRAINT fk_organizations_parent
        FOREIGN KEY (parent_id) REFERENCES core.organizations (id) ON DELETE RESTRICT,
    CONSTRAINT uq_organizations_code UNIQUE (code),
    CONSTRAINT ck_organizations_type CHECK (type IN
        ('MUNICIPALITY', 'WATER_AUTHORITY', 'UTILITY', 'INDUSTRY', 'GOVERNMENT', 'OPERATOR')),
    CONSTRAINT ck_organizations_status CHECK (status IN
        ('ACTIVE', 'SUSPENDED', 'ARCHIVED')),
    CONSTRAINT ck_organizations_zoom CHECK (default_zoom BETWEEN 0 AND 22),
    CONSTRAINT ck_organizations_not_own_parent CHECK (parent_id IS NULL OR parent_id <> id)
);

CREATE INDEX ix_organizations_parent ON core.organizations (parent_id);
CREATE INDEX ix_organizations_status ON core.organizations (status);
CREATE INDEX ix_organizations_boundary ON core.organizations USING GIST (boundary);
CREATE INDEX ix_organizations_centroid ON core.organizations USING GIST (centroid);

CREATE TRIGGER trg_organizations_updated_at
    BEFORE UPDATE ON core.organizations
    FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

COMMENT ON TABLE core.organizations IS
    'Tenant root. Every tenant-owned row in the platform carries organization_id referencing this table.';
COMMENT ON COLUMN core.organizations.code IS
    'Login discriminator, e.g. KWA-TVM. Case-insensitive and globally unique.';
COMMENT ON COLUMN core.organizations.boundary IS
    'Service area. Basis for geofencing (Module 4) and spatial row-level authorisation (Module 3).';

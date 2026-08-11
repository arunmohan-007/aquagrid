-- =====================================================================================
-- AquaGrid — platform baseline
-- Owner : platform kernel (version range V1000–V1099)
-- Creates the schema layout, required extensions and shared helper functions.
-- =====================================================================================

-- ---- Extensions ---------------------------------------------------------------------
-- postgis           : spatial types, indexes and functions (the product's foundation)
-- citext            : case-insensitive text, used for usernames, emails and tenant codes
-- pgcrypto          : gen_random_uuid() and digest() helpers
-- pg_trgm           : trigram indexes for fuzzy asset/address search (Module 4)
-- btree_gist        : mixed btree+gist exclusion constraints (asset scheduling, Module 22)
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ---- Schemas ------------------------------------------------------------------------
-- One schema per bounded context. This keeps the microservice extraction boundary visible
-- in the database itself and allows per-schema grants to future service accounts.
CREATE SCHEMA IF NOT EXISTS core;       -- tenant root and cross-cutting reference data
CREATE SCHEMA IF NOT EXISTS identity;   -- users, roles, permissions, credentials
CREATE SCHEMA IF NOT EXISTS audit;      -- immutable audit trail
CREATE SCHEMA IF NOT EXISTS org;        -- organisation structure, sites, zones, DMAs
CREATE SCHEMA IF NOT EXISTS gis;        -- spatial assets and network topology
CREATE SCHEMA IF NOT EXISTS iot;        -- devices, gateways, communication bindings
CREATE SCHEMA IF NOT EXISTS ts;         -- time-series telemetry (TimescaleDB hypertables)
CREATE SCHEMA IF NOT EXISTS ops;        -- alarms, work orders, maintenance
CREATE SCHEMA IF NOT EXISTS billing;    -- customers, tariffs, consumption billing
CREATE SCHEMA IF NOT EXISTS analytics;  -- NRW, water balance, model outputs

COMMENT ON SCHEMA core      IS 'Tenant root and cross-cutting reference data';
COMMENT ON SCHEMA identity  IS 'Authentication and authorisation (Modules 1-2)';
COMMENT ON SCHEMA audit     IS 'Append-only audit trail (Module 30)';

-- ---- Shared helper functions --------------------------------------------------------

-- Maintains updated_at on any table that installs the trigger below. Hibernate also sets
-- this column, but the trigger guarantees correctness for direct SQL, bulk loads and
-- migrations — anything that bypasses the ORM.
CREATE OR REPLACE FUNCTION core.set_updated_at()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION core.set_updated_at() IS
    'BEFORE UPDATE trigger function maintaining updated_at';

-- Rejects UPDATE and DELETE. Installed on append-only tables (audit trail, telemetry) so
-- that history cannot be rewritten even by an application defect or a compromised app role.
CREATE OR REPLACE FUNCTION core.reject_mutation()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Table %.% is append-only; % is not permitted',
        TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

COMMENT ON FUNCTION core.reject_mutation() IS
    'Trigger function enforcing append-only semantics';

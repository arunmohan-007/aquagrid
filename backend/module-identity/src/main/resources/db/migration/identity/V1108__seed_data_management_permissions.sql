-- =====================================================================================
-- Seed: Data Management permissions
-- Owner : module-identity (version range V1100–V1199)
--
-- gis: permissions seeded from an identity migration, following V1103 and V1107:
-- identity.permissions is identity's table and no other module writes to it. The catalogue is
-- declared in platform-security's Permissions.java and mirrored here.
--
-- Read and manage are separate for the usual reason, and one less usual one. Reading the
-- catalogue is close to harmless — it is a field list. Managing it is not: deactivating an
-- attribute removes it from every subsequent import and export, so a field a contractor has
-- been delivering for two years silently stops being read, and nobody finds out until someone
-- queries for it. That is a configuration change with the blast radius of a schema change, and
-- it is granted accordingly.
-- =====================================================================================

INSERT INTO identity.permissions (code, domain, resource, action, description)
VALUES
    ('gis:metadata:read',   'gis', 'metadata', 'read',
        'View the layer and attribute catalogue'),
    ('gis:metadata:manage', 'gis', 'metadata', 'manage',
        'Create, edit and deactivate layer attributes, changing what every import and export reads');

-- V1103's CROSS JOIN grant to SUPER_ADMIN ran against the permissions that existed then, so it
-- does not cover these. Re-running it for this migration's own rows is the documented convention.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM identity.roles r
         CROSS JOIN identity.permissions p
WHERE r.code = 'SUPER_ADMIN'
  AND r.organization_id IS NULL
  AND p.code IN ('gis:metadata:read', 'gis:metadata:manage');

-- ---- Who gets what -----------------------------------------------------------------------
-- Manage goes to the two administrator roles only: the platform operator and the tenant's own
-- administrator. This is the "top roles only" the module was asked for, expressed as a grant
-- rather than as a role check in code — a utility that invents its own "Data Steward" role must
-- be able to grant it without a release, which is the whole reason authorisation here is
-- permission-based and never names a role.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM identity.roles r
         JOIN identity.permissions p ON p.code = 'gis:metadata:manage'
WHERE r.code = 'ORG_ADMIN' AND r.organization_id IS NULL;

-- Read goes further. A GIS analyst mapping a contractor's columns onto platform fields needs to
-- see what those fields are and what they mean; withholding the field list from the people who
-- do the mapping is how mis-mapped imports happen. A network engineer reading an export needs
-- the same reference.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM identity.roles r
         JOIN identity.permissions p ON p.code = 'gis:metadata:read'
WHERE r.code IN ('ORG_ADMIN', 'GIS_ANALYST', 'NETWORK_ENGINEER') AND r.organization_id IS NULL;

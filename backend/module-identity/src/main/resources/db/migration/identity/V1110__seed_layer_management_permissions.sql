-- =====================================================================================
-- Seed: Layer Management and Layer Style permissions
-- Owner : module-identity (version range V1100–V1199)
--
-- gis: permissions seeded from an identity migration, following V1103, V1107 and V1108:
-- identity.permissions is identity's table and no other module writes to it. The catalogue is
-- declared in platform-security's Permissions.java and mirrored here.
--
-- Four permissions rather than two, because the two jobs have different blast radii and, more to
-- the point, different people do them.
--
-- Archiving a layer takes it off the map, out of the import hub and out of every export for every
-- user of the tenant — the registry decides what the product *contains*. Creating a style changes
-- what that content looks like. A cartographer who should be able to recolour the network and put
-- labels on tanks has no business retiring the pipeline layer, and the seed says so rather than
-- hoping a screen hides the button.
--
-- Read is split the same way for the reason V1108 gives: the style API is consumed by the map
-- itself, so every operator who may open the map must be able to read styles, while reading the
-- registry's editable metadata is an administrative view.
-- =====================================================================================

INSERT INTO identity.permissions (code, domain, resource, action, description)
VALUES
    ('gis:layer:read',   'gis', 'layer', 'read',
        'View the GIS layer registry and its configuration'),
    ('gis:layer:manage', 'gis', 'layer', 'manage',
        'Create, edit, enable, disable and archive GIS layers'),
    ('gis:style:read',   'gis', 'style', 'read',
        'View layer style configurations'),
    ('gis:style:manage', 'gis', 'style', 'manage',
        'Create, edit, activate and deactivate layer styles');

-- V1103's CROSS JOIN grant to SUPER_ADMIN ran against the permissions that existed then, so it
-- does not cover these. Re-running it for this migration's own rows is the documented convention.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM identity.roles r
         CROSS JOIN identity.permissions p
WHERE r.code = 'SUPER_ADMIN'
  AND r.organization_id IS NULL
  AND p.code IN ('gis:layer:read', 'gis:layer:manage', 'gis:style:read', 'gis:style:manage');

-- ---- Who gets what -----------------------------------------------------------------------
-- Layer management: the tenant administrator only. Registering a layer is close to declaring a new
-- kind of thing the utility records, and archiving one withdraws it from every screen at once.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM identity.roles r
         JOIN identity.permissions p ON p.code = 'gis:layer:manage'
WHERE r.code = 'ORG_ADMIN' AND r.organization_id IS NULL;

-- Style management reaches the GIS analyst as well. Deciding that faulty valves draw red is
-- cartography, it is reversible, and it is the analyst's job — routing it through an administrator
-- would mean the person who knows what the map should say cannot make it say it.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM identity.roles r
         JOIN identity.permissions p ON p.code = 'gis:style:manage'
WHERE r.code IN ('ORG_ADMIN', 'GIS_ANALYST') AND r.organization_id IS NULL;

-- Reading the registry follows the Data Management precedent: the people who map contractor
-- columns onto platform fields need to see which layers exist and what they hold.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM identity.roles r
         JOIN identity.permissions p ON p.code = 'gis:layer:read'
WHERE r.code IN ('ORG_ADMIN', 'GIS_ANALYST', 'NETWORK_ENGINEER') AND r.organization_id IS NULL;

/*
 * Reading styles goes to everyone who can open the map, which is wider than the other three.
 *
 * This is not generosity, it is a consequence of where styling now lives: the map fetches its
 * MapLibre layer specifications from the style endpoint, so an operator without gis:style:read
 * would get a map with correct geometry and no symbology — every layer grey, in a product whose
 * legend is how you tell a valve from a hydrant. The permission that gates the styles must
 * therefore be no narrower than the one that gates the map.
 */
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT DISTINCT rp.role_id, p.id
FROM identity.role_permissions rp
         JOIN identity.permissions map_view ON map_view.id = rp.permission_id
         CROSS JOIN identity.permissions p
WHERE map_view.code = 'gis:map:view'
  AND p.code = 'gis:style:read'
  AND NOT EXISTS (
      SELECT 1 FROM identity.role_permissions existing
      WHERE existing.role_id = rp.role_id AND existing.permission_id = p.id);

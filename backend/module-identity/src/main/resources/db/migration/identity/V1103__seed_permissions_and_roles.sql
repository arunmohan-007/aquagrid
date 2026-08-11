-- =====================================================================================
-- Seed: permission catalogue, system roles and the platform operator tenant
--
-- Deliberately absent: a bootstrap administrator with a hard-coded password hash. A
-- shipped default credential is a shipped vulnerability — every scanner knows the
-- vendor defaults. The first administrator is created at startup by
-- BootstrapAdminInitializer from an environment-supplied password, with
-- must_change_password = true.
-- =====================================================================================

INSERT INTO identity.permissions (code, domain, resource, action, description)
VALUES
    -- Identity (Modules 1-2)
    ('identity:user:read',            'identity',  'user',        'read',           'View users'),
    ('identity:user:create',          'identity',  'user',        'create',         'Create users'),
    ('identity:user:update',          'identity',  'user',        'update',         'Modify users'),
    ('identity:user:delete',          'identity',  'user',        'delete',         'Delete users'),
    ('identity:user:impersonate',     'identity',  'user',        'impersonate',    'Impersonate another user for support'),
    ('identity:user:session-revoke',  'identity',  'user',        'session-revoke', 'Revoke another user''s sessions'),
    ('identity:role:read',            'identity',  'role',        'read',           'View roles and permissions'),
    ('identity:role:manage',          'identity',  'role',        'manage',         'Create and modify roles'),

    -- Organization (Module 3)
    ('org:organization:read',         'org',       'organization', 'read',          'View organisation details'),
    ('org:organization:manage',       'org',       'organization', 'manage',        'Modify organisation settings and hierarchy'),

    -- GIS and assets (Modules 4, 11, 12, 23)
    ('gis:map:view',                  'gis',       'map',          'view',          'Open the GIS dashboard'),
    ('gis:asset:read',                'gis',       'asset',        'read',          'View spatial assets'),
    ('gis:asset:create',              'gis',       'asset',        'create',        'Create spatial assets'),
    ('gis:asset:update',              'gis',       'asset',        'update',        'Modify spatial assets and geometry'),
    ('gis:asset:delete',              'gis',       'asset',        'delete',        'Delete spatial assets'),
    ('gis:network:trace',             'gis',       'network',      'trace',         'Run pipeline tracing and network analysis'),

    -- IoT (Modules 6, 7, 17, 18)
    ('iot:device:read',               'iot',       'device',       'read',          'View devices and telemetry'),
    ('iot:device:manage',             'iot',       'device',       'manage',        'Provision and configure devices'),
    ('iot:device:command',            'iot',       'device',       'command',       'Send downlink commands to devices'),
    ('iot:device:firmware',           'iot',       'device',       'firmware',      'Manage and push firmware'),
    ('iot:simulator:run',             'iot',       'simulator',    'run',           'Run the device simulator'),

    -- Operations (Modules 19-22)
    ('ops:alarm:read',                'ops',       'alarm',        'read',          'View alarms'),
    ('ops:alarm:acknowledge',         'ops',       'alarm',        'acknowledge',   'Acknowledge and clear alarms'),
    ('ops:work-order:read',           'ops',       'work-order',   'read',          'View work orders'),
    ('ops:work-order:manage',         'ops',       'work-order',   'manage',        'Create, assign and close work orders'),

    -- Analytics and reporting (Modules 24-29)
    ('analytics:dashboard:view',      'analytics', 'dashboard',    'view',          'View analytics dashboards'),
    ('analytics:nrw:view',            'analytics', 'nrw',          'view',          'View NRW and water balance analysis'),
    ('report:report:generate',        'report',    'report',       'generate',      'Generate and schedule reports'),

    -- Administration (Modules 30-34)
    ('admin:audit:read',              'admin',     'audit',        'read',          'Read the audit trail'),
    ('admin:system:monitor',          'admin',     'system',       'monitor',       'Access system monitoring endpoints'),
    ('admin:backup:manage',           'admin',     'backup',       'manage',        'Run and restore backups'),
    ('admin:settings:manage',         'admin',     'settings',     'manage',        'Change platform and tenant settings');

-- ---- System roles --------------------------------------------------------------------
INSERT INTO identity.roles (code, name, description, is_system)
VALUES
    ('SUPER_ADMIN',       'Platform Administrator',
        'Full control of the platform across every tenant. Reserved for the operator.', TRUE),
    ('ORG_ADMIN',         'Organization Administrator',
        'Full control within one organisation, including users and settings.', TRUE),
    ('NETWORK_ENGINEER',  'Network Engineer',
        'Designs and maintains the distribution network; edits assets and runs analysis.', TRUE),
    ('GIS_ANALYST',       'GIS Analyst',
        'Maintains spatial data and produces map-based analysis.', TRUE),
    ('OPERATOR',          'Control Room Operator',
        'Monitors live telemetry, handles alarms and dispatches work.', TRUE),
    ('FIELD_TECHNICIAN',  'Field Technician',
        'Executes work orders and reads assets in the field.', TRUE),
    ('VIEWER',            'Viewer',
        'Read-only access to dashboards, maps and reports.', TRUE);

-- ---- Role → permission grants ----------------------------------------------------------
-- SUPER_ADMIN receives every permission, including any added by later modules' migrations
-- (each such migration re-runs this grant for its own rows).
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM identity.roles r
         CROSS JOIN identity.permissions p
WHERE r.code = 'SUPER_ADMIN' AND r.organization_id IS NULL;

INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM identity.roles r
         JOIN identity.permissions p ON p.code IN (
            'identity:user:read', 'identity:user:create', 'identity:user:update',
            'identity:user:delete', 'identity:user:session-revoke',
            'identity:role:read', 'identity:role:manage',
            'org:organization:read', 'org:organization:manage',
            'gis:map:view', 'gis:asset:read', 'gis:asset:create', 'gis:asset:update',
            'gis:asset:delete', 'gis:network:trace',
            'iot:device:read', 'iot:device:manage', 'iot:device:command', 'iot:device:firmware',
            'ops:alarm:read', 'ops:alarm:acknowledge',
            'ops:work-order:read', 'ops:work-order:manage',
            'analytics:dashboard:view', 'analytics:nrw:view', 'report:report:generate',
            'admin:audit:read', 'admin:settings:manage')
WHERE r.code = 'ORG_ADMIN' AND r.organization_id IS NULL;

INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM identity.roles r
         JOIN identity.permissions p ON p.code IN (
            'gis:map:view', 'gis:asset:read', 'gis:asset:create', 'gis:asset:update',
            'gis:network:trace', 'iot:device:read', 'ops:alarm:read',
            'ops:work-order:read', 'ops:work-order:manage',
            'analytics:dashboard:view', 'analytics:nrw:view', 'report:report:generate')
WHERE r.code = 'NETWORK_ENGINEER' AND r.organization_id IS NULL;

INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM identity.roles r
         JOIN identity.permissions p ON p.code IN (
            'gis:map:view', 'gis:asset:read', 'gis:asset:create', 'gis:asset:update',
            'gis:asset:delete', 'gis:network:trace',
            'analytics:dashboard:view', 'report:report:generate')
WHERE r.code = 'GIS_ANALYST' AND r.organization_id IS NULL;

INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM identity.roles r
         JOIN identity.permissions p ON p.code IN (
            'gis:map:view', 'gis:asset:read',
            'iot:device:read', 'iot:device:command',
            'ops:alarm:read', 'ops:alarm:acknowledge',
            'ops:work-order:read', 'ops:work-order:manage',
            'analytics:dashboard:view')
WHERE r.code = 'OPERATOR' AND r.organization_id IS NULL;

INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM identity.roles r
         JOIN identity.permissions p ON p.code IN (
            'gis:map:view', 'gis:asset:read', 'gis:asset:update',
            'iot:device:read', 'ops:alarm:read',
            'ops:work-order:read', 'ops:work-order:manage')
WHERE r.code = 'FIELD_TECHNICIAN' AND r.organization_id IS NULL;

INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM identity.roles r
         JOIN identity.permissions p ON p.code IN (
            'gis:map:view', 'gis:asset:read', 'iot:device:read', 'ops:alarm:read',
            'ops:work-order:read', 'analytics:dashboard:view', 'analytics:nrw:view')
WHERE r.code = 'VIEWER' AND r.organization_id IS NULL;

-- ---- Platform operator tenant -----------------------------------------------------------
-- The organisation that owns the deployment itself. Customer tenants are created through
-- Module 3; this one must exist before any user can.
INSERT INTO core.organizations (code, name, legal_name, type, status, timezone, locale,
                                currency_code, centroid, default_zoom)
VALUES ('SYSTEM', 'Platform Operator', 'AquaGrid Platform Operator', 'OPERATOR', 'ACTIVE',
        'Asia/Kolkata', 'en', 'INR',
        ST_SetSRID(ST_MakePoint(76.9366, 8.5241), 4326), 11);

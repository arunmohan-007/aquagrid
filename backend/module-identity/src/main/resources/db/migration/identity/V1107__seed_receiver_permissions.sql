-- =====================================================================================
-- Seed: Receiver Module permissions (Module 18)
-- Owner : module-identity (version range V1100–V1199)
--
-- These are iot: permissions seeded from an identity migration, and that is the established
-- pattern rather than an exception — V1103 seeds every module's permissions, because
-- identity.permissions is identity's table and no other module may write to it. The catalogue is
-- declared once in platform-security's Permissions.java and mirrored here; a constant present in
-- one and not the other is an endpoint that can never be granted to anyone.
--
-- Replay is separated from manage on purpose. Re-injecting a stored packet writes telemetry the
-- device never re-sent, which on a billing meter is authoring a reading. An operator entitled to
-- restart a stalled listener is not thereby entitled to do that.
-- =====================================================================================

INSERT INTO identity.permissions (code, domain, resource, action, description)
VALUES
    ('iot:receiver:read',    'iot', 'receiver', 'read',
        'View receiver status, metrics, packet logs and connected devices'),
    ('iot:receiver:manage',  'iot', 'receiver', 'manage',
        'Start and stop transport listeners and manage the dead-letter queue'),
    ('iot:receiver:replay',  'iot', 'receiver', 'replay',
        'Re-inject stored packets, creating telemetry the device did not re-send');

-- ---- Role grants -----------------------------------------------------------------------------
-- V1103's CROSS JOIN grant to SUPER_ADMIN ran against the permissions that existed then, so it
-- does not cover these. Re-running it for this migration's own rows is the convention V1103
-- documents; without it the platform administrator silently lacks every receiver permission.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM identity.roles r
         CROSS JOIN identity.permissions p
WHERE r.code = 'SUPER_ADMIN'
  AND r.organization_id IS NULL
  AND p.code IN ('iot:receiver:read', 'iot:receiver:manage', 'iot:receiver:replay');

-- The control room watches ingestion; it does not reconfigure it.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM identity.roles r
         JOIN identity.permissions p ON p.code IN ('iot:receiver:read')
WHERE r.code IN ('ORG_ADMIN', 'OPERATOR', 'NETWORK_ENGINEER') AND r.organization_id IS NULL;

-- Managing listeners is a tenant-administrator action. Replay is not granted to any system role
-- by default: it authors telemetry, so it should be an explicit, deliberate grant a customer
-- makes to a named role rather than something that arrives switched on.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM identity.roles r
         JOIN identity.permissions p ON p.code IN ('iot:receiver:manage')
WHERE r.code = 'ORG_ADMIN' AND r.organization_id IS NULL;

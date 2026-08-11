-- =====================================================================================
-- Seed: Device Data Configuration permissions
-- Owner : module-identity (version range V1100–V1199)
--
-- iot: permissions seeded from an identity migration, following V1103, V1107 and V1108:
-- identity.permissions is identity's table and no other module writes to it. The catalogue is
-- declared in platform-security's Permissions.java and mirrored here.
--
-- Why these are not simply iot:device:read / iot:device:manage, which already exist:
--
--   Registering a device says a piece of hardware exists. Configuring its data parameters says
--   what every reading that hardware produces *means* — which unit it is recorded in, which
--   range makes it an alarm, whether it reaches a dashboard or a bill. A commissioning
--   engineer who registers meters all week should not thereby be able to redefine what
--   "volume" means for the fleet, and a data steward who curates parameters has no business
--   provisioning radio credentials. Two jobs, two permissions.
--
-- Read is separate from manage for the reason V1108 gives for the attribute catalogue: reading
-- the parameter list is close to harmless, and changing it reaches data that already exists.
-- Deactivating a parameter drops it from every dashboard, report and alarm rule that reads it,
-- and nothing about the readings themselves changes to announce that it happened.
--
-- Discovery deliberately has no permission of its own. The discovered list is the input to
-- configuration and nothing else — it is read under :read and acted on under :manage — and a
-- third permission would only create a state where someone can see that a device is sending
-- an unknown field and can do nothing about it.
-- =====================================================================================

INSERT INTO identity.permissions (code, domain, resource, action, description)
VALUES
    ('iot:data-config:read',   'iot', 'data-config', 'read',
        'View device data parameters, discovered parameters and raw device payloads'),
    ('iot:data-config:manage', 'iot', 'data-config', 'manage',
        'Define, edit and deactivate device data parameters, changing what every dashboard, alarm and report reads');

-- V1103's CROSS JOIN grant to SUPER_ADMIN ran against the permissions that existed then, so it
-- does not cover these. Re-running it for this migration's own rows is the documented
-- convention; without it the platform administrator silently lacks the whole module.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM identity.roles r
         CROSS JOIN identity.permissions p
WHERE r.code = 'SUPER_ADMIN'
  AND r.organization_id IS NULL
  AND p.code IN ('iot:data-config:read', 'iot:data-config:manage');

-- ---- Who gets what -----------------------------------------------------------------------
-- Manage goes to the tenant administrator only. The brief asks that only administrators change
-- data configuration, and it is expressed as a grant rather than as a role check in code — a
-- utility that invents its own "Instrumentation Engineer" role must be able to grant it without
-- a release, which is why authorisation here is permission-based and never names a role.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM identity.roles r
         JOIN identity.permissions p ON p.code = 'iot:data-config:manage'
WHERE r.code = 'ORG_ADMIN' AND r.organization_id IS NULL;

-- Read goes further, to everyone who has to interpret a reading. A control-room operator
-- looking at a value of 47 needs to know what unit it is in and what range is normal; a network
-- engineer commissioning a device needs to know which parameters it is expected to send before
-- deciding whether a quiet field is a fault. Withholding the definitions from the people
-- reading the data is how a misread unit becomes an incident.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM identity.roles r
         JOIN identity.permissions p ON p.code = 'iot:data-config:read'
WHERE r.code IN ('ORG_ADMIN', 'NETWORK_ENGINEER', 'OPERATOR') AND r.organization_id IS NULL;

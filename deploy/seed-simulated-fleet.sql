-- =====================================================================================
-- Demo fleet for the Module 17 simulator — LOCAL DEVELOPMENT ONLY.
--
-- Not a Flyway migration, and deliberately outside the migration ranges: this is sample
-- data, not schema, and a migration that inserted demo meters would put them in every
-- environment the schema is applied to, including production.
--
-- Registers eight devices with source = 'SIMULATOR' across three transports, because the
-- simulator emits each network's real wire format and a single-transport fleet would leave
-- two of the three payload paths untested: LoRaWAN produces a ChirpStack envelope wrapping
-- a base64 meter frame, while NB-IoT and HTTP produce vendor-spelled JSON documents.
--
-- Two devices declare their own duty cycle via `reportingIntervalSeconds`, so the fleet
-- reports on staggered schedules the way a real estate does rather than in lockstep.
--
-- Idempotent: re-running changes nothing, so it is safe to pipe in twice.
--
--   docker exec -i -e PGPASSWORD=aquagrid aquagrid-postgres-dev \
--     psql -U aquagrid -d aquagrid < deploy/seed-simulated-fleet.sql
--
-- To undo:  DELETE FROM iot.devices WHERE source = 'SIMULATOR' AND device_code LIKE 'WM-%';
-- =====================================================================================

INSERT INTO iot.devices (
    id, organization_id, device_code, network_address, device_type, name,
    transport, source, status, manufacturer, model, installation_date,
    asset_number, location, provisioning, attributes,
    created_at, updated_at, version
)
SELECT
    gen_random_uuid(),
    org.id,
    seed.device_code,
    -- Upper-cased exactly as CommunicationProfile.networkAddressFrom does at registration.
    -- The tenant-unique index must not admit a81b... beside A81B... as two devices.
    upper(seed.network_address),
    'WATER_METER',
    seed.name,
    seed.transport,
    'SIMULATOR',
    'PROVISIONED',
    'Kamstrup',
    'flowIQ 2200',
    DATE '2026-03-14',
    seed.asset_number,
    ST_SetSRID(ST_MakePoint(seed.lon, seed.lat), 4326),
    seed.provisioning::jsonb,
    seed.attributes::jsonb,
    now(), now(), 0
FROM core.organizations org,
     (VALUES
          -- LoRaWAN: addressed by DevEUI, payload is a ChirpStack envelope.
          ('WM-LORA-001', 'A81758FFFE030001', 'LORAWAN', 'Ward 3 household meter',
           'AST-1001', 76.9366, 8.5241, '{"devEui":"A81758FFFE030001"}', '{}'),
          ('WM-LORA-002', 'A81758FFFE030002', 'LORAWAN', 'Ward 3 household meter',
           'AST-1002', 76.9381, 8.5252, '{"devEui":"A81758FFFE030002"}',
           -- A battery meter on a six-hour duty cycle: reports once where the others report often.
           '{"reportingIntervalSeconds":21600}'),
          ('WM-LORA-003', 'A81758FFFE030003', 'LORAWAN', 'Ward 4 household meter',
           'AST-1003', 76.9349, 8.5228, '{"devEui":"A81758FFFE030003"}', '{}'),

          -- NB-IoT: addressed by IMEI, payload is a JSON document with vendor field names.
          ('WM-NB-001', '356938035640001', 'NB_IOT', 'Ward 7 bulk meter',
           'AST-2001', 76.9402, 8.5266, '{"imei":"356938035640001","operator":"Jio"}', '{}'),
          ('WM-NB-002', '356938035640002', 'NB_IOT', 'Ward 7 district meter',
           'AST-2002', 76.9415, 8.5279, '{"imei":"356938035640002","operator":"Jio"}', '{}'),
          ('WM-NB-003', '356938035640003', 'NB_IOT', 'Zone 2 inlet meter',
           'AST-2003', 76.9337, 8.5205, '{"imei":"356938035640003","operator":"Airtel"}',
           -- A mains-powered logger, reporting every five minutes.
           '{"reportingIntervalSeconds":300}'),

          -- HTTP: self-declared device id in the payload.
          ('WM-HTTP-001', 'WM-HTTP-001', 'HTTP', 'Reservoir outlet meter',
           'AST-3001', 76.9290, 8.5310, '{"deviceId":"WM-HTTP-001"}', '{}'),
          ('WM-HTTP-002', 'WM-HTTP-002', 'HTTP', 'Treatment plant feed meter',
           'AST-3002', 76.9275, 8.5327, '{"deviceId":"WM-HTTP-002"}', '{}')
     ) AS seed(device_code, network_address, transport, name, asset_number,
               lon, lat, provisioning, attributes)
WHERE org.code = 'SYSTEM'
  AND NOT EXISTS (
      SELECT 1 FROM iot.devices d
      WHERE d.organization_id = org.id AND d.device_code = seed.device_code
  );

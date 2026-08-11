-- =====================================================================================
-- Module 6 — Device Registration, made communication-independent
-- Owner : module-iot (version range V1400–V1499)
--
-- V1400 made the device row transport-agnostic in its *telemetry* handling, but its identity was
-- still a communication concept: `device_eui` was NOT NULL and doubled as "LoRaWAN devEUI /
-- NB-IoT IMEI / gateway serial". That forces the registration form to ask for a radio identifier
-- before it knows which radio the device uses, and it leaves a NB-IoT device pretending to have
-- an EUI.
--
-- This migration separates the two ideas:
--
--   * `device_code`  — the operator-facing identity. Unique per tenant, communication-independent,
--                      the thing printed on the work order and typed into the field app.
--   * `network_address` — whichever identifier the chosen communication technology uses to address
--                      the device on its network: DevEUI for LoRaWAN, IMEI for NB-IoT and 4G.
--                      Derived from the communication block, never typed as itself. Nullable, so a
--                      device can be registered before its SIM or radio module is allocated.
--
-- Ingestion is unchanged in shape: it still resolves an uplink through one indexed column, which
-- is why the identifier is normalised into `network_address` rather than left in `provisioning`
-- JSONB where every uplink would pay for a JSONB expression index.
--
-- The remaining communication-specific fields (SIM, operator, JoinEUI, AppKey) live in
-- `provisioning`, which V1400 already created for exactly this purpose. AppKey is a root key and
-- is stored as AES-GCM ciphertext via CryptoService; it is never returned by the API.
-- =====================================================================================

-- ---- Identity split -------------------------------------------------------------------------

ALTER TABLE iot.devices RENAME COLUMN device_eui TO network_address;
ALTER INDEX iot.uq_devices_org_eui RENAME TO uq_devices_org_network_address;

ALTER TABLE iot.devices
    ADD COLUMN device_code       VARCHAR(60),
    ADD COLUMN device_type       VARCHAR(40),
    ADD COLUMN manufacturer      VARCHAR(120),
    ADD COLUMN model             VARCHAR(120),
    ADD COLUMN installation_date DATE,
    ADD COLUMN asset_number      VARCHAR(80),
    -- The device's own position. Deliberately not inherited from the linked asset: a meter's
    -- radio unit often sits in a chamber or on a pole metres away from the asset it measures, and
    -- a coverage survey needs where the *antenna* is.
    ADD COLUMN location          geometry(Point, 4326);

-- Backfill before the NOT NULLs. Any device registered under V1400 was keyed by its EUI, so the
-- EUI is the only identity it has ever had — it becomes the device code, and the operator can
-- rename it afterwards.
UPDATE iot.devices SET device_code = network_address WHERE device_code IS NULL;
UPDATE iot.devices SET device_type = 'OTHER' WHERE device_type IS NULL;

ALTER TABLE iot.devices
    ALTER COLUMN device_code SET NOT NULL,
    ALTER COLUMN device_type SET NOT NULL,
    -- A device may now be registered before its radio identity exists.
    ALTER COLUMN network_address DROP NOT NULL;

-- Device code is the tenant-unique operator-facing key.
CREATE UNIQUE INDEX uq_devices_org_code ON iot.devices (organization_id, device_code);

-- Network address stays unique per tenant, but only where present: two devices awaiting SIM
-- allocation are both NULL and must not collide.
DROP INDEX iot.uq_devices_org_network_address;
CREATE UNIQUE INDEX uq_devices_org_network_address
    ON iot.devices (organization_id, network_address)
    WHERE network_address IS NOT NULL;

ALTER TABLE iot.devices
    ADD CONSTRAINT ck_devices_device_type CHECK (device_type IN
        ('WATER_METER', 'BULK_FLOW_METER', 'PRESSURE_SENSOR', 'LEVEL_SENSOR',
         'QUALITY_SENSOR', 'VALVE_CONTROLLER', 'PUMP_CONTROLLER', 'ENERGY_METER',
         'GATEWAY', 'OTHER'));

-- Spatial index: "which devices are in this DMA / this viewport" is the query the fleet map runs.
CREATE INDEX ix_devices_location ON iot.devices USING GIST (location);
CREATE INDEX ix_devices_org_type ON iot.devices (organization_id, device_type);
CREATE INDEX ix_devices_asset_number ON iot.devices (organization_id, asset_number);

COMMENT ON COLUMN iot.devices.device_code IS
    'Operator-facing device identity. Unique per tenant and independent of communication technology.';
COMMENT ON COLUMN iot.devices.network_address IS
    'Network-layer address derived from the communication block: DevEUI (LoRaWAN) or IMEI (NB-IoT, 4G). The column every uplink resolves through.';
COMMENT ON COLUMN iot.devices.asset_number IS
    'Asset code of the linked asset, denormalised. module-iot must not join gis.assets — it is the planned first microservice extraction.';
COMMENT ON COLUMN iot.devices.location IS
    'Where the device itself is, EPSG:4326. May differ from the linked asset''s geometry.';

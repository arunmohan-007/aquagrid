-- =====================================================================================
-- Module 6/18 — IoT devices and the communication layer
-- Owner : module-iot (version range V1400–V1499)
--
-- Devices are physical things that produce readings. The schema is deliberately transport-
-- agnostic: a device knows its identity (devEui / serial), its tenant, its transport binding
-- and its lifecycle, but nothing about how bytes reach the platform. That mapping is the job of
-- the InboundTransportAdapter SPI in Java, not the schema.
--
-- Telemetry is a separate concern (V1500+). This migration creates the device registry only.
-- =====================================================================================

CREATE TABLE iot.devices
(
    id              UUID PRIMARY KEY                  DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL,
    device_eui      VARCHAR(32)  NOT NULL,        -- LoRaWAN devEUI / NB-IoT IMEI / gateway serial
    serial_number   VARCHAR(64),
    name            VARCHAR(200) NOT NULL,
    asset_id        UUID,                          -- link to gis.assets (nullable: a device may exist before installation)
    transport       VARCHAR(20)  NOT NULL,        -- LORAWAN, NB_IOT, CELLULAR, ETHERNET, SIMULATOR
    status          VARCHAR(20)  NOT NULL DEFAULT 'PROVISIONED',
    -- Provisioning: device EUI is globally unique but its meaningfulness is per-tenant, so the
    -- unique constraint is (organization_id, device_eui).
    firmware_version VARCHAR(40),
    -- Last-known radio state, refreshed on every uplink. Powers the device health dashboard.
    battery_v       NUMERIC(5,2),
    rssi            NUMERIC(6,2),
    snr             NUMERIC(5,2),
    last_seen_at    TIMESTAMPTZ,
    -- Vendor-specific provisioning keys (AppKey, NwkKey, DevAddr). Sensitive: AES-GCM ciphertext.
    provisioning    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    attributes      JSONB        NOT NULL DEFAULT '{}'::jsonb,

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by      UUID,
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_devices_organization
        FOREIGN KEY (organization_id) REFERENCES core.organizations (id) ON DELETE RESTRICT,
    CONSTRAINT ck_devices_transport CHECK (transport IN
        ('LORAWAN', 'NB_IOT', 'CELLULAR', 'ETHERNET', 'SIMULATOR', 'HTTP')),
    CONSTRAINT ck_devices_status CHECK (status IN
        ('PROVISIONED', 'ACTIVE', 'INACTIVE', 'DECOMMISSIONED', 'FAULTY'))
);

CREATE UNIQUE INDEX uq_devices_org_eui ON iot.devices (organization_id, device_eui);
CREATE INDEX ix_devices_org_status ON iot.devices (organization_id, status);
CREATE INDEX ix_devices_asset ON iot.devices (asset_id);
CREATE INDEX ix_devices_org_transport ON iot.devices (organization_id, transport);
CREATE INDEX ix_devices_last_seen ON iot.devices (last_seen_at);

CREATE TRIGGER trg_devices_updated_at
    BEFORE UPDATE ON iot.devices
    FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

COMMENT ON TABLE iot.devices IS
    'Device registry. Transport-agnostic: the InboundTransportAdapter SPI maps bytes to DeviceMessage.';
COMMENT ON COLUMN iot.devices.provisioning IS
    'AES-GCM-encrypted vendor provisioning (AppKey, DevAddr). Never plaintext.';

-- ---- Raw telemetry ----------------------------------------------------------------------
-- A plain table for Phase 5. Module 13 converts this into a TimescaleDB hypertable partitioned
-- by observed_at, with continuous aggregates at 15min/1h/1day. The column shape is chosen so the
-- hypertable conversion is a no-op schema change (select_existing_partitions => true).
CREATE TABLE iot.device_readings
(
    id              BIGSERIAL PRIMARY KEY,
    organization_id UUID         NOT NULL,
    device_id       UUID         NOT NULL,
    observed_at     TIMESTAMPTZ  NOT NULL,
    received_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    metric          VARCHAR(60)  NOT NULL,        -- flow_rate, pressure, volume, battery, tamper, ...
    value           DOUBLE PRECISION,
    unit            VARCHAR(20),                   -- L, m3, bar, V, ...
    -- Radio context captured alongside the reading, for quality and coverage analysis.
    rssi            NUMERIC(6,2),
    snr             NUMERIC(5,2),
    battery_v       NUMERIC(5,2),
    f_cnt           INTEGER,
    transport       VARCHAR(20),
    raw_payload     JSONB,
    metadata        JSONB        NOT NULL DEFAULT '{}'::jsonb,

    CONSTRAINT fk_readings_device
        FOREIGN KEY (device_id) REFERENCES iot.devices (id) ON DELETE CASCADE
);

-- The dominant query: "latest readings for this device, this metric, this window". The index
-- serves it in one seek; once this becomes a hypertable, the same index becomes the chunk key.
CREATE INDEX ix_readings_device_metric_time
    ON iot.device_readings (device_id, metric, observed_at DESC);
CREATE INDEX ix_readings_org_time ON iot.device_readings (organization_id, observed_at DESC);

COMMENT ON TABLE iot.device_readings IS
    'Raw telemetry. Converted to a TimescaleDB hypertable in Module 13; schema is hypertable-ready.';

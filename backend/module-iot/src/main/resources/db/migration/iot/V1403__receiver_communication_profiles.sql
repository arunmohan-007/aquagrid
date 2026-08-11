-- =====================================================================================
-- Module 18 — Communication profiles the Receiver Module can address devices on
-- Owner : module-iot (version range V1400–V1499)
--
-- The receiver becomes the single entry point for every inbound packet, which means a device
-- must be registrable on every network the receiver listens on. V1400–V1402 knew five:
-- LORAWAN, NB_IOT, CELLULAR, ETHERNET and HTTP. This adds the four the receiver now terminates
-- itself — MQTT, TCP, UDP and WEBSOCKET — so that `transport` and the set of transports that
-- can actually deliver traffic are the same set.
--
-- It also closes a defect that has been latent since V1402, and it is the same defect V1402 was
-- written to fix.
--
-- V1402 correctly separated `source` (LIVE | SIMULATOR) from `transport`, because "is this data
-- real?" and "which network carries it?" are different questions. It rewrote the old
-- SIMULATOR-transport rows onto transport = 'HTTP', on the stated grounds that HTTP "defines no
-- provisioning fields and no network address, so the rewrite changes no device's reachability".
--
-- That was accurate, and it is precisely the problem. CommunicationProfile.HTTP declared no
-- identity field, so every HTTP device — including every rewritten simulator device — carried a
-- NULL network_address, and network_address is the sole column ingestion resolves an uplink
-- through. The category error was fixed; the unreachability was carried forward intact. It is
-- why AGENTS.md §10 records that marking a device `source = SIMULATOR` "records intent but does
-- not yet cause the simulator to drive it", and why binding the simulator fleet was deferred
-- until this module: a device the receiver cannot resolve cannot be driven by anything.
--
-- HTTP therefore gains a real identity field, `deviceId` — the identifier an HTTP device already
-- puts in its own payload to say who it is — and existing rows are backfilled from their device
-- code so that devices registered before today become resolvable rather than needing re-entry.
-- =====================================================================================

-- ---- Transports the receiver terminates ------------------------------------------------------

ALTER TABLE iot.devices DROP CONSTRAINT ck_devices_transport;
ALTER TABLE iot.devices
    ADD CONSTRAINT ck_devices_transport CHECK (transport IN
        ('LORAWAN', 'NB_IOT', 'CELLULAR', 'ETHERNET', 'HTTP',
         'MQTT', 'TCP', 'UDP', 'WEBSOCKET'));

-- ---- Backfill: make existing HTTP devices addressable ----------------------------------------
--
-- Guarded three ways, because this rewrites the column every uplink resolves through and a bad
-- row here is a device that silently stops receiving telemetry:
--
--   * length(device_code) <= 32 — network_address is VARCHAR(32) while device_code is VARCHAR(60);
--     an unguarded copy would fail the insert, or worse, be silently truncated into a prefix that
--     collides with another device.
--   * NOT EXISTS — uq_devices_org_network_address is unique per tenant across *all* transports, so
--     a device code equal to some other device's DevEUI would violate it.
--   * upper() — the unique index must not admit two casings of one address as two devices, which is
--     the same normalisation CommunicationProfile.networkAddressFrom applies at registration.
--
-- A device that fails a guard keeps its NULL address and stays unresolvable — no worse off than
-- before, and visible: the receiver's unattributed-packet view is where it will show up.

UPDATE iot.devices d
SET network_address = upper(d.device_code),
    provisioning    = d.provisioning || jsonb_build_object('deviceId', upper(d.device_code))
WHERE d.transport = 'HTTP'
  AND d.network_address IS NULL
  AND length(d.device_code) <= 32
  AND d.device_code ~ '^[A-Za-z0-9:_.-]{1,32}$'
  AND NOT EXISTS (SELECT 1
                  FROM iot.devices other
                  WHERE other.organization_id = d.organization_id
                    AND other.id <> d.id
                    AND other.network_address = upper(d.device_code));

COMMENT ON CONSTRAINT ck_devices_transport ON iot.devices IS
    'The networks a device may be registered on. Must stay in step with CommunicationProfile — a constant present in one and not the other is a device that registers and never resolves, or one the receiver accepts and the database refuses.';

-- ---- Lookup paths for the receiver's device-resolution strategies -----------------------------
--
-- network_address covers the primary strategy and is already indexed. These cover the fallbacks,
-- which exist because not every technology addresses a device by the field its profile nominates:
-- a shared MQTT bridge names the meter only in the topic, a TCP logger frames a MAC address, an
-- integration is issued a per-device token.
--
-- Expression indexes over `provisioning`, rather than promoting each to a column. The alternative
-- is a column per identifier kind on the busiest table in the module, almost all NULL, growing
-- every time a technology is added — which is exactly the coupling CommunicationProfile exists to
-- avoid. The cost is that each indexed key must be named here explicitly; an unindexed fallback
-- would be a sequential scan on the ingestion path, so the resolver only offers strategies that
-- have an index behind them.
--
-- Normalised with upper() where the source is hex or a hardware address, matching what
-- CommunicationProfile.networkAddressFrom does at registration: two casings of one identifier must
-- not resolve to two devices, or to none.

CREATE INDEX ix_devices_device_token_hash
    ON iot.devices ((provisioning ->> 'deviceTokenHash'))
    WHERE provisioning ? 'deviceTokenHash';

CREATE INDEX ix_devices_mac_address
    ON iot.devices ((upper(provisioning ->> 'macAddress')))
    WHERE provisioning ? 'macAddress';

CREATE INDEX ix_devices_client_id
    ON iot.devices ((provisioning ->> 'clientId'))
    WHERE provisioning ? 'clientId';

CREATE INDEX ix_devices_unit_id
    ON iot.devices ((upper(provisioning ->> 'unitId')))
    WHERE provisioning ? 'unitId';

-- Serial numbers are printed on the device and are what a field technician reads out, so they are
-- a resolution path of last resort — and were previously unindexed.
CREATE INDEX ix_devices_org_serial ON iot.devices (organization_id, upper(serial_number))
    WHERE serial_number IS NOT NULL;

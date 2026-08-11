-- =====================================================================================
-- Module 6 — Device Protocol, and Postman/API-test as a Device Source
-- Owner : module-iot (version range V1400–V1499)
--
-- HTTP sat on `transport` beside LoRaWAN and NB-IoT, which put a category error in the schema:
-- those name a *network*, and HTTP names the *ingress protocol* a packet arrives on. The two are
-- independent — ChirpStack delivers LoRaWAN over HTTP, and a cellular modem may push over MQTT.
-- V1403 already documented the distinction for raw telemetry (`connection_mode`); this brings the
-- same axis onto the device row so registration can ask it.
--
-- Existing HTTP-transport rows become transport = 'ETHERNET', protocol = 'HTTP'. ETHERNET is the
-- honest network for a device that has no radio identity of its own; the network_address V1403
-- backfilled from device_code is left alone so those devices stay resolvable. Protocol defaults
-- to HTTP for every other row — that is how the platform's HTTP-family receivers already terminate
-- LoRaWAN, NB-IoT and cellular webhooks.
--
-- `source` gains API_TEST for traffic an operator injects from Postman or another client during
-- integration testing. Distinct from LIVE (a field device) and SIMULATOR (Module 17), so a water
-- balance can exclude it and the fleet driver does not claim it.
-- =====================================================================================

ALTER TABLE iot.devices
    ADD COLUMN protocol VARCHAR(10) NOT NULL DEFAULT 'HTTP';

-- Move HTTP off the network axis before the transport CHECK drops it.
UPDATE iot.devices
SET transport = 'ETHERNET',
    protocol  = 'HTTP'
WHERE transport = 'HTTP';

ALTER TABLE iot.devices DROP CONSTRAINT ck_devices_transport;
ALTER TABLE iot.devices
    ADD CONSTRAINT ck_devices_transport CHECK (transport IN
        ('LORAWAN', 'NB_IOT', 'CELLULAR', 'ETHERNET',
         'MQTT', 'TCP', 'UDP', 'WEBSOCKET'));

ALTER TABLE iot.devices
    ADD CONSTRAINT ck_devices_protocol CHECK (protocol IN ('HTTP', 'MQTT'));

ALTER TABLE iot.devices DROP CONSTRAINT ck_devices_source;
ALTER TABLE iot.devices
    ADD CONSTRAINT ck_devices_source CHECK (source IN ('LIVE', 'SIMULATOR', 'API_TEST'));

COMMENT ON COLUMN iot.devices.protocol IS
    'How this device''s telemetry reaches the platform: HTTP (webhook / REST push) or MQTT. Independent of transport, which names the network.';
COMMENT ON COLUMN iot.devices.transport IS
    'The network technology the device communicates over. Names a radio or wire only — see protocol for the ingress bearer, and source for whether the traffic is real.';
COMMENT ON COLUMN iot.devices.source IS
    'Where this device''s telemetry originates: LIVE (field device), SIMULATOR (Module 17), or API_TEST (Postman / manual API injection). Independent of transport and protocol.';
COMMENT ON CONSTRAINT ck_devices_transport ON iot.devices IS
    'The networks a device may be registered on. Must stay in step with CommunicationProfile — a constant present in one and not the other is a device that registers and never resolves, or one the receiver accepts and the database refuses. HTTP is not a network; it lives on protocol.';

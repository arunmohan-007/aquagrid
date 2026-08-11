# Module 18 — Communication Layer (Phase 5)

The transport-agnostic ingestion tier. The brief's hard requirement — *"the software must work
even if the communication technology changes"* — is satisfied here by construction, not convention.

---

## 1. The hexagonal ingestion port

```
LoRaWAN ─► ChirpStackLorawanAdapter ─┐
NB-IoT  ─► NbiotAdapter ─────────────┤
MQTT    ─► MqttAdapter ──────────────┼─► TelemetryIngestPort ─► DeviceMessage ─► telemetry, alarms, GIS
HTTP    ─► HttpIngestController ─────┤     (the ONLY thing          (canonical,
Sim     ─► SimulatorAdapter (P6) ────┘      business code sees)       protocol-free)
```

- `DeviceMessage` is the canonical, protocol-free model. Identity, timing, a metrics map keyed by
  canonical names, and radio-quality context. The only shape above the port.
- `TelemetryIngestPort.ingest(DeviceMessage)` is the single entry point business code calls.
- `InboundTransportAdapter` is the SPI each transport implements: receive bytes → decode → ingest.
- `IngestResult` is sealed (`Accepted | Rejected | Duplicate`) so the compiler forces every adapter
  to handle the QoS-ack decision correctly.

**Adding LTE-M in 2027 = adding one class. Zero changes to metering, billing, alarms, NRW or GIS.**

## 2. Adapters delivered

| Transport | Adapter | Activation | Notes |
|---|---|---|---|
| HTTP | `HttpIngestController` | `aquagrid.iot.transports.http.enabled` | Cellular modems, REST bridges, simulator. Decodes JSON directly. |
| LoRaWAN | `ChirpStackLorawanAdapter` | `…lorawan.enabled` | ChirpStack HTTP integration. Byte-level codec for the smart-water-meter payload (volume LE dL, flow, battery dV, status flags). |
| NB-IoT | `NbiotAdapter` | `…nbiot.enabled` | Carrier webhook. Metric-name normalisation (vendor names → canonical). |
| MQTT | `MqttAdapter` | `…mqtt.enabled` | Broker subscriber seam; Paho wiring lands in Module 18 deepening. `onMessage` is the handler, unchanged. |

Every adapter is `@ConditionalOnProperty`-gated. A deployment that does not use a transport compiles
its beans away — no route, no socket, no listener.

## 3. Ingest service

`TelemetryIngestService` implements the port:
1. **Resolve device** by `network_address` across tenants (ingestion is unauthenticated; tenant is
   pinned from the resolved row). Unknown → `Rejected`, never an exception — a rogue device on the
   wire is operation. The wire field is still called `deviceEui` because that is what gateways call
   it; it is matched against whichever identifier the device's technology addresses it by.
2. **Dedup** by `(deviceId, fCnt)` or content hash. LoRaWAN replay and NB-IoT retransmission produce
   duplicates that must be acked without double-counting.
3. **Persist** one `DeviceReading` per metric, with canonical units.
4. **Refresh** the device's last-known radio state (battery, RSSI, SNR, lastSeen) so the health
   dashboard is current without a telemetry scan.
5. **Audit** the uplink.

## 4. Database (`V1400__iot_devices.sql`, `V1401__device_registration.sql`)

- `iot.devices` — registry, communication-independent. Identity is `device_code`, unique per tenant
  and unrelated to any radio. `network_address` holds whichever identifier the chosen technology
  addresses the device by — DevEUI for LoRaWAN, IMEI for NB-IoT and 4G — derived from the
  communication block rather than entered on its own, and nullable so a device can be registered
  before its SIM is allocated (the unique index is partial for that reason).
- `provisioning` holds the remaining communication-specific fields. Secrets are prefixed `secret:`
  and stored as AES-GCM ciphertext via `CryptoService`; the API never returns them, only which keys
  are set.
- `CommunicationProfile` is the single declaration of what each technology needs. It drives
  validation, storage and the `GET /devices/communication-types` catalogue the registration form
  renders from — so the form cannot ask for a field the server rejects.
- `iot.device_readings` — raw telemetry, `BIGSERIAL`, hypertable-ready (Module 13 converts to
  TimescaleDB; column shape chosen so conversion is a no-op).
- Index `(device_id, metric, observed_at DESC)` serves the dominant analytical query in one seek.

## 5. Independence, enforced

- **Compile-time:** `module-iot` depends on `platform-common` only — not on `module-identity` or
  `module-gis`. Cross-module data travels through the published `IdentityApi` and domain events.
- **Deployment:** each transport is a Spring profile/property; inactive transports contribute nothing.
- **Future extraction:** IoT ingestion is the planned first microservice (scales with device count).
  Moving it behind HTTP changes only the `TelemetryIngestPort` implementation to a client.

## 6. Out of scope

- MQTT Paho live subscriber (Module 18 deepening — the `onMessage` seam is in place)
- Device-level authn (API key / mTLS on ingest endpoints)
- Batch ingest via COPY (Module 13 hypertable ingester)
- Downlink command path (`OutboundCommandPort`, Module 6 deepening)

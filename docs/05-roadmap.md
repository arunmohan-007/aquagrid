# 5. Development Roadmap

35 modules, sequenced so that every phase ends with a **demonstrable, shippable increment** and no
module is started before its dependencies exist. Each module is delivered as a full vertical slice:
migration → domain → service → API → OpenAPI → UI → tests → docs.

---

## Phase 0 — Foundation *(delivered with Module 1)*
Parent POM, kernel (`platform-common`), security primitives (`platform-security`), Flyway baseline,
Docker Compose (PostGIS), CI skeleton, React/Vite/MUI/Tailwind shell, theming, error contract.

## Phase 1 — Identity & Tenancy
| # | Module | Delivers |
|---|---|---|
| **1** | **Authentication** | Login, MFA (TOTP), refresh rotation + reuse detection, lockout, password reset/change, sessions, JWKS |
| 2 | User & Role Management | User CRUD, role/permission administration, invitations, OIDC/SAML federation hooks |
| 3 | Organization Management | Tenant hierarchy, sites, branding, licensing, service-area boundary |

## Phase 2 — Spatial Core
| # | Module | Delivers |
|---|---|---|
| 4 | GIS Dashboard | OpenLayers shell, base maps (OSM/Satellite), layer tree, legend, bookmarks, length & area measure, draw, coordinate/address search, print/export, `ST_AsMVT` tile endpoint |
| 23 | Asset Management | `gis.assets` supertype, lifecycle, attachments, attribute bag, bulk import (Shapefile/GeoJSON/CSV) |
| 11 | Pipeline Network Management | Pipes, topology build, pgRouting tracing, network analysis |
| 12 | Valve Management | Valves, isolation-valve tracing, operate/close-out workflow |

## Phase 3 — Physical Assets
| # | Module |
|---|---|
| 8 | Tank Management |
| 9 | Reservoir Management |
| 10 | Pump Station Management |
| 5 | Customer Management (service connections tied to network nodes) |

## Phase 4 — IoT Backbone
| # | Module | Delivers |
|---|---|---|
| 18 | **Communication Layer** | `InboundTransportAdapter`/`OutboundCommandPort`, canonical `DeviceMessage`, codec registry, MQTT + ChirpStack + NB-IoT + HTTP adapters |
| 6 | Device Management | Registry, provisioning, keys, health, battery, RSSI/SNR, firmware version, online/offline, remote config |
| 17 | Device Simulator | Fleet simulation with realistic diurnal demand curves, leaks, burst events, comms loss, battery decay |
| 7 | Smart Water Meter Management | Meter lifecycle, meter↔customer↔connection binding, index rollover, tamper/reverse-flow flags |

## Phase 5 — Real-Time Operations
| # | Module |
|---|---|
| 13 | Pressure Monitoring (TimescaleDB hypertables, continuous aggregates) |
| 14 | Flow Monitoring |
| 15 | Water Quality Monitoring (pH, turbidity, residual chlorine, TDS, compliance thresholds) |
| 19 | Alarm Management (rule engine, severity, dedupe, escalation, ack/clear, shelving) |
| 20 | Notification Center (email/SMS/push/webhook, per-user routing, quiet hours) |
| 16 | SCADA Integration (OPC UA / Modbus TCP, tag mapping, setpoint write-back with interlocks) |

## Phase 6 — Field Operations
| # | Module |
|---|---|
| 21 | Work Orders (creation from alarms, assignment, SLA, mobile close-out with geotagged photos) |
| 22 | Maintenance Management (preventive schedules, meter reading rounds, spares, downtime) |
| 33 | Mobile Responsive UI / field PWA (offline queue, background sync) |

## Phase 7 — Intelligence
| # | Module | Delivers |
|---|---|---|
| 26 | Water Balance Analysis | IWA standard water balance |
| 27 | NRW Analysis | DMA-level NRW %, minimum night flow, apparent vs real losses |
| 25 | AI-based Leak Detection | MNF anomaly detection, pressure-transient burst detection, acoustic correlation, ML scoring pipeline with drift monitoring |
| 28 | Consumption Analytics | Profiles, seasonality, anomalies, forecasting, tariff simulation |
| 24 | Analytics | Cross-domain query/aggregation service and dashboard builder |

## Phase 8 — Enterprise Hardening
| # | Module |
|---|---|
| 29 | Reports (scheduled PDF/XLSX, templates, regulatory formats, distribution lists) |
| 30 | Audit Logs (partitioning, retention, tamper-evident hash chain, export) |
| 31 | System Monitoring (Actuator/Prometheus/Grafana, ingest lag, broker health, SLOs) |
| 32 | Backup & Restore (PITR, `pg_dump` + object storage, tested restore runbook) |
| 34 | Settings (tenant + system configuration, feature flags, tariff/unit preferences) |
| 35 | API Documentation (published OpenAPI portal, generated TS/Java clients, API keys, versioning policy) |

---

## Delivery contract per module

Every module ships:
1. Flyway migration inside its reserved version range
2. Domain model with invariants + unit tests (no Spring)
3. Application services with transaction and authorisation boundaries
4. REST controllers, DTOs, validation, OpenAPI annotations
5. Integration tests on real PostGIS via Testcontainers
6. Frontend feature slice: typed API client, hooks, pages, routes, permissions, dark mode
7. `docs/modules/NN-<name>.md` — design record and API reference

## Definition of Done
`ddl-auto` never leaves `validate` · no `@Transactional` on controllers · no entity crosses the
web boundary · every endpoint has an explicit `@PreAuthorize` · every mutation raises an audit
event · every list endpoint is paginated and sorted · every new UI surface works in light **and**
dark mode at 360 px width · CI green including dependency and image scans.

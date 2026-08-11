# 2. Technology Justification

Every choice below is justified against the alternatives we rejected. Where the brief fixed a
technology, we justify *how* it is used rather than whether.

---

## 2.1 Backend

### Java 21 (LTS)
* **Virtual threads (JEP 444)** — a water platform is overwhelmingly I/O-bound (DB, MQTT, GeoServer,
  SCADA). Virtual threads give reactive-grade concurrency with blocking, debuggable, stack-traced
  code. We get Netty-like throughput without WebFlux's cognitive tax.
* **Records + sealed types + pattern matching** — DTOs and domain events become immutable one-liners;
  `sealed interface IngestResult permits Accepted, Rejected, Duplicate` makes the compiler enforce
  exhaustive handling of protocol outcomes.
* LTS through 2031 — matters for a product a municipality will run for a decade.

*Rejected:* Node/NestJS (no mature spatial ORM, weak long-running numeric workloads),
Go (thin GIS/JPA ecosystem, no MapStruct/JPA equivalent), .NET (excellent, but PostGIS +
GeoServer + ChirpStack tooling is JVM-native).

### Spring Boot 3.4 (Jakarta EE 10)
Not chosen for familiarity — chosen because it is the only stack that ships **all** of these as
first-class, mutually-integrated concerns: declarative transactions, JPA, method security, OAuth2
resource server, scheduling, caching abstraction, Actuator/Micrometer, MQTT/AMQP integration,
testcontainers support, and native-image readiness. Each of those is a module in our roadmap.

### Spring Security 6 + RS256 JWT + JWKS
* **Asymmetric (RS256), not HMAC.** With HS256 every future microservice needs the *signing* secret
  and can therefore mint tokens. With RS256 the private key never leaves the identity module and
  every other service validates offline against `/.well-known/jwks.json`. This is the single
  decision that makes microservice extraction cheap, and it costs nothing today.
* **Permission-scoped tokens.** `roles[]` + `perms[]` claims. Permission sets in this domain are
  bounded (<200 codes), so authorisation stays fully stateless.
* **15-minute access token + rotating refresh token.** Short TTL bounds the revocation window;
  rotation + reuse detection converts a stolen refresh token from a persistent backdoor into a
  detectable, self-revoking incident.

*Rejected:* Keycloak/Auth0 as the system of record. A utility platform sold to government bodies
must support air-gapped, on-premise deployment and per-tenant password/MFA policy. We instead keep
our own IdP **and** expose standard OIDC/SAML federation in Module 2 for customers who already have
Azure AD or Keycloak.

### Spring Data JPA + Hibernate 6 (+ Hibernate Spatial)
* Hibernate Spatial maps PostGIS `geometry` directly to JTS `Point`/`LineString`/`Polygon` — no
  WKT string juggling, no custom types.
* Hibernate 6 filters give us the tenant-isolation safety net described in §1.4.
* **Where JPA is the wrong tool we do not use it.** Telemetry ingestion, NRW aggregation and
  vector-tile generation use `JdbcTemplate`/native SQL with batch inserts and `COPY`. ORM for the
  transactional model, SQL for the analytical model. Pretending JPA suits both is how these
  platforms die at 50M rows.

### MapStruct + Lombok
Compile-time, zero-reflection mapping. Adding a field to an entity and forgetting the DTO becomes a
**build failure**, not a silent null in production. Reflection-based mappers (ModelMapper) are
rejected: they fail at runtime and cost measurably at telemetry volumes.

### Flyway
Versioned, ordered, checksummed, repeatable-in-CI migrations. **Version ranges are reserved per
module** (see §3), which lets modules ship migrations independently while keeping one linear
timeline — a prerequisite for later extraction.

### springdoc-openapi
OpenAPI 3.1 generated from the actual controllers and Bean Validation constraints, so documentation
cannot drift. The spec is a build artifact used to generate the typed frontend client and to gate
breaking-change detection in CI.

---

## 2.2 Database

### PostgreSQL 16 + PostGIS 3.4
The only realistic choice. PostGIS is the reference open geospatial engine: `ST_Intersects`,
`ST_DWithin`, network tracing via `pgRouting`, `ST_AsMVT` for vector tiles **generated in the
database**, GiST/SP-GiST spatial indexes, and topology support for pipeline connectivity.

Commercial equivalents (Oracle Spatial, SQL Server geography) cost six figures per socket and are
strictly weaker for raster + topology + MVT. ESRI's file geodatabase is not a transactional store.

### TimescaleDB (from Module 13)
Meter and sensor telemetry is classic time-series: append-heavy, queried by
`(device_id, time-range)`, aggregated to 15-min/hourly/daily rollups, and retained for years.
TimescaleDB adds hypertable partitioning, native compression (10–20×) and continuous aggregates
**inside PostgreSQL** — so a leak-detection query can join a compressed hypertable to a PostGIS
pipeline geometry in one SQL statement. Adopting InfluxDB or Cassandra would force every analytical
query to be a cross-store join in application code.

### Extensions used
`postgis`, `postgis_topology`, `pgrouting`, `timescaledb`, `pgcrypto`, `citext`, `pg_trgm`,
`btree_gist`, `uuid-ossp` (superseded by native `gen_random_uuid()`).

---

## 2.3 GIS

### GeoServer
Serves WMS/WFS/WMTS/vector tiles from the same PostGIS instance. Gives us OGC-standard interop —
non-negotiable when a municipality must consume the network in QGIS or publish to a state GIS
portal. Style changes (SLD) become configuration, not deployments.

### OpenLayers 10 (not Leaflet, not Mapbox GL JS)
* **Native projection support.** Indian utilities work in EPSG:32643/32644 (UTM WGS84) and state
  grids; Leaflet is effectively Web-Mercator-only and Mapbox GL requires reprojection gymnastics.
* **Native WMS/WFS/WMTS clients** — Leaflet needs a plugin per protocol, each with its own decay rate.
* Built-in measure, draw, modify, snap, and `ol/interaction` primitives we need for network editing.
* Vector tiles + WebGL points renderer handle 100k+ meters without clustering artefacts.
* Mapbox GL JS licence terms and mandatory-token model are unacceptable for on-premise/air-gapped
  government deployments.

### Vector tiles (`ST_AsMVT`) over GeoJSON
> Directly informed by a prior production incident on a sibling platform: a 12.5 MB GeoJSON payload
> per map open made the app unusable on a constrained office network. That mistake is designed out
> from day one.

Tiles are generated in PostGIS, cached by zoom/x/y, and fetched per viewport — payload becomes
O(viewport), not O(network size). Bulk GeoJSON is reserved for exports.

---

## 2.4 IoT & Communication

| Technology | Role | Why |
|---|---|---|
| **MQTT 5** | Primary telemetry bus | Built for constrained links; QoS 1/2, retained state, shared subscriptions for horizontal scaling, LWT for instant offline detection |
| **ChirpStack** | LoRaWAN network server | Open-source, self-hostable (mandatory for on-prem), publishes uplinks to MQTT — so LoRaWAN reaches us through the same port as everything else |
| **NB-IoT / LTE-M** | Battery meters, wide coverage | Carrier-grade coverage where LoRa gateways are uneconomic; UDP/CoAP or MQTT-SN adapter |
| **4G/Cellular** | Pump stations, DMA loggers, SCADA gateways | Bandwidth for high-rate sensors and firmware push |
| **OPC UA / Modbus TCP** | SCADA integration (Module 16) | The actual protocols of pump/treatment PLCs |
| **WebSocket (STOMP)** | Browser push | Live tank levels, alarms, device status without polling |

All of them terminate at `InboundTransportAdapter` and never appear above it (§1.3).

---

## 2.5 Frontend

| Choice | Justification |
|---|---|
| **React 19** | Actions/`useTransition`/`useOptimistic` remove hand-rolled pending state; Suspense streaming suits dashboards that must paint before telemetry arrives. Largest hiring pool of the credible options. |
| **TypeScript (strict)** | The API contract is generated from OpenAPI into TS types; a backend field rename breaks the build, not the customer's dashboard. |
| **Vite** | Sub-second HMR on a codebase this size; Rollup production builds with genuine code-splitting per module — the GIS bundle (OpenLayers is heavy) is lazy-loaded and never taxes the login screen. |
| **MUI v6 + Tailwind** | MUI supplies accessible, dense, enterprise components (DataGrid, Autocomplete, Dialog) and a real theming/dark-mode system. Tailwind handles layout and one-off composition without a parallel CSS-in-JS file per component. MUI is configured to emit CSS variables so both systems share one source of truth for colour — no conflict. |
| **React Query (TanStack v5)** | Server state ≠ client state. Caching, deduping, background refetch, polling intervals per widget, and optimistic updates are exactly a SCADA dashboard's requirements. Redux is not used: there is very little genuine client state, and what exists (theme, map view, filters) lives in Context/Zustand. |
| **React Hook Form + Zod** | Uncontrolled inputs = no re-render per keystroke, which matters on the 60-field asset forms in Modules 7–12. Zod schemas are shared with the generated API types. |
| **ECharts (primary) + Chart.js** | ECharts handles 100k-point time series with downsampling, dataZoom, and dual-axis pressure/flow overlays — Chart.js does not. Chart.js remains for lightweight KPI sparklines. |
| **Axios** | Interceptor model is the cleanest place for the silent-refresh queue, correlation-id injection and 401 handling. |

---

## 2.6 Infrastructure

* **Docker + Compose** for development parity and single-node customer installs; **Helm/K8s**
  manifests for cloud SKUs. Same image, different orchestration.
* **Multi-stage, distroless, non-root images.** Layer-cached Maven build, JRE-only runtime.
* **Testcontainers** — every persistence test runs against real PostGIS. Mocking a spatial database
  tests nothing.
* **GitHub Actions**: build → unit → integration (Testcontainers) → OWASP dependency-check →
  Trivy image scan → SBOM (CycloneDX) → publish. Supply-chain evidence is a procurement requirement
  for government tenders.

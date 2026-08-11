# AquaGrid — Enterprise Smart Water Management Platform
## 1. Overall Software Architecture

**Product codename:** AquaGrid EWMP
**Maven groupId:** `com.aquagrid`
**Root package:** `com.aquagrid.platform`

---

### 1.1 Architectural Style: Microservice-Ready Modular Monolith

We build **one deployable unit composed of independently-compiled Maven modules**, each of which
owns its domain, its database schema and its migrations, and communicates with other modules only
through published interfaces (Java APIs today, HTTP/AMQP tomorrow).

```
                         ┌──────────────────────────────────────────────┐
                         │              app-bootstrap                   │
                         │  Spring Boot runtime, wiring, observability   │
                         └──────────────────────────────────────────────┘
                                            ▲ depends on
        ┌──────────────┬──────────────┬─────┴────────┬──────────────┬──────────────┐
        │              │              │              │              │              │
 module-identity  module-org     module-gis     module-iot     module-ops   module-analytics
   auth, users,   organisations,  spatial       devices,       work orders, NRW, leak
   roles, perms   sites, zones    assets, tiles comm. layer    maintenance  detection
        │              │              │              │              │              │
        └──────────────┴──────────────┴──────┬───────┴──────────────┴──────────────┘
                                             │ depends on
                    ┌────────────────────────┴─────────────────────────┐
                    │   platform-security      platform-messaging      │
                    │   JWT, JWKS, authz       events, outbox, MQTT    │
                    └────────────────────────┬─────────────────────────┘
                                             │
                    ┌────────────────────────┴─────────────────────────┐
                    │                 platform-common                  │
                    │  base entities, tenancy, errors, crypto, audit   │
                    └──────────────────────────────────────────────────┘
```

**Dependency rule (enforced by Maven, not by convention):** arrows point downward only.
`module-iot` **cannot** compile against `module-identity` internals — the compiler rejects it,
because `module-identity` exposes only its `*.api` package as a published contract and
`module-iot` does not declare a dependency on it. Cross-module data access happens through
`...api` service interfaces and domain events, never through another module's repositories.

#### Why not microservices on day one?

| Concern | Microservices from day 1 | Modular monolith (chosen) |
|---|---|---|
| Domain boundaries | Frozen before they are understood; wrong cuts cost months | Refactorable at compile time |
| Spatial joins (`pipe ∩ zone`, `meter → nearest valve`) | Cross-service, N+1 over the network, no ACID | Single PostGIS query, transactional |
| Operational cost | K8s, service mesh, distributed tracing, 12 pipelines on day 1 | One JAR, one DB, one pipeline |
| Latency of SCADA/telemetry ingest | Network hop per hop | In-process |
| Extraction later | — | Module already has its own schema, events and API surface: lift-and-shift |

Water utility domains are **densely spatially joined**. Distributing them prematurely is the single
most common failure mode in this product category. We therefore keep the *seams* of microservices
(schema-per-module, event-driven integration, stateless JWT authz, no shared entities) while
retaining monolithic deployment until a module has a proven independent scaling profile.

**Planned first extractions** (already designed for): `module-iot` ingestion (scales with device
count, not user count) and `module-analytics` (CPU-bound batch). Both already communicate only via
events + the JWKS-validated token, so extraction is a deployment change, not a rewrite.

---

### 1.2 Clean Architecture Inside Each Module

Every business module has the same four-layer internal structure:

```
module-<name>/src/main/java/com/aquagrid/platform/<name>/
├── api/                 ← PUBLISHED. Other modules may import ONLY this.
│   ├── dto/             ← immutable records crossing the module boundary
│   ├── event/           ← domain events other modules subscribe to
│   └── *Api.java        ← service interfaces
├── domain/              ← enterprise rules. NO Spring, NO JPA annotations leak outward.
│   ├── model/           ← entities + value objects + invariants
│   ├── enums/
│   └── policy/          ← pure business policies (e.g. PasswordPolicy, LockoutPolicy)
├── application/         ← use cases. Orchestration, transactions, authorisation.
│   ├── service/
│   ├── command/         ← input models
│   └── mapper/          ← MapStruct
├── infrastructure/      ← replaceable detail
│   ├── persistence/     ← Spring Data repositories, JPA mappings
│   ├── client/          ← outbound adapters (SMTP, MQTT, SCADA, GeoServer)
│   └── config/
└── web/                 ← inbound adapter
    ├── controller/
    └── dto/             ← request/response records, validation annotations
```

**Dependency direction:** `web → application → domain`, `infrastructure → domain`.
The domain layer depends on *nothing* but the JDK and `platform-common` value types. This is what
makes the business rules testable in milliseconds without Spring, PostgreSQL or a broker.

---

### 1.3 The Communication-Independence Principle (critical requirement)

The brief demands: *"the software must work even if the communication technology changes."*

We satisfy this with a **hexagonal ingestion port**. Business logic never learns whether a reading
arrived over NB-IoT, LoRaWAN, 4G, MQTT, HTTP or a CSV upload.

```
  LoRaWAN ─► ChirpStackAdapter ─┐
  NB-IoT  ─► UdpCoapAdapter ────┤
  4G/MQTT ─► MqttAdapter ───────┼─► TelemetryDecoder ─► DeviceMessage ─► TelemetryIngestPort
  REST    ─► HttpIngestAdapter ─┤     (per-vendor          (canonical,      (the ONLY thing
  SCADA   ─► OpcUaAdapter ──────┤      codec plugin)        protocol-free)   business code sees)
  Sim     ─► SimulatorAdapter ──┘
```

* `DeviceMessage` is the canonical model: `deviceEui`, `observedAt`, `receivedAt`, `metrics{}`,
  `rssi`, `snr`, `batteryV`, `fCnt`, `rawPayload`, `transport`.
* Adding LTE-M in 2027 = adding one class implementing `InboundTransportAdapter`. Zero changes to
  metering, billing, alarms, NRW or GIS.
* Downlinks/OTA use the mirror-image `OutboundCommandPort`, with per-transport capability
  descriptors (`supportsDownlink`, `maxPayloadBytes`, `dutyCycleLimited`).

Adapters are activated by Spring profiles/properties (`aquagrid.iot.transports.lorawan.enabled`),
so a municipality running only NB-IoT never loads an MQTT client.

---

### 1.4 Multi-Tenancy Model

**Discriminator-based, shared schema, defence in depth.**

1. Every tenant-owned table carries `organization_id UUID NOT NULL`.
2. The JWT carries `org` (organization id). `TenantContext` (ThreadLocal, cleared by filter)
   holds it for the request.
3. A **Hibernate filter** (`@FilterDef("tenantFilter")`) is enabled on every session, so
   `SELECT` statements are rewritten with `organization_id = :tenantId` automatically —
   a developer who forgets the predicate still cannot leak data.
4. PostgreSQL **Row-Level Security** policies are provisioned as a second, database-enforced net
   for high-sensitivity tables (billing, audit).

Rejected: schema-per-tenant (thousands of Flyway timelines, connection-pool explosion) and
database-per-tenant (uneconomic below very large tenants). Both remain reachable for a dedicated
"sovereign deployment" SKU because all tenant access already funnels through `TenantContext`.

---

### 1.5 Cross-Cutting Concerns

| Concern | Implementation |
|---|---|
| **AuthN** | Stateless RS256 JWT (15 min) + rotating opaque refresh token in `HttpOnly` cookie, reuse-detection with family revocation. JWKS endpoint so extracted services validate offline. |
| **AuthZ** | Permission-based (`asset:pipeline:update`), not role-based, at the method level via `@PreAuthorize`. Roles are bundles of permissions; customers redefine roles without a code change. |
| **Errors** | RFC 7807 `ProblemDetail` everywhere, one `@RestControllerAdvice`, stable machine-readable `code`, `traceId` on every response. |
| **Validation** | Jakarta Bean Validation at the web edge; domain invariants re-asserted in entity constructors/factories. |
| **Logging** | Structured JSON (logstash encoder) in prod, MDC carries `traceId`, `userId`, `orgId`. Secrets masked. |
| **Config** | 12-factor. `application.yml` + profile overlays + env vars. Typed `@ConfigurationProperties` records with `@Validated` — the app refuses to start on bad config. |
| **Caching** | Caffeine (L1, in-process) now; Redis (L2, shared) added in Module 31 without touching call sites, because everything goes through Spring `@Cacheable` abstractions. |
| **Async** | `@Async` on a bounded `ThreadPoolTaskExecutor` for I/O side-effects (audit, email, webhook); virtual threads enabled for the servlet layer (Java 21). Long-running work → Spring Events + outbox, never fire-and-forget. |
| **Observability** | Micrometer → Prometheus, OpenTelemetry traces, Spring Boot Actuator with a locked-down management port. |
| **Persistence** | Flyway only. `ddl-auto: validate`. Hibernate never touches DDL, in any environment. |

---

### 1.6 Runtime Topology (production)

```
              ┌──────────────┐
   Internet ──│  Nginx / ALB │── TLS termination, HSTS, rate limit, WAF
              └──────┬───────┘
         ┌───────────┼────────────┬─────────────────┐
         ▼           ▼            ▼                 ▼
   React SPA    AquaGrid API  GeoServer        MQTT Broker
   (static)     (N replicas)  (WMS/WFS/MVT)    (EMQX/Mosquitto)
                     │             │                 │
                     ├─────────────┴─────────────────┤
                     ▼                               ▼
         PostgreSQL 16 + PostGIS 3.4        ChirpStack (LoRaWAN NS)
         + TimescaleDB (telemetry hypertables)
                     │
                     ▼
              Object storage (S3/MinIO) — reports, attachments, firmware, backups
```

Stateless API replicas: no HTTP session, no sticky routing. WebSocket fan-out for the live
dashboard uses a broker relay so it scales horizontally.

---

### 1.7 Security Posture (baseline, applied from Module 1)

* Argon2/BCrypt(12) password hashing via `DelegatingPasswordEncoder` — hash upgrades are automatic.
* Access token in **memory only** on the SPA (never `localStorage`) → XSS cannot exfiltrate it.
* Refresh token in `HttpOnly; Secure; SameSite=Strict` cookie scoped to `/api/v1/auth` → JS cannot read it.
* **Trusted-proxy-aware client IP resolution.** `X-Forwarded-For` is honoured *only* when the peer
  is inside a configured trusted CIDR, and parsed right-to-left. A spoofed header cannot evade
  lockout or rate limiting.
* Per-identifier and per-IP login throttling + progressive account lockout.
* Refresh-token reuse detection with whole-family revocation and a `SECURITY_ALERT` audit event.
* TOTP MFA with AES-GCM-encrypted secrets and single-use recovery codes.
* Every authentication decision written to an immutable audit trail.
* CSP, HSTS, `X-Content-Type-Options`, `Referrer-Policy`, no `Server` banner.
* Strict CORS allowlist; no wildcard with credentials.

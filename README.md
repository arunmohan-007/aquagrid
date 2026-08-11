# AquaGrid — Enterprise Smart Water Management Platform

A microservice-ready modular monolith for municipalities, water authorities, smart cities,
industry and government: GIS asset management, IoT telemetry, SCADA integration, alarms,
work orders and NRW analytics on one PostGIS-backed platform.

**Status: Phases 1–7 delivered.** Identity (Module 1), User & Role Management (Module 2), GIS
dashboard with vector tiles (Module 4), the transport-agnostic IoT communication layer (Module 18)
with the device simulator (Module 17), and production hardening with CI/CD.

---

## Documentation

| Document | Contents |
|---|---|
| [`docs/installation.md`](docs/installation.md) | **What to install, in what order** — the plain-English setup guide |
| [`docs/01-architecture.md`](docs/01-architecture.md) | Architecture, module boundaries, communication independence, multi-tenancy, security posture |
| [`docs/02-technology-justification.md`](docs/02-technology-justification.md) | Every technology choice and the alternatives rejected |
| [`docs/03-folder-structure.md`](docs/03-folder-structure.md) | Repository layout, Flyway version ranges, database schemas |
| [`docs/04-database-design.md`](docs/04-database-design.md) | Global principles, kernel and identity schema, forward-looking design |
| [`docs/05-roadmap.md`](docs/05-roadmap.md) | All 35 modules, sequenced into eight phases |
| [`docs/modules/00-phase1-audit.md`](docs/modules/00-phase1-audit.md) | Phase 1 — architecture & database audit, seams consumed by later phases |
| [`docs/modules/01-authentication.md`](docs/modules/01-authentication.md) | Module 1 design record and API reference |
| [`docs/modules/02-user-management.md`](docs/modules/02-user-management.md) | Module 2 — user & role management design record and API reference |
| [`docs/modules/04-gis.md`](docs/modules/04-gis.md) | Module 4 — GIS dashboard, vector tiles and OpenLayers |
| [`docs/modules/08-10-tank-reservoir-pump.md`](docs/modules/08-10-tank-reservoir-pump.md) | Modules 8–10 — tank, reservoir, pump station type tables |
| [`docs/modules/11-pipeline-network.md`](docs/modules/11-pipeline-network.md) | Module 11 — pipeline network, pgRouting topology & tracing |
| [`docs/modules/12-valve-management.md`](docs/modules/12-valve-management.md) | Module 12 — valves, isolation tracing, operate workflow |
| [`docs/modules/17-device-simulator.md`](docs/modules/17-device-simulator.md) | Module 17 — device fleet simulator |
| [`docs/modules/17-device-simulator.md`](docs/modules/17-device-simulator.md) | Module 17 — device fleet simulator |
| [`docs/modules/18-communication-layer.md`](docs/modules/18-communication-layer.md) | Module 18 — transport-agnostic IoT ingestion |
| [`docs/modules/31-deployment-hardening.md`](docs/modules/31-deployment-hardening.md) | Phase 7 — Docker, CI/CD, production hardening |

---

## Layout

```
backend/
├── platform-common/     kernel: base entities, tenancy, error contract, crypto, audit
├── platform-security/   JWT issuance/validation, JWKS, authorisation, rate limiting
├── module-identity/     Modules 1–2 — authentication, users, roles, permissions, invitations
├── module-gis/          Module 4 — spatial assets, vector tiles (ST_AsMVT), layers
├── module-iot/          Modules 6/17/18 — devices, transport-agnostic ingestion, simulator
└── app-bootstrap/       the deployable Spring Boot application
frontend/                React 19 + TypeScript + Vite + MUI + Tailwind
deploy/                  Docker Compose, nginx, PostgreSQL init
.github/workflows/       CI/CD: build → test → OWASP → Trivy → SBOM
docs/                    architecture and per-module design records
```

---

## Running locally

### Prerequisites
Java 21 · Maven 3.9+ · Node 20+ · Docker (for PostGIS)

### 1. Database

```bash
docker run -d --name aquagrid-db -p 5432:5432 -e POSTGRES_DB=aquagrid -e POSTGRES_USER=aquagrid -e POSTGRES_PASSWORD=aquagrid postgis/postgis:16-3.4
```

### 2. Backend

```bash
cd backend && mvn clean install && mvn -pl app-bootstrap spring-boot:run
```

Flyway creates the schema on first start, and `BootstrapAdminInitializer` creates the first
administrator. The `local` profile sets a development password
(`Aquagrid#Local2026`) — override it with `AQUAGRID_BOOTSTRAP_ADMIN_PASSWORD`. The account
is created with `must_change_password`, so the first sign-in forces a new one.

API: <http://localhost:8080> · OpenAPI UI: <http://localhost:8080/swagger-ui.html>

### 3. Frontend

```bash
cd frontend && npm install && npm run dev
```

<http://localhost:5173> — Vite proxies `/api` to the backend, keeping the browser
same-origin so the `SameSite=Strict` refresh cookie behaves exactly as it does in
production.

### 4. Tests

```bash
cd backend && mvn verify
```

Integration tests run against a real PostGIS container via Testcontainers, so Docker must
be available.

---

## Deploying

```bash
cp .env.example deploy/.env    # then fill in every required value
```

```bash
cd deploy && docker compose --profile web up -d --build
```

The application **refuses to start** outside a development profile if the JWT signing key
is missing, the refresh cookie is not `Secure`, `SameSite` is `None`, or `app-base-url` is
not HTTPS. These are boot-time assertions rather than warnings: each one is a
vulnerability that produces no symptoms until it is exploited.

---

## Module 1 — what is implemented

**Backend**

- Password sign-in by email, or by username plus organisation code
- TOTP multi-factor (RFC 6238) with QR enrolment and single-use recovery codes
- RS256 access tokens (15 min) with roles and permissions as claims; JWKS endpoint
- Opaque refresh tokens: SHA-256 hashed at rest, rotated on every use, with reuse
  detection that revokes the whole token family and raises a `CRITICAL` audit event
- Refresh token delivered as `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`
- Progressive account lockout plus per-IP and per-identifier token-bucket rate limiting
- Trusted-proxy-aware client IP resolution — a spoofed `X-Forwarded-For` cannot evade
  lockout, rate limiting or the audit trail
- Password policy (NIST SP 800-63B aligned), history, change, and a forgotten-password
  flow that cannot be used to enumerate accounts
- Active device sessions: list, revoke one, revoke all
- Every authentication decision written to an append-only audit trail
- RFC 7807 error contract with stable codes and a trace id on every response

**Frontend**

- Sign-in, MFA challenge, forgotten password, reset, forced password change, security and
  sessions self-service
- Access token in memory only; silent bootstrap from the refresh cookie on load;
  single-flight refresh so concurrent 401s cannot trigger false reuse detection
- Light and dark mode with no flash of incorrect theme, responsive to 360 px, full
  keyboard path, `aria-live` status regions, reduced-motion support

---

## Next

**Module 2 — User & Role Management.** User CRUD and invitations, role and permission
administration, and OIDC/SAML federation for customers already running Azure AD or
Keycloak.

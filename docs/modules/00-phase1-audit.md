# Phase 1 — Architecture & Database Audit

> Record of the state of the platform's architecture and database design at the start of the
> seven-phase delivery cycle. Confirms what Phase 0 / Module 1 already established and lists the
> forward-looking decisions that Phases 2–7 build on without revisiting.

---

## 1. Verdict

**Phase 0 (Foundation) and the architecture/database deliverables of the seven-phase plan are
complete.** The five design documents, the kernel schema, the identity schema and the seed data are
all in place and consistent with one another. There is no architecture or database work to redo in
Phase 1; the task is to *confirm* the foundations and document the seams the later phases use.

| Concern | Status | Evidence |
|---|---|---|
| Architecture record | ✅ Done | `docs/01-architecture.md` — modular monolith, clean layers, comm-independence, multi-tenancy, security posture |
| Technology justification | ✅ Done | `docs/02-technology-justification.md` — every choice justified against rejected alternatives |
| Folder structure & Flyway ranges | ✅ Done | `docs/03-folder-structure.md` — reserved version range per module, schema-per-context |
| Database design | ✅ Done | `docs/04-database-design.md` — global principles, kernel + identity schema, forward design |
| Roadmap | ✅ Done | `docs/05-roadmap.md` — 35 modules sequenced into eight phases with a Definition of Done |
| Kernel schema (`V1000–V1002`) | ✅ Done | extensions, ten schemas, `core.organizations`, `audit.audit_events`, helper triggers |
| Identity schema (`V1100–V1103`) | ✅ Done | permissions, roles, users, tokens, MFA, login attempts + seed data |
| Permission catalogue | ✅ Done | seeded for identity, org, gis, iot, ops, analytics, admin — **all codes Phases 2–7 need already exist** |
| System roles | ✅ Done | SUPER_ADMIN, ORG_ADMIN, NETWORK_ENGINEER, GIS_ANALYST, OPERATOR, FIELD_TECHNICIAN, VIEWER |
| `Permissions` constants | ✅ Done | `platform-security` exposes every code as a compile-time constant |

---

## 2. Seams the later phases consume (no redesign needed)

### 2.1 The published identity contract
`com.aquagrid.platform.identity.api.IdentityApi` is the **only** surface another module may import.
GIS/IoT/ops modules resolve "who is user X" through `IdentityApi.findUser(...)`, never by joining to
`identity.users`. This is what keeps the identity module extractable. Phase 2 (User Management)
extends this interface with `listUsers`, `findUsersByOrg`, etc., without breaking the existing
two methods.

### 2.2 Permission codes are pre-seeded
The migration `V1103` already inserts the `gis:*`, `iot:*`, `ops:*`, `analytics:*`, `admin:*`
permissions that Phases 4–7 gate endpoints on. No later migration needs to `INSERT` permission rows;
it only needs to *grant* them to roles. `Permissions.java` already holds the matching constants.

### 2.3 Flyway version ranges are reserved
Each phase's migrations slot into a pre-reserved range with no collision risk:

| Phase | Module | Range |
|---|---|---|
| 2 | User & Role Management | `V1104–V1199` (identity, extends Module 1's range) |
| 4 | GIS dashboard + assets | `V1300–V1399` |
| 5 | IoT devices & comm layer | `V1400–V1499` |
| 5 | Telemetry hypertables | `V1500–V1599` |
| 6 | (simulator adds no schema of its own — writes through the IoT port) |

### 2.4 Database schemas already created
`V1000` created all ten schemas (`core`, `identity`, `audit`, `org`, `gis`, `iot`, `ts`, `ops`,
`billing`, `analytics`). Later migrations `CREATE TABLE` directly — no schema-creation step.

### 2.5 Cross-cutting infrastructure already wired
From `platform-common`: `TenantContext` + `TenantFilterAspect`, RFC 7807 `GlobalExceptionHandler`,
`AuditService`, `CryptoService` (AES-GCM), `CorrelationIdFilter`, `ClientIpResolver`,
`PageResponse`, `BaseEntity`/`AuditableEntity`/`TenantAwareEntity`. From `platform-security`:
RS256 JWT + JWKS, `@PreAuthorize` method security, `RateLimiter`. **No phase needs to build any of
this.**

---

## 3. Forward-looking decisions already locked (do not revisit)

These were decided in Phase 0 because retrofitting them later would touch every module:

1. **UUID v4 PKs** everywhere except append-only log/telemetry tables (`BIGSERIAL`).
2. **`organization_id` first column** of every tenant-owned table and every composite index.
3. **`geometry(<Type>, 4326)` + GiST index** on every spatial table; analytical length/distance uses
   `geography` or an explicit UTM transform, never degrees.
4. **VARCHAR + CHECK** for enumerations, never `ENUM` types or ordinals.
5. **`TIMESTAMPTZ` UTC** always; local rendering is a presentation concern.
6. **Soft delete is opt-in**, not default — only assets, work orders and customers get `deleted_at`.
7. **Every table** carries `created_at`, `created_by`, `updated_at`, `updated_by`, `version`.
8. **`ddl-auto: validate`** — Hibernate never touches DDL; Flyway is the only source of truth.
9. **Communication independence**: business logic sees only `DeviceMessage` + `TelemetryIngestPort`;
   transport adapters are activated by Spring profile. Adding LTE-M in 2027 = adding one class.
10. **Stateless RS256 JWT + JWKS** so any module can be extracted to a service that validates
    offline against `/.well-known/jwks.json`.

---

## 4. Phase 1 exit criteria — all met

- [x] Architecture documented and internally consistent across all five docs
- [x] Database designed for every bounded context, principles stated, schemas created
- [x] Permission vocabulary complete for all 35 modules
- [x] System roles and the platform-operator tenant seeded
- [x] Migration version ranges reserved with no overlap
- [x] Cross-cutting infrastructure (tenancy, errors, audit, crypto, security) implemented and reusable

**Phase 1 is complete. Proceeding to Phase 2 (User & Role Management backend).**

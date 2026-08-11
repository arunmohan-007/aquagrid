# AquaGrid — agent orientation

Read this before touching code. It describes what the software is, where things live, how to run
and verify it, and the invariants that are load-bearing. Where it disagrees with `README.md` or
`docs/`, **this file is correct** — the discrepancies are listed in §9.

---

## 1. What this software is

An enterprise smart-water-management platform for municipalities and water authorities: GIS asset
management, IoT telemetry ingestion, alarms, work orders and non-revenue-water analytics over
PostGIS.

Architecturally it is a **microservice-ready modular monolith**. One deployable Spring Boot
application, but each module owns its own Java package, database schema and Flyway version range,
and communicates with other modules through published `api` packages rather than by reaching into
their internals. `module-iot` is the planned first extraction, which is why it never joins to
`gis.assets` even where a join would be convenient.

Domain vocabulary: a **device** is the hardware that reports readings; an **asset** is the pipe,
valve, tank or meter it is fitted to; a **transport** / communication type is the network a device
speaks on; a **device source** is whether its telemetry is real or simulated.

---

## 2. Stack

| Layer | Choice |
|---|---|
| Backend | Java 21, Spring Boot 3.4.5, Maven multi-module |
| Persistence | PostgreSQL 16 + PostGIS 3.4, Hibernate Spatial, JTS, Flyway |
| Frontend | React 19, TypeScript 5.7, Vite 6, MUI 7, Tailwind 3, TanStack Query 5 |
| Maps | **MapLibre GL** (not OpenLayers), server-side vector tiles via `ST_AsMVT` |
| Auth | RS256 JWT access tokens + opaque rotating refresh tokens, TOTP MFA |
| Tests | JUnit 5, AssertJ, Testcontainers (real PostGIS — Docker required) |

---

## 3. Layout

```
backend/
├── platform-common/     kernel — base entities, tenancy, RFC 7807 errors, crypto, audit
├── platform-security/   JWT issue/validate, JWKS, Permissions catalogue, rate limiting
├── module-identity/     Modules 1–3 — auth, users, roles, permissions, invitations
├── module-gis/          Module 4 — spatial assets, layers, vector tiles, network tracing
├── module-iot/          Modules 6/17/18 — device registry, ingestion, fleet simulator,
│                        and `dataconfig/` — the device data-parameter catalogue (§11)
└── app-bootstrap/       the ONLY executable; wires modules, owns runtime config
frontend/src/
├── app/                 providers, theme (light+dark), router
├── lib/                 api/httpClient.ts (axios + silent refresh), auth/ (in-memory tokens)
├── layouts/             AuthLayout, AppShell
└── features/<module>/   api/ · hooks/ · pages/ · components/ · types.ts · labels.ts
deploy/                  Docker Compose, nginx, Postgres init
docs/                    architecture + per-module design records
```

Each backend module follows `api/` (published to other modules) · `domain/model` ·
`application/service` · `infrastructure/persistence|config` · `web/controller|dto`.

The frontend is **feature-sliced**: everything for one business module lives under
`features/<name>/`. There is no shared `src/components/` directory.

---

## 4. Running it

Ports are a common source of confusion — these are the real ones:

| Service | Port |
|---|---|
| Backend API (local profile) | **8088** |
| Frontend dev server | **5173** |
| PostgreSQL (local profile, host) | **5433** |
| GeoServer, if running | 8080 — **not AquaGrid** |

`application.yml` defaults `SERVER_PORT` to 8080, but `application-local.yml` overrides it to 8088
and Vite proxies `/api` to 8088. Local development always means 8088.

```bash
cd backend && ./mvnw.cmd -o install -DskipTests && ./mvnw.cmd -pl app-bootstrap spring-boot:run -Dspring-boot.run.profiles=local
```

**The `install` is not optional after touching any module other than `app-bootstrap`.**
`-pl app-bootstrap` resolves `module-gis`, `module-identity` and the rest from your local
repository, not from source, so the app boots against whatever was last installed. New code and —
much more quietly — new Flyway migrations are simply absent: startup succeeds and the only symptom
is a log line reading `Successfully validated 26 migrations` when the tree contains more. Check that
number against `find . -name "*.sql" -path "*migration*" | wc -l` whenever a change "isn't showing".

`package` and `verify` do **not** fix this — neither installs. Nor does adding `-am`: with
`spring-boot:run` that runs the goal on every reactor module including the parent aggregator, which
has no main class, so the build fails with "Unable to find a suitable main class".

```bash
cd frontend && npm install && npm run dev
```

Flyway builds the schema on first start; `BootstrapAdminInitializer` creates the first admin
(`local` profile password `Aquagrid#Local2026`, forced change on first sign-in). Swagger UI at
`/swagger-ui.html`.

Do not start dev servers with a bare `mvn`/`npm` call inside an agent session if the harness offers
a managed preview — two servers on one port is the usual cause of "my change isn't showing".

---

## 5. Verifying changes

```bash
cd backend && ./mvnw.cmd -o verify
```

Full suite is currently **229 tests, green** — 109 unit, 120 integration. Integration tests (`*IT`) run against a real PostGIS
container, so Docker must be up. Failsafe is bound in the parent POM — without it `*IT` classes are
silently skipped and the build goes green having tested nothing.

Single integration test:

```bash
cd backend && ./mvnw.cmd -o verify -pl app-bootstrap -am -Dit.test=DeviceRegistrationIT -Dfailsafe.failIfNoSpecifiedTests=false -Dtest=skip -Dsurefire.failIfNoSpecifiedTests=false
```

```bash
cd frontend && npx tsc --noEmit && npm run lint
```

`npm test` runs Vitest, but **there are currently no frontend test files**. Type-checking, lint and
the production build are the frontend gates. The lint config (`eslint.config.js`) is deliberately
narrow — hooks rules, unused vars, `no-console`, `react/no-array-index-key` — because
`--max-warnings 0` makes every enabled rule a build failure, and a build that fails over quote
style trains people to bypass the build.

When you fix a bug, prove the test catches it: revert the fix, watch the new test fail, restore it.
A regression test that has never failed is not yet a regression test.

---

## 6. Invariants — do not break these

**Multi-tenancy.** Every query is scoped by `organizationId`, taken from the authenticated
principal, never from a request parameter. A missing tenant filter is a data-leak bug, not a
performance bug.

**Module boundaries.** No cross-module joins or repository imports. `module-iot` denormalises
`asset_number` rather than joining `gis.assets`, deliberately. Cross-module reads go through the
published `api` package (e.g. `IdentityApi.findTenantByCode`).

**Received data is never discarded.** Device data configuration decides how a reading is *used* —
its unit, its plausible range, whether it reaches a dashboard, an alarm or a report. It never
decides whether the reading is *accepted*. A parameter nobody configured is stored with quality
`UNKNOWN` and listed for discovery; a value outside its configured range is stored and marked
`OUT_OF_RANGE`. There is no code path in `dataconfig` that drops a value on the strength of a
configuration row, and adding one would break the module's only real promise — see §11.

**A layer is not an asset type.** `gis.assets.layer_id` (V1332) says which registry layer a feature
belongs to; `asset_type` says which physical bucket and which typed detail table it dispatches to.
The two were interchangeable until Layer Management, and treating them as still interchangeable is
how two layers over one asset type end up drawing each other's features — the case
`LayerRepository`'s own javadoc predicted. The column is nullable and stays so: a tenant provisioned
after the layer-seeding migrations has no layer rows, so readers resolve a layer's features as "rows
claiming this layer, plus unclaimed rows of its asset type". Drop that second half and every
pre-V1332 row disappears from the map.

**Flyway ranges are reserved per module.** `V1000–1099` core · `1100–1199` identity ·
`1200–1299` org/zones · `1300–1399` GIS · `1400–1499` devices · `1500–1599` telemetry ·
`1600–1699` alarms · `1700–1799` work orders · `1800–1899` billing · `1900–1999` analytics.
Never edit an applied migration; add a new one in your module's range.

**Schemas:** `core` `identity` `audit` `org` `gis` `iot` `ts` `ops` `billing` `analytics`.

**Secrets are write-only.** Values like a LoRaWAN AppKey go in AES-GCM encrypted via
`CryptoService` under a `secret:` prefix in the `provisioning` JSONB, and are never returned — the
API exposes only *which* secrets are set. An absent secret on update means "keep it", never "clear
it".

**Security state on failure paths must commit.** `BusinessException` is a `RuntimeException`, so
Spring's default rollback rule would discard the lockout counter, the token-family revocation and
the `login_attempts` forensic row that failure paths write *before* throwing. `login`,
`completeMfaChallenge`, `refresh` and `RefreshTokenService.rotate` are therefore annotated
`@Transactional(noRollbackFor = BusinessException.class)`. Removing that silently disables account
lockout and refresh-token reuse containment. Scope it to `BusinessException` — other exceptions are
unknown state and must still roll back.

**Production boot assertions.** Outside a dev profile the app *refuses to start* if the JWT signing
key is missing, the refresh cookie is not `Secure`, `SameSite` is `None`, `app-base-url` is not
HTTPS, or the simulator is enabled. These are assertions, not warnings — do not downgrade them.

**Permissions are declared once**, in `platform-security` `Permissions.java`, seeded by
`V1103__seed_permissions_and_roles.sql`, and enforced with `@PreAuthorize("hasAuthority(...)")`.
Format: `<domain>:<resource>:<action>`, e.g. `iot:device:read`, `gis:asset:create`.

**Nullable list filters must be cast** in JPQL: `cast(:status as string) IS NULL OR ...`. The
datasource runs `stringtype=unspecified`, so an untyped null parameter fails to plan and the
most common call — the unfiltered list — errors instead of returning everything.

The cast in the guard is not enough on its own: **cast the parameter at every position**, including
inside `concat`. `concat` is variadic over `"any"` and so gives Postgres nothing to infer from, and
each occurrence of `:search` is a separate parameter. `concat('%', :search, '%')` therefore fails
with "could not determine data type of parameter $5" for every *non-empty* search while the
unfiltered list works — the mirror image of the failure above, and it hid in
`AssetRepository.findForTenant` until a test finally passed a search term. Write
`concat('%', cast(:search as text), '%')`.

---

## 7. Design conventions

**Model orthogonal concepts as separate axes.** If an enum value answers a different question from
its siblings, it does not belong in that enum. Worked example: `SIMULATOR` used to be a
`CommunicationProfile` beside `LORAWAN` and `NB_IOT`. But "which network?" and "is this data real?"
are two questions, and because the simulator profile declared no identity field, every device
registered that way got a `NULL network_address` — the sole column ingestion resolves through — and
was unreachable by construction. It is now `DeviceSource` (`LIVE` | `SIMULATOR`), independent of
transport, so a simulated meter emulates a real network and is addressed on it.

**Server-driven forms.** The device registration form renders its transport-specific fields from
`GET /devices/communication-types`, served from the same enum the server validates against. Do not
hard-code a parallel field table in the client.

**No `switch` on transport** anywhere in the registration path. Adding a technology should be one
enum constant in `CommunicationProfile`.

**Field definitions are data, not code.** The layers' attributes live in
`gis.layer_attribute_master` (Data Management, `V1330`–`V1331`), and import, export and the dynamic
forms to come read them through `LayerMetadataApi`. Adding a field to a layer is an INSERT, not a
release — so do not reintroduce a table of field constants in `BulkImportService` or a
`TARGET_FIELDS` array in the client; both existed and both were removed for the usual reason, that
the two copies disagreed. New attributes land as keys in the `gis.assets.attributes` JSONB bag,
never as new columns — the application does not issue DDL at runtime and should not be given the
rights to. Deactivation is the module's only delete, and it removes nothing.

**Comments explain *why*, at the decision site.** This codebase's comments carry rationale and
consequences, not restatements of the code. Match that register — a change without its reasoning
will read as noise next to the surrounding code.

**Colour discipline (frontend).** Saturated red, amber and orange are reserved for alarm severity.
Module and brand accents come from the blue→cyan→teal "aqua" system only.

---

## 8. Where the interesting logic lives

| Concern | File |
|---|---|
| Login, MFA, lockout, refresh | `module-identity/.../application/service/AuthenticationService.java` |
| Token rotation & reuse detection | `module-identity/.../application/service/RefreshTokenService.java` |
| Device registration & validation | `module-iot/.../application/service/DeviceManagementService.java` |
| Transport field catalogue | `module-iot/.../domain/model/CommunicationProfile.java` |
| Real vs simulated telemetry | `module-iot/.../domain/model/DeviceSource.java` |
| Metric labels, units, categories | `module-iot/.../domain/model/MetricCatalog.java` |
| Parameter catalogue (writes, history) | `module-iot/.../dataconfig/application/service/DeviceDataConfigService.java` |
| Template + override resolution, cached | `module-iot/.../dataconfig/application/service/ParameterResolver.java` |
| Unknown-parameter discovery | `module-iot/.../dataconfig/application/service/ParameterDiscoveryService.java` |
| Complete raw payload retention | `module-iot/.../dataconfig/application/service/RawTelemetryService.java` |
| Reading quality verdicts | `module-iot/.../dataconfig/domain/model/QualityStatus.java` |
| Readings by device, grouped | `module-iot/.../application/service/DeviceTelemetryService.java` |
| Reading exports (Excel/PDF) | `module-iot/.../application/service/ReadingExportService.java` |
| Fleet simulator + cutover | `module-iot/.../simulator/DeviceSimulator.java` |
| Simulated wire formats | `module-iot/.../simulator/UplinkEncoder.java` |
| Uplink → device resolution | `module-iot/.../application/service/TelemetryIngestService.java` |
| Attribute catalogue (Data Management) | `module-gis/.../application/service/LayerMetadataService.java` |
| Layer registry (Layer Management) | `module-gis/.../application/service/LayerManagementService.java` |
| Style writes + field validation | `module-gis/.../application/service/LayerStyleService.java` |
| Stored style → MapLibre expressions | `module-gis/.../domain/style/MapLibreStyleComposer.java` |
| Symbology vocabulary | `module-gis/.../domain/style/SymbolKeys.java` |
| Tile projection for styled fields | `module-gis/.../infrastructure/persistence/LayerTileRepository.java` |
| Tile address validation (z/x/y) | `module-gis/.../domain/tile/TileCoordinate.java` |
| Server-side tile filtering | `module-gis/.../domain/tile/TileFilter.java` |
| Tile extent, buffer, cache lifetimes | `module-gis/.../infrastructure/config/GisTileProperties.java` |
| Field-name rules & reserved words | `module-gis/.../domain/metadata/FieldNamePolicy.java` |
| Type coercion for imported values | `module-gis/.../domain/enums/AttributeDataType.java` |
| Where an attribute's value lives | `module-gis/.../domain/metadata/AttributeBinder.java` |
| Catalogue-driven asset export | `module-gis/.../application/service/AssetExportService.java` |
| Permission catalogue | `platform-security/.../core/Permissions.java` |
| Error contract (RFC 7807) | `platform-common/.../error/` |
| Frontend auth + silent refresh | `frontend/src/lib/api/httpClient.ts`, `frontend/src/lib/auth/` |
| Module registry / navigation | `frontend/src/features/home/modules.tsx` |

---

## 9. Known drift — verify before trusting

- `README.md` says the API is on **8080** and Postgres on **5432**. The local profile uses **8088**
  and **5433**.
- `README.md` ends with "Next: Module 2", but Modules 1–4, 6, 17 and 18 are delivered.
- `docs/03-folder-structure.md` predates `module-gis` and `module-iot` and does not list them.
- `docs/03-folder-structure.md` says the GIS feature "owns OpenLayers". It uses **MapLibre GL**.
- `docs/03-folder-structure.md` shows a shared `frontend/src/components/` directory. It does not
  exist.
- `frontend/src/features/gis/layerStyle.ts` reads as a palette and is no longer one. It is a
  registry filled at runtime from `GET /gis/map-style`; the hard-coded colour table it used to hold
  was removed when layers became creatable at runtime. Adding a colour back to it will be ignored by
  the map and will only make the legend lie.
- The launcher at `frontend/src/features/launcher/ModuleLauncher.tsx` keeps a **second, private
  copy** of the module list, separate from `features/home/modules.tsx` (which is documented as the
  single source of truth but only feeds the breadcrumb). The two have drifted before — a module
  appearing "missing" from the home screen usually means a stale `soon: true` flag in the launcher's
  copy.

---

## 10. The simulator, and replacing it with real devices

`DeviceSimulator` (Module 17) drives **registered** devices whose `source` is `SIMULATOR`. It
builds no fleet of its own; it reads `DeviceRepository.findBySourceAndOrganizationId`.

The load-bearing constraint is that a simulated device must be replaceable by a physical one with
no change to anything else — not the receiver, ingestion, GIS, analytics, dashboards, alarms, work
orders, the API, the schema or business logic. That is achieved by *using* those things rather than
imitating them. The simulator shares the device registry, device and asset ids, communication
profiles, provisioning, credentials, receiver pipeline, payload formats and database tables with
live traffic.

Concretely:

- It emits through `ReceiverGateway`, never `TelemetryIngestPort`. A simulated packet is
  authenticated, resolved, validated, replay-checked and logged exactly as a real one, and **can be
  rejected** — which is how a registration fault is found before real meters are commissioned.
- It presents the device's **own** credentials — `secret:hmacKey` if provisioned, otherwise the
  configured gateway API key (`aquagrid.iot.simulator.gateway-api-key`), checked by the same
  authenticators against the same hashes the physical gateway will face. There is deliberately no
  simulator authentication scheme.
- It emits each transport's real wire format: a ChirpStack envelope for LoRaWAN, a raw meter frame
  for TCP/UDP, a vendor-spelled JSON document otherwise. `WaterMeterFrameCodec.encode` is the
  counterpart of `decode` and lives beside it.
- **Nothing on the packet says "simulated."** The sole record is `DeviceSource` on the device row.

Cutover is: register (or reuse) the row → set `source` to `LIVE` → configure communications →
silence the virtual twin (`POST /simulator/devices/{id}/suspend`, though the source change alone
releases it at the next fleet refresh) → the physical device reports through the same row. Fleets
convert a few meters at a time; simulated and live devices run side by side indefinitely.

Devices may declare their own duty cycle with a `reportingIntervalSeconds` device attribute.

Activation is `aquagrid.iot.transports.simulator=true`, off by default; `ProductionReadinessVerifier`
refuses to boot a non-development profile with it on.

---

## 11. Device Data Configuration

Device registration says a device exists. Telemetry says what it reported. This module says what
those reports **mean** — and, more importantly, guarantees that everything a device sends is kept
whether anyone has said what it means or not.

The rule, which every part of the module is shaped by:

> **Configuration determines how data is used, not whether data is allowed in.**

A device in the ground sends what its firmware sends; the packet cannot be re-requested. Refusing
one because it carried a field nobody catalogued would discard measurements in order to enforce a
table an administrator has simply not filled in yet. So:

- **`iot.device_raw_telemetry`** (V1406) stores the complete original payload of *every* packet as
  JSONB — accepted, duplicate or rejected — never modified. It is written by a `ReceptionObserver`
  rather than a pipeline stage, because a stage only runs if every stage before it let the packet
  through, and a payload refused for an unregistered device is the one somebody most needs to read.
  This is not `receiver_packet_logs`: that stores BYTEA, only for rejected packets by default, and
  cannot be asked which payloads carry a `powerFactor`.
- **`iot.device_data_parameter`** (V1405) is the catalogue, at two scopes in one table —
  `DEVICE_TYPE` (the template every device of that type inherits) and `DEVICE` (one device's
  override, which replaces the template entry entire). Resolution and its per-tenant cache live in
  `ParameterResolver`; the cache is invalidated by the writer, never expired on a timer.
- **`iot.device_discovered_parameter`** is the queue that makes retention *actionable*. An
  unconfigured parameter is on no dashboard and in no report, so without this it is
  indistinguishable from a field the device never sent. Upserted per sighting, not appended.
  `IGNORED` deletes nothing.
- **`iot.device_readings.quality`** (V1406) carries the verdict: `VALID` · `INVALID` ·
  `OUT_OF_RANGE` · `MISSING` · `UNKNOWN`. Stamped in `TelemetryIngestService`, which is where the
  row is written and which also serves the older adapter path — judging in two places would risk
  the two verdicts disagreeing.

Units are rows in `iot.unit_master`, not a Java enum, and are served to the client alongside the
data types and categories. The form hard-codes none of the three.

The simulator uses the catalogue as its model for everything the water-meter physics does not cover
(`ConfiguredParameterComposer`), and `POST /simulator/extra-test-parameters` makes it emit
deliberately *unconfigured* fields so the discovery path can be exercised end to end. The composer
never writes over a key the meter model already produced — matched on the **canonicalised** name, so
a tenant cataloguing its cell as `battery` rather than `battery_voltage` cannot have a percentage
range overwrite a 3.6 V reading and silence the fleet through `MetricSanityValidator`. That is a
real regression that happened once; `SimulatorIT.dataConfigurationExtendsTheModelWithoutDisplacingIt`
is the test that now catches it.

Permissions are `iot:data-config:read` / `:manage` (V1109), separate from `iot:device:manage`:
registering hardware and defining the semantics of everything it reports are different jobs.

---

## 12. GIS Management — layers and styles

Two modules over one row. `gis.layers` is the registry: Layer Management owns it, Data Management
hangs `gis.layer_attribute_master` off its primary key, Layer Style Management hangs
`gis.layer_style` off it. None of the three duplicates the others, and the seam is `gis.layers.id`.

**Creating a layer issues no DDL.** The brief asked for a PostGIS table per layer; the platform
declined the equivalent for attributes in V1330 and declines it here for the same reasons — runtime
`CREATE TABLE` means the web tier holding DDL rights on its own schema, and a per-layer table would
be invisible to the tile endpoint, the importer, the exporter and the register until each was
generalised to find it. A layer is a row backed by the `gis.assets` supertype, which already
provides geometry(4326), the generated Web-Mercator column, both GiST indexes and the GIN-indexed
attribute bag. `geometry_type` and `srid` are declarations checked on write, not column types, which
is what lets them be corrected later; `feature_table` and `geometry_column` are recorded rather than
assumed and are never interpolated into SQL.

**Withdrawal never deletes.** `LayerStatus` is `ACTIVE` · `INACTIVE` · `ARCHIVED`, all reversible,
none touching a row of `gis.assets`. System layers — the ones the dashboard and the network trace
name by asset type — cannot be archived; they can be disabled.

**Styles are data, and the server compiles them.** A stored style is AquaGrid's symbology
vocabulary (`SymbolKeys`), not MapLibre paint; `MapLibreStyleComposer` is the single translator, and
both `GET /gis/map-style` and the editor's preview go through it — two compilers of the same rules
is two chances for the preview to lie. Attribute-based styling needs the styled field *in the tile*,
so `LayerTileRepository` builds its projection from the catalogue; that is the one place in the
module where an identifier reaches SQL as text, and it is re-validated against `FieldNamePolicy` at
the point of use.

**Templates must be savable exactly as they arrive.** `StyleTemplates` seeds a new style with a
complete symbol rather than a blank form. The property that makes them trustworthy is that every one
of them passes `LayerStyleService`'s validation unedited — `LayerManagementIT` asserts it per
template — which is why the labelling template ships with labels *off* and a `labelField` for the
client to resolve: labels enabled with no field is the one combination the service refuses outright.
A template never fills in a field the layer's catalogue does not have.

**Every field a style names comes from Data Management.** Label field, classification field, every
rule's field — validated against `gis.layer_attribute_master`, with the declared data type deciding
which operators are legal. There is no second attribute list in the feature, in either tier.

**Tiles are private and generated, never stored.** Every tile is built per request by
`ST_TileEnvelope` → `ST_AsMVTGeom` → `ST_AsMVT`; nothing is written to disk, so there is no tile
cache to warm or invalidate. The response is `Cache-Control: private` because the URL carries no
tenant — the tenant comes from the bearer token — and a shared cache keyed on the URL would serve
one utility's asset tile to another's operator. Lifetime comes from Layer Management's `editable`
flag: short for operational layers, long for reference ones. A `z/x/y` outside the tile grid is
rejected in `TileCoordinate` before it reaches SQL, because `ST_TileEnvelope` validates by raising
and would otherwise turn a mistyped address into a 500.

**An unknown layer is a 404; a tile-disabled layer is an empty tile.** The two look alike and are
not. Nothing legitimate requests a code the registry does not hold — the map builds its sources from
the composed style — so that is a stale bookmark or a client bug and it gets a 404. A layer that
exists with `vector_tile_enabled` off is reachable by configuration: the flag composes the layer out
of the style, but a map loaded before the change keeps requesting a source it already mounted.
MapLibre surfaces a tile 404 through the map's `error` event, which this console turns into an
operator-facing banner, so a 404 there would raise one banner per tile per pan for a change the
operator did not make. It answers a valid empty-layer MVT instead, which the client caches as
"nothing here".

**A tile filter is resolved, not accepted.** `TileFilter` matches the requested field against the
layer's `gis.layer_attribute_master` catalogue (or the four whitelisted `gis.assets` columns) and it
is the *catalogue's* copy of the string that reaches SQL, the operator is a `StyleOperator` constant,
and the value is always bound. Filtering happens in the `WHERE` clause rather than as a MapLibre
`filter` because only the former makes the response smaller — a client-side filter hides features the
tile already paid to carry.

Labels need glyphs, which the raster base maps do not carry: `basemaps.ts` points `glyphs` at
`/basemap/fonts`, proxied to MapLibre's font server in both `vite.config.ts` and `deploy/nginx`. The
stack is `Noto Sans Regular` — that endpoint serves only it and `Open Sans Semibold`, and a stack it
404s draws nothing without reporting an error. Icon styles register SDF shapes at runtime
(`markerShapes.ts`) rather than using a sprite, which is also what lets `icon-color` tint them from
a classified expression.

Permissions are `gis:layer:read` / `:manage` and `gis:style:read` / `:manage` (V1110).
`gis:style:read` is granted alongside `gis:map:view` and must stay at least as wide: the map fetches
its layer specifications from the style API, so a narrower grant gives an operator correct geometry
with every layer drawn grey.

---

## 13. Still not built

Modules visible in the UI but not implemented: alarms, work orders, maintenance, analytics,
settings. They are shown dimmed with a "Soon" badge rather than hidden, so the product's shape
is honest.

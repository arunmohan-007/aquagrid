# 4. Database Design

## 4.1 Global principles

1. **PostgreSQL 16 + PostGIS 3.4** is the single system of record. TimescaleDB extends it for
   telemetry (Module 13+). No polyglot persistence without a proven need.
2. **UUID v4 primary keys** (`gen_random_uuid()`), because IDs must be mergeable across
   distributed deployments, safe to expose in URLs, and generatable client-side for offline
   field-app sync (Module 33). Sequential `BIGSERIAL` is used *only* for append-only, non-exposed
   log tables (`login_attempts`, telemetry) where index locality beats everything else.
3. **Schema per bounded context**, listed in §3.1.
4. **`organization_id UUID NOT NULL`** on every tenant-owned table, first column of every
   composite index, enforced additionally by Hibernate filters and (for sensitive tables) RLS.
5. **Every table** carries `created_at`, `created_by`, `updated_at`, `updated_by`, and `version`
   (optimistic locking). No exceptions in the transactional model.
6. **Soft delete is not the default.** Only entities with regulatory retention (assets, work
   orders, customers) get `deleted_at`; everything else is hard-deleted and captured in the audit
   trail. Blanket soft-delete silently corrupts uniqueness constraints and aggregate queries.
7. **Enumerations are stored as `VARCHAR` with a `CHECK` constraint**, not as PostgreSQL `ENUM`
   types (which require `ALTER TYPE` and lock) and not as ordinals (which break on reorder).
8. **Timestamps are `TIMESTAMPTZ`, always UTC.** Utilities operate across DST and audit evidence
   must be unambiguous. Local rendering is a presentation concern.
9. **Geometry columns are `geometry(<Type>, 4326)`** with a GiST index, plus a generated
   `geom_3857` for tile serving where the reprojection cost matters. Analytical distance/length
   uses `geography` or an explicit UTM transform — never degrees.
10. **Naming:** `snake_case`, plural tables, `fk_`/`ix_`/`uq_`/`ck_` prefixed constraints.

---

## 4.2 Kernel schema (`V1000–V1099`)

### `core.organizations` — the tenant root
Owned by the platform kernel because *authentication cannot exist without a tenant*. Module 3
(Organization Management) extends it with hierarchy depth, branding, contacts and licensing —
it does not redefine it.

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `parent_id` | uuid FK → self | utility → zone → sub-division hierarchy |
| `code` | citext UNIQUE | tenant login discriminator, e.g. `KWA-TVM` |
| `name`, `legal_name` | text | |
| `type` | varchar CHECK | `MUNICIPALITY`,`WATER_AUTHORITY`,`INDUSTRY`,`UTILITY`,`GOVERNMENT` |
| `status` | varchar CHECK | `ACTIVE`,`SUSPENDED`,`ARCHIVED` |
| `timezone`, `locale`, `currency` | text | defaults for all users in the tenant |
| `centroid` | geometry(Point,4326) | default map view — GIS from day one |
| `boundary` | geometry(MultiPolygon,4326) | service area; used for geofencing + spatial RLS |

### `audit.audit_events` — immutable trail
Append-only (no `UPDATE`/`DELETE` grant for the app role), `BIGSERIAL` id, monthly range
partitioning from Module 30. Written asynchronously so it can never block a business transaction,
but with a persistent queue so it can never be silently lost.

Columns: `organization_id`, `actor_user_id`, `actor_username`, `event_type`, `category`
(`AUTHENTICATION|AUTHORIZATION|DATA|CONFIG|SECURITY`), `severity`, `resource_type`, `resource_id`,
`outcome`, `message`, `client_ip inet`, `user_agent`, `trace_id`, `metadata jsonb`, `created_at`.

---

## 4.3 Identity schema (`V1100–V1199`) — Module 1

```
core.organizations
        │ 1
        │
        │ N                        N          M
   identity.users ──── identity.user_roles ──── identity.roles
        │                                             │ N
        │ 1                                           │
        ├── identity.refresh_tokens  (family chain)   │ M
        ├── identity.password_history                 └── identity.role_permissions
        ├── identity.password_reset_tokens                        │ M
        ├── identity.mfa_recovery_codes                           │
        └── identity.login_attempts (also anonymous)     identity.permissions
```

### `identity.users`
| Column | Type | Rationale |
|---|---|---|
| `id` | uuid PK | |
| `organization_id` | uuid NOT NULL FK | tenant |
| `username` | citext NOT NULL | **unique per organization** — utilities reuse staff codes |
| `email` | citext NOT NULL | **globally unique** — enables org-less login and password reset |
| `password_hash` | text | `{bcrypt}$2a$12$…` — the `{id}` prefix enables algorithm migration |
| `password_updated_at` | timestamptz | drives expiry policy |
| `must_change_password` | boolean | first login / admin reset |
| `status` | varchar CHECK | `PENDING`,`ACTIVE`,`DISABLED`,`LOCKED` |
| `failed_login_attempts` | int | lockout counter |
| `lockout_until` | timestamptz | null = not locked |
| `mfa_enabled` / `mfa_secret` / `mfa_confirmed_at` | bool / text / ts | secret is **AES-GCM ciphertext**, never plaintext |
| `last_login_at`, `last_login_ip` | timestamptz, inet | |
| `timezone`, `locale` | text | per-user override of tenant default |
| `version` | bigint | optimistic locking |

*Design decision:* `email` is globally unique, so `POST /auth/login` works with an email alone.
Login with `username` additionally requires `organizationCode`. One human = one account in v1;
multi-org membership is a v2 concern that would introduce an `identity.user_organizations` join
table without changing any other design.

### `identity.permissions` — the authorisation vocabulary
`code` is `resource:action`, e.g. `identity:user:create`, `gis:pipeline:update`,
`iot:device:command`. Global catalogue (not tenant-owned) so the code base can `@PreAuthorize`
against stable constants. Seeded by migration, extended by each module's migration.

### `identity.roles`
`organization_id` **nullable**: `NULL` = system-defined role shipped with the product
(`SUPER_ADMIN`, `ORG_ADMIN`, `GIS_ANALYST`, `FIELD_TECHNICIAN`, `OPERATOR`, `VIEWER`);
non-null = tenant-authored custom role. `is_system` roles are immutable through the API.
Unique on `(coalesce(organization_id,'00000000-…'), code)` via a partial-unique index pair.

### `identity.refresh_tokens` — rotation with reuse detection
| Column | Purpose |
|---|---|
| `token_hash char(64) UNIQUE` | SHA-256 of the opaque token. **The token itself is never stored** — a database dump does not yield working sessions. |
| `family_id uuid` | all tokens descended from one login |
| `replaced_by_id uuid` | rotation chain |
| `revoked_at`, `revoked_reason` | `LOGOUT`,`ROTATED`,`REUSE_DETECTED`,`PASSWORD_CHANGED`,`ADMIN_REVOKED`,`EXPIRED` |
| `client_ip inet`, `user_agent`, `device_label` | powers the "active sessions" screen |

**Reuse detection:** presenting a token that is already `ROTATED` or revoked means the token leaked.
The entire `family_id` is revoked immediately and a `SECURITY_ALERT` audit event is raised.

### `identity.login_attempts`
`BIGSERIAL`, append-only, records both successes and failures with `client_ip` (resolved through
the trusted-proxy chain), `outcome`, `failure_reason`. Feeds lockout, threat analytics and the
compliance report in Module 29. Monthly partitioning from Module 30.

### Indexing strategy for Module 1
```sql
uq_users_email                UNIQUE (email)
uq_users_org_username         UNIQUE (organization_id, username)
ix_users_org_status           (organization_id, status)
uq_refresh_tokens_hash        UNIQUE (token_hash)
ix_refresh_tokens_user_active (user_id) WHERE revoked_at IS NULL
ix_refresh_tokens_family      (family_id)
ix_refresh_tokens_expiry      (expires_at) WHERE revoked_at IS NULL   -- reaper job
ix_login_attempts_ident_time  (identifier, created_at DESC)
ix_login_attempts_ip_time     (client_ip, created_at DESC)
```

---

## 4.4 Forward-looking design (later modules, decided now)

* **Assets** (`gis.*`) use table-per-type with a shared `gis.assets` supertype carrying
  `asset_code`, `organization_id`, `status`, `install_date`, `geom`, `attributes jsonb`. Type
  tables (`gis.pipelines`, `gis.valves`, `gis.tanks`…) hold the type-specific columns. This keeps
  "show every asset in this viewport" a single indexed query while retaining strong typing.
* **Pipeline network topology** uses `pgrouting` on a `gis.pipe_network` edge table derived from
  `gis.pipelines`, refreshed on change, enabling isolation-valve tracing and flow-path analysis.
* **Telemetry** (`ts.readings`) is a TimescaleDB hypertable partitioned by `observed_at`, with
  `(device_id, observed_at DESC)` locality, native compression after 7 days, and continuous
  aggregates at 15 min / 1 h / 1 day. Business queries read the aggregates, never the raw table.
* **Attributes bag:** every asset carries `attributes jsonb` with a GIN index for utility-specific
  fields (contract numbers, ward codes) that must not require a migration per customer.

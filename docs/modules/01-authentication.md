# Module 1 — Authentication

> Design record. Read this before the code.

## 1. What this module is

The **trust anchor** of the platform. Every other module — GIS editing, SCADA setpoint writes, work
order close-out, meter data export — depends on this module correctly answering two questions:
*who is this request from*, and *which tenant do they belong to*. Nothing else can be built safely
until this is right.

Module 1 owns **authentication and session lifecycle only**. User/role administration (CRUD,
invitations, federation) is Module 2; tenant administration is Module 3. This module ships the
minimum tenant + user + role tables those modules will extend, plus the complete auth surface.

### In scope
| Capability | Detail |
|---|---|
| Credential login | email (global) or username + organization code |
| MFA | TOTP (RFC 6238), QR enrolment, single-use recovery codes |
| Session lifecycle | RS256 access token (15 min) + rotating opaque refresh token (14 d) |
| Reuse detection | presenting a rotated token revokes the entire token family |
| Brute-force defence | per-identifier + per-IP rate limit, progressive account lockout |
| Password lifecycle | policy, history, change, forgot/reset, forced change |
| Device sessions | list active sessions, revoke one, revoke all |
| Federation readiness | JWKS endpoint (`/.well-known/jwks.json`) for extracted services |
| Auditing | every authentication decision recorded immutably |

### Out of scope (deliberately)
User CRUD, role editing, OIDC/SAML, SCIM provisioning, API keys, service accounts — Modules 2 & 35.

---

## 2. Architecture

```
                        ┌──────────────────────────────────────────────┐
 POST /api/v1/auth/*    │            web/AuthController                │
 ─────────────────────► │   request DTOs · @Valid · no business logic  │
                        └───────────────────┬──────────────────────────┘
                                            ▼
 ┌───────────────────────────── application/service ─────────────────────────────┐
 │ AuthenticationService  ── orchestrates the login use case                     │
 │ RefreshTokenService    ── issue / rotate / detect reuse / revoke families      │
 │ MfaService             ── enrol, activate, verify, recovery codes             │
 │ PasswordService        ── policy check, history, change, forgot/reset         │
 │ LoginAttemptService    ── record attempts, evaluate lockout                    │
 │ SessionService         ── list / revoke device sessions                        │
 └────────┬───────────────────────────┬───────────────────────────┬───────────────┘
          ▼                           ▼                           ▼
   domain/policy               platform-security            infrastructure/persistence
   PasswordPolicy              JwtTokenService              Spring Data JPA repositories
   LockoutPolicy               RateLimiter                  (the ONLY place SQL happens)
   (pure Java, no Spring)      ClientIpResolver
```

**Why services, not a fat controller or fat entity:** the login use case touches six aggregates
(user, login attempt, refresh token, audit event, rate limiter, MFA state) under one transaction
boundary with a precise ordering. That orchestration belongs in an application service. The
*rules* it applies (is this password strong enough, should this account be locked) are pure
policies in `domain/policy`, unit-testable without a container, and replaceable per tenant later.

### Login sequence

```
Client                AuthController        AuthenticationService        DB
  │  POST /auth/login       │                        │                    │
  ├────────────────────────►│                        │                    │
  │                         ├── rate limit (IP + identifier) ──┐          │
  │                         │                        │◄────────┘ 429 if exceeded
  │                         ├───────────────────────►│  find user (email | org+username)
  │                         │                        ├───────────────────►│
  │                         │                        │  status / lockout checks
  │                         │                        │  BCrypt verify (constant time,
  │                         │                        │   always executed — no user
  │                         │                        │   enumeration via timing)
  │                         │                        │  ┌── fail → increment, maybe lock,
  │                         │                        │  │         audit, 401 generic
  │                         │                        │  └── ok  → reset counters
  │                         │                        │
  │                         │                        │  MFA enabled?
  │                         │                        │   yes → 200 {mfaRequired, mfaToken(5min)}
  │                         │                        │   no  → issue access + refresh
  │◄────────────────────────┤                        │
  │  200 {accessToken, expiresIn, user}              │
  │  Set-Cookie: ag_rt=<opaque>; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth
```

### Token design and why

| Decision | Reason |
|---|---|
| **RS256, not HS256** | Extracted microservices validate with the public JWKS and can never mint tokens. |
| **Access token 15 min, in browser memory** | Bounds the damage of theft; `localStorage` is readable by any XSS payload, memory is not. |
| **Refresh token opaque, not a JWT** | It must be revocable. A JWT refresh token cannot be revoked without exactly the server-side store we would then be pretending not to need. |
| **Refresh token SHA-256 hashed at rest** | A database dump yields no usable sessions. |
| **`HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`** | JS cannot read it (XSS-proof), the browser will not send it cross-site (CSRF-proof), and it is not attached to any non-auth request. |
| **Rotation on every refresh + reuse detection** | Converts a stolen refresh token from a silent permanent backdoor into a detected, self-revoking incident. |
| **Separate 5-minute `mfaToken` with `scp=mfa`** | The password step must not yield an API-usable token. This token authorises exactly one endpoint. |
| **`perms[]` in the token** | Bounded (<200 codes) in this domain, so authorisation is fully stateless — the prerequisite for extraction. |

### Brute-force defence
Two independent layers, because either alone is bypassable:
* **Per-identifier lockout** (persistent): 5 failures → 15 min lock, escalating. Stops password
  spraying against one account from a botnet.
* **Per-IP rate limit** (in-memory token bucket): stops one host enumerating many accounts.

The client IP comes from `ClientIpResolver`, which trusts `X-Forwarded-For` **only** when the TCP
peer is inside a configured trusted-proxy CIDR, and then walks the header right-to-left taking the
first untrusted hop. A naïve `getHeader("X-Forwarded-For")` implementation lets an attacker rotate
a header value and defeat both layers — this is a real, previously-observed production defect and
it is designed out here.

Failed logins return **one generic error** (`AUTH_INVALID_CREDENTIALS`) whether the user exists,
the password is wrong, or the tenant is unknown; and the BCrypt comparison is executed against a
dummy hash even for unknown users so response time does not reveal account existence.

---

## 3. Database design

Full detail in `docs/04-database-design.md` §4.3. Migrations:

| File | Contents |
|---|---|
| `core/V1000__baseline.sql` | schemas, extensions (`citext`, `pgcrypto`, `pg_trgm`), `set_updated_at()` trigger fn |
| `core/V1001__organizations.sql` | `core.organizations` (tenant root, with PostGIS `centroid`/`boundary`) |
| `core/V1002__audit_events.sql` | `audit.audit_events` append-only trail |
| `identity/V1100__identity_core.sql` | `users`, `roles`, `permissions`, `role_permissions`, `user_roles` |
| `identity/V1101__auth_tokens.sql` | `refresh_tokens`, `password_reset_tokens`, `password_history`, `mfa_recovery_codes` |
| `identity/V1102__login_attempts.sql` | `login_attempts` |
| `identity/V1103__seed_permissions_roles.sql` | permission catalogue + system roles + default org |

The bootstrap administrator is **not** seeded with a hard-coded hash. `BootstrapAdminInitializer`
creates it on first start from `AQUAGRID_BOOTSTRAP_ADMIN_PASSWORD`, with
`must_change_password = true`. A shipped default credential is a shipped vulnerability.

---

## 4. API design

Base path `/api/v1/auth`. All responses are `application/json`; all errors are RFC 7807
`ProblemDetail` with a stable `code` and a `traceId`.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/login` | public | password step → tokens **or** `{mfaRequired, mfaToken}` |
| POST | `/mfa/challenge` | mfaToken | complete login with a TOTP or recovery code |
| POST | `/refresh` | cookie | rotate refresh token, issue new access token |
| POST | `/logout` | cookie | revoke current session |
| POST | `/logout-all` | bearer | revoke every session of the user |
| GET | `/me` | bearer | current principal, roles, permissions, org |
| POST | `/password/change` | bearer | change with current-password proof |
| POST | `/password/forgot` | public | always 202 — never reveals whether the email exists |
| POST | `/password/reset` | reset token | complete reset, revoke all sessions |
| GET | `/password/policy` | public | policy for live client-side validation |
| POST | `/mfa/setup` | bearer | secret + `otpauth://` URI + QR payload |
| POST | `/mfa/activate` | bearer | confirm with a code, returns recovery codes **once** |
| POST | `/mfa/disable` | bearer | requires password + current code |
| GET | `/sessions` | bearer | active device sessions |
| DELETE | `/sessions/{id}` | bearer | revoke one session |
| GET | `/.well-known/jwks.json` | public | public keys for token validation |

**Conventions:** `POST` for every state change; no verbs in paths beyond the auth vocabulary;
versioned prefix from day one; idempotent `logout`; `202 Accepted` where the outcome must not leak
information.

---

## 5. UI design

Two surfaces: an **auth shell** (unauthenticated) and the beginning of the **app shell**.

* **Split layout.** Left: brand panel with an animated water-network motif, product name and
  tenant branding slot. Right: the form, centred, max 420 px. Below `md` the brand panel collapses
  to a compact header — the field technician on a phone gets the form above the fold.
* **Material Design 3 via MUI v6 with CSS variables**, so Tailwind utilities and MUI components
  read the same palette token. Dark mode is a `data-mui-color-scheme` switch with no flash of
  incorrect theme (resolved in an inline script before hydration).
* **Palette:** deep water blue primary `#0B63CE`, teal secondary `#0E9F9F`, amber warning,
  desaturated slate neutrals. Chosen for AA contrast in both schemes and for leaving strong
  saturated red/orange free to mean *alarm* everywhere else in the product.
* **Form UX:** React Hook Form + Zod, inline validation on blur, one error summary region with
  `role="alert"`, submit disabled only while pending (never on invalid — that hides why),
  caps-lock hint, show/hide password, autocomplete tokens correct for password managers.
* **Failure states are specific and actionable:** locked account shows the remaining time; rate
  limit shows a countdown; expired reset link offers to request a new one.
* **MFA screen:** six single-character inputs with paste support and auto-advance, or a recovery
  code toggle.
* **Accessibility:** full keyboard path, visible focus rings, labelled inputs, 4.5:1 contrast,
  `aria-live` on async status. Non-negotiable for public-sector procurement.

---

## 6. GIS requirements for this module

Authentication is not a map screen, but it must not be GIS-hostile. Three obligations:

1. **`core.organizations` carries `centroid geometry(Point,4326)` and
   `boundary geometry(MultiPolygon,4326)`** from the very first migration. The tenant's default map
   extent is an identity concern — Module 4 must not have to guess where to open the map, and
   retrofitting geometry onto the tenant root later would touch every module.
2. **`/auth/me` returns the map bootstrap payload**: `defaultCenter`, `defaultZoom`, tenant
   `boundary` bbox and the user's permitted layer scope. The GIS dashboard therefore renders the
   correct extent on first paint with no additional round trip.
3. **Spatial authorisation is anticipated.** `boundary` is the basis for restricting a user to a
   zone/DMA in Module 3 (`ST_Within(asset.geom, org.boundary)` as an RLS predicate). The token
   already carries `org`, which is the join key.

---

## 7. Testing strategy

* **Domain policies** — plain JUnit, no Spring: password policy matrix, lockout escalation, TOTP
  vectors from RFC 6238, `IpSubnet`/`ClientIpResolver` including spoofing attempts.
* **Application services** — Mockito, asserting audit events and transaction boundaries.
* **Integration** — Testcontainers PostGIS, `MockMvc`: full login → MFA → refresh → reuse-detection
  → family revocation flow, lockout, and reset-token single use.
* **Security regression suite** — explicit tests that a spoofed `X-Forwarded-For` does not bypass
  lockout, that a rotated refresh token kills its family, that `/me` is unreachable with an
  `mfaToken`, and that error responses are identical for unknown user vs. wrong password.

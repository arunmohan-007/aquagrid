# 3. Folder Structure

```
E:\Water Meter Project\
├── README.md
├── .gitignore
├── .env.example
├── docs/
│   ├── 01-architecture.md
│   ├── 02-technology-justification.md
│   ├── 03-folder-structure.md
│   ├── 04-database-design.md
│   ├── 05-roadmap.md
│   └── modules/
│       └── 01-authentication.md
├── deploy/
│   ├── docker-compose.yml            # postgis + api (+ profiles: geoserver, mqtt, chirpstack)
│   ├── docker-compose.override.yml
│   ├── postgres/initdb/00-extensions.sql
│   └── nginx/nginx.conf
├── backend/
│   ├── pom.xml                       # parent (packaging: pom)
│   ├── platform-common/
│   ├── platform-security/
│   ├── module-identity/
│   └── app-bootstrap/
└── frontend/
    ├── package.json
    ├── vite.config.ts
    ├── tailwind.config.js
    ├── tsconfig.json
    └── src/
```

---

## 3.1 Backend module layout

```
backend/
├── pom.xml ......................... parent POM: Java 21, dependency mgmt, annotation processors
│
├── platform-common/ ................ THE KERNEL. Depends on nothing but Spring core + JPA API.
│   └── src/main/java/com/aquagrid/platform/common/
│       ├── domain/         BaseEntity, AuditableEntity, TenantAwareEntity
│       ├── tenant/         TenantContext, TenantFilterAspect
│       ├── error/          ErrorCode, BusinessException, GlobalExceptionHandler (RFC 7807)
│       ├── web/            ApiPaths, PageResponse, CorrelationIdFilter, ClientIpResolver, IpSubnet
│       ├── crypto/         CryptoService (AES-GCM), Hashes, TokenGenerator, Base32
│       ├── audit/          AuditEvent, AuditService, AuditEventEntity  (Module 30 extends this)
│       ├── config/         JacksonConfig, AsyncConfig, CacheConfig, JpaAuditingConfig
│       └── util/
│   └── src/main/resources/db/migration/core/    V1000–V1099  ← reserved range
│
├── platform-security/ .............. Token issuance/validation + authz primitives. No user tables.
│   └── src/main/java/com/aquagrid/platform/security/
│       ├── jwt/            JwtProperties, JwtKeyProvider, JwtTokenService, JwksController
│       ├── core/           AuthenticatedPrincipal, SecurityUtils, Permissions, AuthClaims
│       ├── ratelimit/      RateLimiter (Caffeine), RateLimitProperties
│       └── config/         SecurityConfig, PasswordEncoderConfig, PublicEndpoints (SPI)
│
├── module-identity/ ................ MODULE 1 + 2 + 3. Auth, users, roles, permissions, orgs.
│   └── src/main/java/com/aquagrid/platform/identity/
│       ├── api/            IdentityApi, UserSummary, event/UserAuthenticatedEvent
│       ├── domain/         model/{User,Role,Permission,RefreshToken,...}  enums/  policy/
│       ├── application/    service/  command/  mapper/
│       ├── infrastructure/ persistence/  config/  bootstrap/
│       └── web/            controller/AuthController  dto/
│   └── src/main/resources/db/migration/identity/  V1100–V1199  ← reserved range
│
└── app-bootstrap/ .................. The only executable. Wires modules, owns runtime config.
    └── src/main/java/com/aquagrid/platform/AquaGridApplication.java
    └── src/main/resources/{application.yml, application-local.yml, logback-spring.xml}
    └── Dockerfile
```

### Flyway version ranges (reserved per module)

| Range | Owner |
|---|---|
| `V1000–V1099` | platform kernel — schemas, extensions, `core.organizations`, `audit.audit_events` |
| `V1100–V1199` | identity — users, roles, permissions, tokens, MFA, login attempts |
| `V1200–V1299` | organization / sites / zones / DMA |
| `V1300–V1399` | GIS assets (pipes, valves, hydrants, tanks, reservoirs, pump stations) |
| `V1400–V1499` | devices & communication layer |
| `V1500–V1599` | telemetry hypertables & continuous aggregates |
| `V1600–V1699` | alarms & notifications |
| `V1700–V1799` | work orders & maintenance |
| `V1800–V1899` | customers, billing, consumption |
| `V1900–V1999` | analytics, NRW, water balance |

One linear timeline, module-owned files. A module can be lifted into its own service with its
migration folder intact.

### Database schemas

`core` · `identity` · `audit` · `org` · `gis` · `iot` · `ts` · `ops` · `billing` · `analytics`

Schema-per-module keeps the extraction boundary visible in the database itself and lets us grant
per-schema privileges to future service accounts.

---

## 3.2 Frontend layout (feature-sliced)

```
frontend/src/
├── main.tsx                     bootstrap: providers, router
├── app/
│   ├── providers/               AppProviders, ThemeModeProvider, QueryProvider
│   ├── theme/                   palette.ts, theme.ts (light+dark, CSS variables)
│   └── router/                  routes.tsx, ProtectedRoute, RequirePermission
├── lib/
│   ├── api/                     httpClient.ts (axios + silent refresh), queryClient.ts, problem.ts
│   ├── auth/                    tokenStore.ts (in-memory), AuthProvider.tsx, useAuth.ts, permissions.ts
│   └── utils/
├── components/                  shared presentational: DataTable, PageHeader, StatCard, Empty, Toast
├── layouts/                     AuthLayout, AppShell (sidebar + topbar + breadcrumbs)
├── features/                    ONE FOLDER PER BUSINESS MODULE
│   ├── auth/
│   │   ├── api/authApi.ts       typed calls
│   │   ├── hooks/               useLogin, useMfaChallenge, useLogout, useMe
│   │   ├── pages/               LoginPage, MfaChallengePage, ForgotPasswordPage, ResetPasswordPage
│   │   ├── components/          PasswordField, PasswordStrengthMeter, BrandPanel
│   │   └── types.ts
│   ├── users/          (Module 2)
│   ├── gis/            (Module 4 — lazy-loaded chunk, owns OpenLayers)
│   ├── devices/        (Module 6)
│   └── ...
└── styles/index.css             Tailwind layers + MUI CSS-variable bridge
```

**Rule:** a feature folder may import from `components/`, `lib/`, `layouts/` — never from another
feature. Cross-feature reuse gets promoted to `components/` or `lib/`. This mirrors the backend's
module boundary so the two sides stay refactorable together.

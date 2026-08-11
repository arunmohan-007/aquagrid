# Module 31 — Deployment & Production Hardening (Phase 7)

Docker, CI/CD and the production-readiness guarantees that turn "it runs on my laptop" into "it is
safe to operate a water utility on."

---

## 1. What was already in place (Phase 0)

- **Multi-stage, layered, non-root Dockerfile** — dependency-cache layer invalidated only by POM
  changes; Spring Boot layertools split the image so an app-code change rebuilds the smallest layer;
  runs as an unprivileged `aquagrid` user on a JRE-only Alpine base with no shell package manager.
- **docker-compose** with profiles: default (postgres + api), `web` (nginx + SPA build), `gis`
  (GeoServer), `iot` (mosquitto). A municipality running Module 1 does not start a LoRaWAN NS.
- **Actuator** with health/readiness probes, Prometheus metrics, `show-details: when-authorized`.
- **Boot-time security assertions** in `IdentityModuleConfig`: JWT key present, refresh cookie
  `Secure`, `SameSite` not `None`, `app-base-url` HTTPS — each refuses to start outside dev if violated.

## 2. Added in Phase 7

### 2.1 CI/CD pipeline (`.github/workflows/ci.yml`)
Mirrors the supply-chain evidence the technology justification commits to:

```
backend:  compile → unit tests → integration tests (Testcontainers/PostGIS)
                 → OWASP dependency-check (CVSS ≥ 7) → Trivy image scan (SARIF → Security tab)
                 → CycloneDX SBOM artifact
frontend: typecheck (strict) → build → SPA artifact upload
ready:    gate job, main only, both green
```

- **Concurrency-cancelling** so a force-push doesn't queue behind the build it replaced.
- **Test report annotations** surface failing tests on the PR without opening the log.
- **Supply-chain scanners report, not block, in Phase 7** (`exit-code: 0`, `continue-on-error`).
  This is intentional: blocking requires a triaged baseline first. Each scanner's docs state exactly
  how to promote it to blocking once the baseline is established.
- **Deploy is a gate, not an executor**: actual deployment runs in a protected environment with
  manual approval, never automatically from a green build.

### 2.2 OWASP + CycloneDX plugins
Version-pinned in the parent pom's `pluginManagement`, invoked explicitly by CI (not every build).
Suppression file (`.github/owasp-suppressions.xml`) enforces per-CVE, per-version suppression with
a documented reason — wildcard suppression is forbidden.

### 2.3 Dockerfile fix
The Phase 0 Dockerfile copied only `module-identity`. After Phases 4–6 added `module-gis` and
`module-iot`, the Docker build would have failed. Fixed to copy all five modules.

### 2.4 Production-readiness verifier
`ProductionReadinessVerifier` extends the boot-assertion pattern to the new modules: the simulator
**must** be off outside development profiles (it would generate fictional telemetry
indistinguishable from real data — a quiet data-integrity defect), and the live ingest transports
are logged explicitly so an unintended one is visible.

### 2.5 `.env.example`
Documents every IoT transport env var, with the simulator warning prominent.

## 3. Hardening posture (cumulative)

| Concern | Control |
|---|---|
| Image footprint | Multi-stage, non-root, no shell package manager, layered |
| Secrets | Never in the image; env / Docker secrets only; JWT private key never leaves the API |
| TLS | nginx terminates; HSTS; `app-base-url` must be HTTPS (boot-asserted) |
| Cookies | `HttpOnly; Secure; SameSite=Strict` (boot-asserted) |
| CORS | Strict allowlist, no wildcard with credentials (boot-asserted) |
| Client IP | Trusted-proxy-aware, spoofed `X-Forwarded-For` cannot evade lockout/rate-limit |
| Dependency CVEs | OWASP dependency-check, CVSS ≥ 7 gate |
| Image CVEs | Trivy, HIGH/CRITICAL → SARIF → Security tab |
| SBOM | CycloneDX artifact published per build |
| Health | Readiness probe gates the load balancer; liveness probes restart on hang |
| Simulator safety | Boot-asserted off in production |

## 4. Out of scope (later modules)

- Backup & restore runbook with tested PITR (Module 32)
- Tamper-evident audit hash chain (Module 30)
- Redis L2 cache and horizontal-session invalidation (Module 31 deepening)
- Helm/K8s manifests for cloud SKU (same image, different orchestration)

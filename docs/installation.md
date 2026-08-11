# Installation & Setup Guide

What you need to install to run the AquaGrid platform, in plain terms.

---

## TL;DR — the minimum

To run everything built so far, you need **only three things**:

1. **Docker** (for the database)
2. **Java 21 + Maven** (for the backend)
3. **Node 20+** (for the frontend)

GeoServer, ChirpStack, LoRa gateways, MQTT brokers, and MinIO are **all optional** and off by default. The simulator generates realistic telemetry without any hardware.

---

## 1. The database (mandatory)

PostgreSQL with **three extensions** is the single system of record — nothing runs without it:

| Extension | Why it's needed | Modules |
|---|---|---|
| **PostGIS** | Spatial types, `ST_AsMVT` vector tiles, GiST indexes | 4, 11, 12, 23 |
| **pgRouting** | Network tracing (`pgr_dijkstra`, `pgr_drivingDistance`) | 11, 12 |
| **TimescaleDB** | Telemetry hypertables + compression | 13–15 (table is hypertable-ready) |

The default `postgis/postgis` Docker image **lacks pgRouting and TimescaleDB**. A custom image is
provided that layers both onto the PostGIS base.

### Local development (recommended path)

```bash
cd "E:/Water Meter Project"
docker compose -f deploy/docker-compose.dev.yml up -d
```

This builds and starts:
- **PostgreSQL** (custom image with PostGIS + pgRouting + TimescaleDB) on `localhost:5432`
  - User/password: `aquagrid` / `aquagrid`
- **MinIO** (S3-compatible object storage, for attachments) on `localhost:9000` (console `:9001`)

First run takes ~2 minutes to build the DB image. Subsequent runs are instant.

### Verify the database

```bash
docker exec -it aquagrid-postgres-dev psql -U aquagrid -d aquagrid -c "
  SELECT extname FROM pg_extension WHERE extname IN ('postgis','pgrouting','timescaledb');"
```
You should see all three listed.

### Connection details
- **JDBC URL:** `jdbc:postgresql://localhost:5432/aquagrid`
- **User / password:** `aquagrid` / `aquagrid`

The schema is created automatically by **Flyway** when the backend starts — you never run SQL by hand.

---

## 2. The backend (mandatory)

### Prerequisites
- **Java 21** — you have this (Adoptium 21.0.11)
- **Maven 3.9+** — you do NOT have this. Install it:

```bash
# Windows (if you use scoop)
scoop install maven

# Or download manually from https://maven.apache.org/download.cgi
# Add the bin/ directory to your PATH
```

### Run

```bash
cd "E:/Water Meter Project/backend"
mvn clean install          # first build: downloads deps, compiles, runs unit tests
mvn -pl app-bootstrap spring-boot:run -Dspring-boot.run.profiles=local
```

On first start:
- Flyway runs all migrations (creates schemas, tables, seeds permissions/roles/system tenant)
- `BootstrapAdminInitializer` creates the first admin if `AQUAGRID_BOOTSTRAP_ADMIN_PASSWORD` is set

- API: <http://localhost:8080>
- Swagger UI: <http://localhost:8080/swagger-ui.html>

### Integration tests (require Docker)
```bash
cd "E:/Water Meter Project/backend"
mvn verify     # runs Testcontainers against a real PostGIS container
```

---

## 3. The frontend (mandatory)

### Prerequisites
- **Node 20+** — you have this (v24.14)

### Run

```bash
cd "E:/Water Meter Project/frontend"
npm install
npm run dev
```

- App: <http://localhost:5173>
- Vite proxies `/api` to the backend (port 8080), keeping the browser same-origin so the
  `SameSite=Strict` refresh cookie works exactly as in production.

---

## What you do NOT need to install

These are all **optional**, behind Docker profiles or config flags, and off by default:

| Component | When you'd need it | How it's activated |
|---|---|---|
| **GeoServer** | WMS/WFS for QGIS, publishing to a state GIS portal | `--profile gis` in compose. The map works without it — `ST_AsMVT` serves tiles directly from PostGIS. |
| **ChirpStack** (LoRaWAN NS) | Connecting **real** LoRaWAN meters | `--profile iot`. For dev/demos, the simulator generates identical traffic. |
| **LoRa gateway** (hardware) | Real LoRaWAN devices | Physical purchase + site install. Not a software install. |
| **Mosquitto** (MQTT broker) | The MQTT transport specifically | `--profile iot` + `IOT_MQTT_ENABLED=true`. HTTP and simulator transports work without it. |
| **MinIO** | Multi-node attachment storage | `docker compose dev` includes it, but the app falls back to filesystem storage if absent. |
| **NB-IoT carrier platform** | Real NB-IoT meters | A telecom subscription + the carrier's webhook URL. Not self-hosted. |

---

## Enable the simulator (to see live telemetry)

After the backend is running with the `local` profile, start the simulator:

```bash
# Option A: environment variable before starting the backend
export IOT_SIMULATOR_ENABLED=true
mvn -pl app-bootstrap spring-boot:run -Dspring-boot.run.profiles=local

# Option B: in application-local.yml, add:
# aquagrid:
#   iot:
#     transports:
#       simulator: true
```

The simulator provisions 50 meters against the system tenant and emits realistic diurnal-demand
telemetry every minute — the same ingest path a real LoRaWAN meter uses. Check status at
`GET /api/v1/simulator/status` (requires `iot:simulator:run` permission).

---

## Quick start (all three in order)

```bash
# 1. Database
cd "E:/Water Meter Project"
docker compose -f deploy/docker-compose.dev.yml up -d

# 2. Backend (in a new terminal)
cd backend
mvn clean install -DskipTests
mvn -pl app-bootstrap spring-boot:run -Dspring-boot.run.profiles=local

# 3. Frontend (in a new terminal)
cd frontend
npm install && npm run dev
```

Then open <http://localhost:5173>, sign in with the bootstrap admin, and the platform is live.

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Backend fails: "extension pgrouting does not exist" | You're using the plain `postgis/postgis` image. Use the dev compose (builds the custom image) or `docker compose -f deploy/docker-compose.dev.yml build` then up. |
| Backend fails: "connection refused localhost:5432" | Database not running. `docker compose -f deploy/docker-compose.dev.yml up -d`. |
| Frontend: blank page, console shows 401 | Backend not running, or not signed in. Start backend first. |
| `mvn: command not found` | Maven not installed. See §2 above. |
| Flyway: "validation failed" | Schema drifted from migrations. Drop and recreate the DB: `docker compose -f deploy/docker-compose.dev.yml down -v` then `up -d`. |

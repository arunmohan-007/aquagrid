# Modules 8, 9, 10 — Tank, Reservoir, Pump Station

Type-specific extensions of the asset supertype. Each adds one table referencing `gis.assets.id`
and the engineering fields an operator needs to run that asset kind.

---

## Pattern

Every type module follows the same shape, established here so Modules 11 (pipelines) and 12
(valves) clone it:

```
gis.assets (supertype, Module 23)
      │ 1
      │
      │ 1            PK = FK to gis.assets.id (shared identity, not a separate id)
  gis.tanks          type-specific columns only
```

- **Shared identity:** the type table's PK *is* the asset id. No separate id, no join confusion —
  "the tank for asset X" is a single keyed lookup.
- **Type assertion:** the service rejects a type row whose parent asset is the wrong type. A tank
  record on a PIPELINE asset is a data error, not a silent success.
- **Engineering data only:** capacity, elevations, rated curves. Everything common (org, code,
  status, geometry, attributes) lives on the supertype and is never duplicated.

## Database (`V1310__tanks_reservoirs_pumps.sql`)

| Table | Key fields | Why they matter |
|---|---|---|
| `gis.tanks` | `capacity_m3`, `current_level_m3`, `base/overflow/inlet_elevation_m`, `tank_type` | Live level drives the map gauge + overflow alarm; elevations feed the hydraulic model. |
| `gis.reservoirs` | `max_capacity_m3`, `current_volume_m3`, `source_type`, `surface_area_m2` | Surface area drives evaporation loss in water-balance analysis. |
| `gis.pump_stations` | `pump_count`, `rated_flow/head/power`, `pump_states jsonb`, `pump_curve jsonb` | Curve is vendor head/flow data (JSONB — one vendor's columns would freeze the schema); states refreshed by SCADA. |

CHECK constraints enforce physical sanity (capacity > 0, level within capacity, pump count 1–20).

## API

Nested under the asset id so the relationship is unambiguous:

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/assets/{id}/tank` | Tank record |
| `PUT` | `/assets/{id}/tank` | Create/update tank record |
| `GET/PUT` | `/assets/{id}/reservoir` | Reservoir record |
| `GET/PUT` | `/assets/{id}/pump-station` | Pump-station record |

All gated by `gis:asset:read` / `gis:asset:update`.

## Frontend

Type records surface on the existing `AssetDetailPage` (Module 23) — a type-specific panel renders
when the asset's type matches. The API is the deliverable for this slice; the form panels are added
when telemetry (Module 13) makes the live-level gauges meaningful.

## Files

| Path | Role |
|---|---|
| `V1310__tanks_reservoirs_pumps.sql` | Three type tables |
| `domain/model/{Tank,Reservoir,PumpStation}.java` | Entities |
| `infrastructure/persistence/{Tank,Reservoir,PumpStation}Repository.java` | Repos |
| `web/dto/AssetTypeDtos.java` | DTOs + requests |
| `application/service/AssetTypeService.java` | Read + upsert, with type assertion |
| `web/controller/AssetTypeController.java` | REST |

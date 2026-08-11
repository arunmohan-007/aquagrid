# Module 4 — GIS Dashboard

The spatial foundation of the platform: a PostGIS-backed asset supertype, an `ST_AsMVT` vector-tile
endpoint, and an OpenLayers map client. Delivers the operational map a utility opens every morning.

---

## 1. Architecture

```
OpenLayers (browser)
   │ VectorTileSource ──fetch PBF──► /api/v1/gis/tiles/{layer}/{z}/{x}/{y}
   │                                      │ Authorization: Bearer (header, never URL)
   ▼                                      ▼
ST_AsMVT(gis.assets.geom_3857) ◄── ST_TileEnvelope clip ◄── PostGIS 3.4
```

The map's hot path is the tile endpoint. It returns PBF bytes generated **in the database** by
`ST_AsMVT`, so payload is O(viewport) not O(network size) — the property designed out from day one
to avoid the 12 MB GeoJSON payload incident documented in the technology justification.

## 2. Database (`V1300__gis_assets.sql`)

- **`gis.assets`** supertype: `organization_id`, `asset_code`, `asset_type`, `status`, `geom`
  (EPSG:4326), a **generated `geom_3857`** column (Web Mercator, materialised once on write so tile
  serving never reprojects per request), and an `attributes jsonb` bag with a GIN index.
- **Dual GiST indexes** on both SRIDs: 4326 for analytical `ST_Intersects`/`ST_DWithin`, 3857 for
  tile-bbox clipping in `ST_AsMVT`.
- **`gis.layers`** catalogue, seeded with a default layer set for every tenant (meters, valves,
  pipelines, hydrants…) so the first map open is useful, not empty.

## 3. Backend (`module-gis`)

New Maven module. **Depends on `platform-common` only, not on `module-identity`** — GIS resolves
"who owns this asset" through `TenantContext`, never by joining to `identity.users`. This keeps the
GIS module extractable without dragging identity with it.

| Endpoint | Purpose |
|---|---|
| `GET /api/v1/gis/layers` | Layer catalogue for the tenant (drives the layer tree) |
| `GET /api/v1/gis/tiles/{layer}/{z}/{x}/{y}` | Mapbox Vector Tile PBF bytes, 10-min public cache |

Both gated by `gis:map:view`. The tile endpoint returns 200 with an empty body when a tile has no
features — empty tiles are cacheable so the client stops asking, unlike a 404 that would be retried.

Spatial queries use native SQL (`ST_AsMVT`, `ST_Intersects`, `ST_TileEnvelope`), not JPQL — the
explicit ORM-vs-SQL split from the technology justification.

## 4. Frontend (`features/gis`)

- **`MapView`** — OpenLayers initialised once in a ref (it is imperative and intolerant of React
  re-renders of its container). Base-layer toggle (OSM / satellite). Vector tile layers reconciled
  against the catalogue so toggling preserves pan/zoom state and the tile cache.
- **`LayerTree`** — toggle pane mirroring the catalogue's visibility.
- **Authed tile loading** — the bearer token is injected per-request via `tileLoadFunction`, never
  baked into the URL (URLs are cacheable and may be logged by proxies).
- The whole feature is **lazy-loaded** by the router, so OpenLayers (the heaviest dependency) never
  taxes the login screen.
- Opens on the tenant's extent from `/auth/me` (centre + zoom carried on the identity endpoint) so
  the operator sees their service area on first paint with no extra round trip.

## 5. Deployment

The `--profile gis` docker-compose block (GeoServer 2.26) was already present from Phase 0. GeoServer
serves WMS/WFS for QGIS and state-portal interop; the MVT endpoint above serves the browser map
directly from PostGIS, which is cheaper and avoids a GeoServer hop for the common case.

## 6. Out of scope (Module 4 deepening / later modules)

- Type-specific tables (pipelines with topology, valves with isolation tracing) — Modules 11/12
- GeoServer layer/style auto-provisioning from `gis.layers`
- Measure/draw/snap interactions for network editing
- Print/export, coordinate-address search, bookmarks
- Tile-cache hardening (signed URLs or cookie-session tiles instead of header auth)

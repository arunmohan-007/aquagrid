# Module 11 — Pipeline Network Management

Pipes as network edges, with pgRouting topology build and tracing. The hardest spatial module — the
one Modules 5, 7, 12 depend on for network nodes.

---

## 1. The graph model

```
gis.pipelines (source of truth)        gis.pipe_network (derived, pgRouting edges)
   pipe ── from_node ──► junction           id, source, target, cost, reverse_cost
        ── to_node ──► junction             (rebuilt from pipelines on every write)
                    ▲
                    │
              gis.network_nodes (junctions, auto-created on snap)
              pgr_vertex_id BIGINT (the integer pgRouting requires)
```

**Source of truth vs derived graph.** `gis.pipelines` is edited (normal CRUD). `gis.pipe_network`
is the edge table pgRouting queries — rebuilt from pipelines on every write, never edited directly.
This separation means pipe edits are simple, and topology is a fast idempotent build step.

## 2. Topology build

Every pipe write, in one transaction:
1. **Snap** — the line's endpoints find the nearest existing junction within 1m tolerance, or a new
   junction is created. This is what connects separately-drawn pipes into a graph.
2. **Persist** — the pipeline row stores `from_node`/`to_node`.
3. **Rebuild** — `gis.pipe_network` is regenerated: each pipe becomes an edge with `cost = length_m`
   and `reverse_cost = -1` for one-way mains (impassable backwards).

Tolerance is 1m — close enough for survey-grade GPS, tight enough not to bridge parallel mains.

## 3. Tracing

| Query | pgRouting function | Use |
|---|---|---|
| Downstream reachability | `pgr_drivingDistance` (forward edges) | "What does this break affect downstream?" |
| Upstream reachability | `pgr_drivingDistance` (reversed edges) | "What feeds this point?" / contamination source |
| Shortest path | `pgr_dijkstra` | "Feed path from reservoir to DMA" |

Direction is encoded in `reverse_cost`: a one-way `FROM_TO` main has `reverse_cost = -1`, so an
upstream trace cannot travel "up" it. The rebuild keeps this honest.

Traces return node id, accumulated cost (metres), and geometry (WKT) — the frontend highlights these
on the map.

## 4. Database (`V1320__pipeline_network.sql`)

- **pgRouting extension** added.
- `gis.network_nodes` — junctions with `pgr_vertex_id BIGSERIAL` (pgRouting's integer vertex id),
  geometry (Point 4326 + generated 3857), node type.
- `gis.pipelines` — type table + `geom LineString` + `from_node`/`to_node` + `length_m` GENERATED
  (`ST_Length(geography)` for accurate metres) + `flow_direction` + Hazen-Williams roughness.
- `gis.pipe_network` — derived edge table, rebuilt on change.

## 5. API

| Method | Path | Permission | Purpose |
|---|---|---|---|
| `GET` | `/assets/{id}/pipeline` | `gis:asset:read` | Pipeline detail |
| `PUT` | `/assets/{id}/pipeline` | `gis:asset:update` | Create/update (snaps + rebuilds) |
| `POST` | `/assets/network/rebuild` | `gis:network:trace` | Force topology rebuild |
| `POST` | `/gis/network/trace` | `gis:network:trace` | Up/down reachability trace |
| `POST` | `/gis/network/shortest-path` | `gis:network:trace` | Shortest path A→B |

`gis:network:trace` is a distinct permission from asset read/write — trace results reveal topology
not every viewer should see.

## 6. Why this approach

- **pgRouting in Postgres, not an in-memory graph library.** The network is large and persisted;
  tracing must be transactional with the data. Pulling the graph into Java to run Dijkstra would be
  slower and stale-prone.
- **Derived edge table, not direct pgRouting on pipelines.** pgRouting needs a specific edge schema
  (`source`, `target`, `cost`, `reverse_cost`). Mapping pipelines to that on every query is wasteful;
  materialising once per write is cheap.
- **Snap-on-write, not snap-on-query.** Connectivity is established when a pipe is saved, so traces
  are pure graph queries with no spatial reasoning at query time.

## 7. Files

| Path | Role |
|---|---|
| `V1320__pipeline_network.sql` | pgRouting ext, nodes, pipelines, edge table |
| `domain/model/{NetworkNode,Pipeline}.java` | Entities |
| `infrastructure/persistence/{NetworkNode,Pipeline,NetworkTrace}Repository.java` | Snap, rebuild, trace SQL |
| `application/service/{NetworkTopologyService,NetworkTraceService,PipelineService}.java` | Snap/rebuild, trace, CRUD |
| `web/controller/{PipelineController,NetworkTraceController}.java` | REST |

## 8. Out of scope

- Incremental edge-table updates (current rebuild is full-scan; fine to ~100k pipes)
- Hydraulic simulation (EPANET integration) — uses the same network, separate module
- Frontend pipeline editor (draw/snap UI) — Module 11 deepening

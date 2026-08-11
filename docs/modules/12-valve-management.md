# Module 12 — Valve Management

Valves as network control points, with isolation-valve tracing — the control-room question answered
during every main break: *"which valves do I close to isolate this section?"*

---

## 1. The defining capability

A break occurs on a pipe. The operator needs, in seconds:
- The **set of valves to close** to isolate the broken section.
- The **customer impact** (how many connections are inside the isolated area).
- Confidence that the **already-closed valves** are accounted for.

This is the isolation trace. It is policy layered on topology: the graph machinery is pgRouting
(Module 11); the isolation logic is "walk until a CLOSED valve."

## 2. The algorithm

```
trace(sourceNode, maxDistance):
    downstream = pgr_drivingDistance(source, DOWN, maxDistance)   # Module 11
    upstream   = pgr_drivingDistance(source, UP,   maxDistance)
    reachedNodes = downstream.nodes ∪ upstream.nodes

    perimeterValves = valves WHERE node IN reachedNodes
    valvesToClose   = perimeterValves WHERE status = 'OPEN'       # the actionable set
    alreadyClosed   = perimeterValves WHERE status = 'CLOSED'     # existing boundary
```

The walk halts at CLOSED valves because pgRouting's `reverse_cost = -1` on a closed valve's edges
makes them impassable — but the current implementation models valve state as a post-filter on the
reachable set, which is correct and simpler than rewriting edge costs per valve state change. (A
future optimisation bakes valve state into the edge table; correctness is identical.)

The trace walks **both directions** — a break affects upstream and downstream sections, and
isolation valves may be on either side.

## 3. The operate workflow

Operating a valve is a **state transition with an evidence chain**, not a field update:

```
operate(valve, OPEN|CLOSED, operator, reason):
    fromState = valve.status
    valve.status = toState
    write ValveOperation(fromState, toState, operator, reason, timestamp)   # append-only
    audit("VALVE_OPERATED", fromState, toState)
```

Every open/close is recorded in `gis.valve_operations` (append-only, BIGSERIAL) — the evidence for
"was this valve operated correctly during the incident?", a question regulators ask after any supply
event. `normal_state` (the designed-default) drives the return-to-normal close-out step.

## 4. Database (`V1321__valves.sql`)

- **`gis.valves`** — type table: `node_id` (links to the graph), `valve_type` (GATE/BUTTERFLY/PRV/
  AIR_RELEASE/CHECK/BALL), `status` (OPEN/CLOSED/PARTIAL/FAULTY), `normal_state`, `pressure_setpoint_bar`
  (for PRVs), `turns_to_operate` (field-crew planning).
- **`gis.valve_operations`** — append-only audit: from/to state, operator, reason, work-order link,
  client IP, timestamp.

## 5. API

| Method | Path | Permission | Purpose |
|---|---|---|---|
| `GET` | `/assets/{id}/valve` | `gis:asset:read` | Valve detail |
| `PUT` | `/assets/{id}/valve` | `gis:asset:update` | Create/update valve record |
| `POST` | `/assets/{id}/valve/operate` | `iot:device:command` | Operate (OPEN/CLOSED) — gated as a command |
| `GET` | `/assets/{id}/valve/operations` | `gis:asset:read` | Operation history |
| `POST` | `/gis/isolation/trace` | `gis:network:trace` | Isolation perimeter from a break point |

**Permission note:** operating a valve is gated by `iot:device:command` — the same permission that
gates device downlinks. Operating physical infrastructure is a command; a viewer can read state but
not change it.

## 6. Files

| Path | Role |
|---|---|
| `V1321__valves.sql` | Valves + operation log |
| `domain/model/{Valve,ValveOperation}.java` | Entities |
| `infrastructure/persistence/{Valve,ValveOperation}Repository.java` | Repos |
| `application/service/{ValveService,IsolationTraceService}.java` | CRUD+operate, isolation trace |
| `web/controller/{ValveController,IsolationTraceController}.java` | REST |

## 7. Out of scope

- Baking valve state into edge `reverse_cost` (current post-filter is correct; optimisation later)
- Affected-customer enrichment (returns node set; Module 5/7 join enriches it)
- Interlock enforcement (don't close a valve cutting off a critical facility) — Module 12 deepening
- Frontend isolation-trace panel — Module 12 deepening

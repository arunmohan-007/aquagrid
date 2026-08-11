# Module 17 — Device Simulator (Phase 6)

A fleet simulator that generates realistic water-meter traffic through the same ingestion port every
real transport uses. Demos, load tests, and the development of alarm/NRW features all run against
simulated data — no real hardware required.

---

## 1. Why a simulator matters

A simulator that emits uniform random flow teaches the analytics nothing. Real residential water
demand has a characteristic shape, and the alarm/NRW modules are built to detect deviations from it.
So the simulator must reproduce that shape and the faults that depart from it:

- **Diurnal demand** — two peaks (morning ~07:00, evening ~20:00), a midday bump, a deep 03:00
  trough (the minimum-night-flow window NRW analysis depends on).
- **Leaks** — slow, persistent additions to baseline flow. The signature MNF anomaly.
- **Bursts** — sudden high flow that decays as the main is shut. Loud, short, dramatic.
- **Comms loss** — intermittent windows where no uplink is emitted (gateway outage, fade, PSM).
- **Battery decay** — voltage drifts down over months of operation toward the replace threshold.
- **Tamper** — rare, sticky flag (magnet/removal) once set.

## 2. Components

| Class | Role |
|---|---|
| `DiurnalDemandModel` | Sum-of-Gaussians demand curve, local-clock driven. Multiplicative log-normal jitter (never negative flow). |
| `SimulatedMeter` | One meter's state: cumulative volume, battery, active faults, frame counter. Integrates flow over each interval. |
| `FaultScenarioEngine` | Per-meter-per-tick probabilistic fault injection, scaled by elapsed hours (interval-independent). |
| `DeviceSimulator` | Fleet orchestrator. `@Scheduled` tick emits one `DeviceMessage` per meter through `TelemetryIngestPort`. |
| `SimulatorController` | Status endpoint (fleet size, active faults, target tenant). |

## 3. The transport-agnostic guarantee, demonstrated

The simulator emits through `TelemetryIngestPort.ingest(DeviceMessage)` — **the same port** LoRaWAN,
NB-IoT, MQTT and HTTP use. There is no "test mode" ingest path. This means the simulator exercises
the entire pipeline (dedup, persistence, device-state refresh, audit) exactly as production traffic,
and a meter simulated today is indistinguishable from a real meter in tomorrow's telemetry table
except for the `SIMULATOR` transport tag.

### Transport tag vs. device source

Two different things are called "simulator", and they sit at different levels:

| | Where | What it means |
|---|---|---|
| `DeviceMessage.Transports.SIMULATOR` | On the **reading** | This uplink was generated, not received. |
| `DeviceSource.SIMULATOR` | On the **device** (`iot.devices.source`) | This device is a stand-in; everything it will ever report is generated. |

The device-level flag exists because the reading-level tag cannot answer "exclude synthetic meters
from this water balance" without first querying telemetry — and cannot answer it at all for a
simulated device that has gone quiet.

Neither is a `CommunicationProfile`. Until `V1402`, `SIMULATOR` was a communication type, which made
it unsayable that a simulated meter emulates NB-IoT, and — because that profile declared no identity
field — left every such device with a `NULL` `network_address`, the one column ingestion resolves
through. A simulated device now declares the network it emulates and is addressed on it exactly as
the device it replaces would be.

## 4. Activation

`aquagrid.iot.transports.simulator=true`. Off by default — a production deployment never starts it.
Fleet size and interval are configurable (`-Daquagrid.simulator.fleet=200`,
`aquagrid.simulator.interval-ms`). The simulator resolves its target tenant through the published
`IdentityApi.findTenantByCode` (the platform operator tenant by default), never by reaching into
identity internals.

## 5. Demand model detail

```
intensity(h) = 0.45·N(h;7,1.2)  +  0.30·N(h;12.5,1.5)  +  0.55·N(h;20,1.8)  +  0.05·N(h;3,1.0)  +  0.04
```

The integral over 24h is ~1.0 by construction, so multiplying a household's daily volume by the
instantaneous intensity yields L/min in the correct magnitude. Per-meter baseline variation
(300–1000 L/day) reflects that not every household consumes the same.

## 6. Out of scope

- GUI controls (start/stop/pause/inject-fault-on-demand) — Module 6 deepening
- Network topology-aware simulation (meters grouped by DMA, with DMA-level supply pressure)
- Seasonality and climate — Module 28 (consumption analytics)

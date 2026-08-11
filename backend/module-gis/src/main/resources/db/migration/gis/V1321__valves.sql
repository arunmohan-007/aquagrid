-- =====================================================================================
-- Module 12 — Valve management
-- Owner : module-gis (version range V1300–V1399)
--
-- Valves are the network's control points. The defining capability is isolation tracing:
-- "which valves must be closed to isolate this section?" This migration adds the valve type
-- table (extending gis.assets) and an append-only operation log.
--
-- A valve sits on a network node (Module 11). Its status (OPEN/CLOSED) is what the isolation
-- trace uses as a stopping boundary: the walk halts at a CLOSED valve, defining the isolated
-- perimeter.
-- =====================================================================================

-- ---- Valves (type table) ----------------------------------------------------------------
CREATE TABLE gis.valves
(
    asset_id        UUID PRIMARY KEY,
    -- The network node this valve controls. A valve on a node regulates flow through every edge
    -- incident to that node, which is why isolation tracing works at the node level.
    node_id         UUID,
    valve_type      VARCHAR(30) NOT NULL DEFAULT 'GATE',
    diameter_mm     NUMERIC(7,1),
    -- Current operating state. Refreshed by the operate workflow and by SCADA.
    status          VARCHAR(20) NOT NULL DEFAULT 'CLOSED',
    -- The designed-default position. Most distribution valves are normally-open; PRVs and
    -- boundary valves are normally-closed. Drives the "return to normal" close-out step.
    normal_state    VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    -- For PRVs: the pressure setpoint. NULL for isolation valves.
    pressure_setpoint_bar NUMERIC(5,2),
    turns_to_operate INTEGER,        -- how many turns to fully open/close; field crew planning
    manufacturer    VARCHAR(80),
    model_number    VARCHAR(80),

    CONSTRAINT fk_valves_asset
        FOREIGN KEY (asset_id) REFERENCES gis.assets (id) ON DELETE CASCADE,
    CONSTRAINT fk_valves_node
        FOREIGN KEY (node_id) REFERENCES gis.network_nodes (id) ON DELETE SET NULL,
    CONSTRAINT ck_valves_type CHECK (valve_type IN
        ('GATE', 'BUTTERFLY', 'PRV', 'AIR_RELEASE', 'CHECK', 'BALL')),
    CONSTRAINT ck_valves_status CHECK (status IN ('OPEN', 'CLOSED', 'PARTIAL', 'FAULTY')),
    CONSTRAINT ck_valves_normal CHECK (normal_state IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_valves_turns CHECK (turns_to_operate IS NULL OR turns_to_operate BETWEEN 0 AND 100)
);

CREATE INDEX ix_valves_node ON gis.valves (node_id);
CREATE INDEX ix_valves_status ON gis.valves (status);

COMMENT ON TABLE gis.valves IS
    'Valves — network control points. status is the isolation-trace boundary; node_id links to the graph.';
COMMENT ON COLUMN gis.valves.normal_state IS
    'Designed-default position. Drives return-to-normal after an isolation event.';

-- ---- Valve operations (append-only audit) ----------------------------------------------
-- Every open/close action is recorded: who, when, from-state, to-state, reason. This is the
-- evidence chain for "was this valve operated correctly during the incident?" — a regulatory and
-- operational question after any supply event.
CREATE TABLE gis.valve_operations
(
    id              BIGSERIAL PRIMARY KEY,
    organization_id UUID         NOT NULL,
    valve_asset_id  UUID         NOT NULL,
    from_state      VARCHAR(20)  NOT NULL,
    to_state        VARCHAR(20)  NOT NULL,
    operated_by     UUID         NOT NULL,
    operated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reason          VARCHAR(300),
    work_order_id   UUID,        -- link to the work order that prompted the operation (Module 21)
    client_ip       INET,

    CONSTRAINT fk_valve_ops_valve
        FOREIGN KEY (valve_asset_id) REFERENCES gis.valves (asset_id) ON DELETE CASCADE
);

CREATE INDEX ix_valve_ops_valve_time ON gis.valve_operations (valve_asset_id, operated_at DESC);
CREATE INDEX ix_valve_ops_org_time ON gis.valve_operations (organization_id, operated_at DESC);

COMMENT ON TABLE gis.valve_operations IS
    'Append-only valve operation log. The evidence chain for every open/close action.';

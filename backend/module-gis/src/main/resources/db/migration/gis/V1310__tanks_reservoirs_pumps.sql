-- =====================================================================================
-- Modules 8, 9, 10 — Tank, Reservoir, Pump Station type tables
-- Owner : module-gis (version range V1300–V1399)
--
-- Each table references gis.assets.id (the supertype from V1300) and holds only the columns
-- specific to that asset kind. The supertype carries organisation, code, status, geometry and
-- attributes; these carry the engineering data an operator needs to run the asset.
--
-- A tank's live level, a reservoir's source, a pump station's rated curve — these are the fields
-- the dashboard widgets (Module 13+) will read once telemetry flows. They are nullable because an
-- asset can be registered before its commissioning data is known.
-- =====================================================================================

-- ---- Module 8: Tanks -------------------------------------------------------------------
-- A storage tank: elevated or ground-level, with capacity and current level. The current_level_m3
-- column is the operational heartbeat — refreshed by telemetry, rendered as a gauge on the map.
CREATE TABLE gis.tanks
(
    asset_id          UUID PRIMARY KEY,
    capacity_m3       NUMERIC(10,2) NOT NULL,
    current_level_m3  NUMERIC(10,2),
    -- Geometric/physical data needed for hydraulic models and overflow alarms.
    base_elevation_m  NUMERIC(8,2),
    overflow_elevation_m NUMERIC(8,2),
    inlet_elevation_m NUMERIC(8,2),
    tank_type         VARCHAR(30) NOT NULL DEFAULT 'ELEVATED',
    material          VARCHAR(40),

    CONSTRAINT fk_tanks_asset
        FOREIGN KEY (asset_id) REFERENCES gis.assets (id) ON DELETE CASCADE,
    CONSTRAINT ck_tanks_type CHECK (tank_type IN ('ELEVATED', 'GROUND', 'UNDERGROUND', 'SERVICE')),
    CONSTRAINT ck_tanks_capacity_positive CHECK (capacity_m3 > 0),
    CONSTRAINT ck_tanks_level_within CHECK (
        current_level_m3 IS NULL OR current_level_m3 BETWEEN 0 AND capacity_m3)
);

COMMENT ON TABLE gis.tanks IS
    'Storage tanks. capacity_m3 and current_level_m3 drive the dashboard level gauges and overflow alarms.';

-- ---- Module 9: Reservoirs --------------------------------------------------------------
-- A raw-water or treated-water reservoir: a large impoundment rather than a pressurised tank.
CREATE TABLE gis.reservoirs
(
    asset_id          UUID PRIMARY KEY,
    max_capacity_m3   NUMERIC(12,2) NOT NULL,
    current_volume_m3 NUMERIC(12,2),
    source_type       VARCHAR(30) NOT NULL,
    -- Surface area drives evaporation loss modelling (Module 26 water balance).
    surface_area_m2   NUMERIC(10,2),
    max_depth_m       NUMERIC(6,2),
    intake_elevation_m NUMERIC(8,2),

    CONSTRAINT fk_reservoirs_asset
        FOREIGN KEY (asset_id) REFERENCES gis.assets (id) ON DELETE CASCADE,
    CONSTRAINT ck_reservoirs_source CHECK (source_type IN
        ('RIVER', 'LAKE', 'GROUNDWATER', 'DESALINATION', 'TREATED')),
    CONSTRAINT ck_reservoirs_capacity_positive CHECK (max_capacity_m3 > 0),
    CONSTRAINT ck_reservoirs_volume_within CHECK (
        current_volume_m3 IS NULL OR current_volume_m3 BETWEEN 0 AND max_capacity_m3)
);

COMMENT ON TABLE gis.reservoirs IS
    'Raw or treated water reservoirs. surface_area_m3 feeds evaporation loss in water balance analysis.';

-- ---- Module 10: Pump Stations ----------------------------------------------------------
-- A pump station: one or more pumps with rated curves. The curve is JSONB because pump
-- manufacturers publish it as head/flow point sets — forcing it into columns would freeze the
-- schema to one vendor's convention.
CREATE TABLE gis.pump_stations
(
    asset_id          UUID PRIMARY KEY,
    pump_count        INTEGER NOT NULL CHECK (pump_count BETWEEN 1 AND 20),
    -- Rated values at the best-efficiency point; used for capacity planning and efficiency analysis.
    rated_flow_lpm    NUMERIC(10,2),
    rated_head_m      NUMERIC(10,2),
    rated_power_kw    NUMERIC(10,2),
    -- Operating status per pump slot: array of "RUNNING"|"STANDBY"|"FAULT"|"OFFLINE".
    pump_states       JSONB NOT NULL DEFAULT '[]'::jsonb,
    -- The head/flow curve as [{flow, head, efficiency}] points. Vendor-published; drives
    -- efficiency analysis (Module 28) and the hydraulic model.
    pump_curve        JSONB,
    suction_elevation_m NUMERIC(8,2),
    discharge_elevation_m NUMERIC(8,2),

    CONSTRAINT fk_pumps_asset
        FOREIGN KEY (asset_id) REFERENCES gis.assets (id) ON DELETE CASCADE
);

COMMENT ON TABLE gis.pump_stations IS
    'Pump stations. pump_curve is vendor head/flow data; pump_states is the live per-pump status.';

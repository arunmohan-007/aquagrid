-- =====================================================================================
-- Data Management — describe gis.layers, then seed the catalogue from the code as it stands
-- Owner : module-gis (version range V1300–V1399)
--
-- Two jobs.
--
-- 1. gis.layers becomes the layer master the Data Management screen lists, which needs one
--    thing it never had: a sentence saying what the layer is for. `title` is a label, not a
--    description, and an administrator choosing which layer to add a field to should not have
--    to infer "DMAs" from three letters.
--
-- 2. Every attribute the platform currently reads by name is written into
--    gis.layer_attribute_master as a system row. Until this runs the catalogue is empty and
--    the import hub would offer nothing — the module is metadata-driven, so the metadata has
--    to exist before the code that consumes it means anything. These rows are the code's own
--    field list, transcribed once; from here on the transcription runs the other way.
--
-- Idempotent throughout (ON CONFLICT DO NOTHING on the natural keys), so a tenant that
-- somehow already holds these rows is left alone.
-- =====================================================================================

ALTER TABLE gis.layers ADD COLUMN IF NOT EXISTS description VARCHAR(300);

UPDATE gis.layers SET description = d.description
FROM (VALUES
    ('METER',              'Consumer and bulk water meters, individually located'),
    ('VALVE',              'Isolation, air-release, scour and pressure-reducing valves'),
    ('PIPELINE',           'Transmission and distribution mains — the network graph''s edges'),
    ('HYDRANT',            'Fire and flushing hydrants'),
    ('TANK',               'Over head tanks and other elevated storage'),
    ('RESERVOIR',          'Ground-level reservoirs and raw-water storage'),
    ('PUMP_STATION',       'Pump houses and booster stations'),
    ('OPEN_WELL',          'Dug wells — a wide-mouthed groundwater source'),
    ('BORE_WELL',          'Boreholes — a single point per shaft'),
    ('DMA',                'District metered areas — hydraulic districts'),
    ('PANCHAYAT',          'Panchayat boundaries — local-government administrative areas'),
    ('SERVICE_CONNECTION', 'Consumer connection points on the network'),
    ('SENSOR',             'Pressure, flow and quality instruments')
) AS d(asset_type, description)
WHERE gis.layers.asset_type = d.asset_type
  AND gis.layers.description IS NULL;

COMMENT ON TABLE gis.layers IS
    'The layer master. Names the layers the map renders and the layers the attribute catalogue hangs off.';

-- =====================================================================================
-- The catalogue
--
-- One VALUES table, with a NULL asset_type meaning "every layer". The join below expands it
-- across every layer of every tenant — the same shape V1300, V1323 and V1324 use to seed the
-- layers themselves, and it inherits their scope: layers, and therefore this catalogue, exist
-- for the tenants present when the migration runs. A tenant provisioned later has no layer rows
-- either; that gap predates this module and is fixed for both at once when it is fixed.
--
-- Column order, once, so the rows below stay readable:
--   asset_type, field_name, display_name, description, data_type,
--   max_length, numeric_precision, numeric_scale, default_value, sample_value,
--   is_mandatory, is_unique, is_editable, is_visible,
--   storage, storage_table, storage_target, sort_order
-- =====================================================================================

INSERT INTO gis.layer_attribute_master
    (organization_id, layer_id, field_name, display_name, description, data_type,
     max_length, numeric_precision, numeric_scale, default_value, sample_value,
     is_mandatory, is_unique, is_editable, is_visible, is_active, is_system,
     storage, storage_table, storage_target, sort_order)
SELECT l.organization_id, l.id, a.field_name, a.display_name, a.description, a.data_type,
       a.max_length, a.numeric_precision, a.numeric_scale, a.default_value, a.sample_value,
       a.is_mandatory, a.is_unique, a.is_editable, a.is_visible, TRUE, TRUE,
       a.storage, a.storage_table, a.storage_target, a.sort_order
FROM gis.layers l
JOIN (VALUES
    -- ---- gis.assets supertype — present on every layer -----------------------------------
    (NULL::varchar, 'asset_code', 'Asset Code',
        'The platform''s unique key for the row within this tenant.',
        'TEXT', 80::integer, NULL::integer, NULL::integer, NULL::varchar, 'PL-0001',
        TRUE, TRUE, TRUE, TRUE, 'COLUMN', NULL::varchar, 'asset_code', 10),
    (NULL, 'name', 'Name',
        'Human-readable name shown on the map and in every list.',
        'TEXT', 200, NULL, NULL, NULL, 'Main Road Distribution Line',
        TRUE, FALSE, TRUE, TRUE, 'COLUMN', NULL, 'name', 20),
    (NULL, 'asset_type', 'Asset Type',
        'Which layer the row belongs to. Fixed at creation — moving an asset between layers is a re-import, not an edit.',
        'TEXT', 40, NULL, NULL, NULL, 'PIPELINE',
        TRUE, FALSE, FALSE, TRUE, 'COLUMN', NULL, 'asset_type', 30),
    (NULL, 'status', 'Status',
        'Lifecycle state: PLANNED, IN_SERVICE, OUT_OF_SERVICE, DECOMMISSIONED or DAMAGED.',
        'TEXT', 20, NULL, NULL, 'IN_SERVICE', 'IN_SERVICE',
        TRUE, FALSE, TRUE, TRUE, 'COLUMN', NULL, 'status', 40),
    (NULL, 'install_date', 'Install Date',
        'Date the asset was commissioned.',
        'DATE', NULL, NULL, NULL, NULL, '2019-06-14',
        FALSE, FALSE, TRUE, TRUE, 'COLUMN', NULL, 'install_date', 50),
    (NULL, 'decommission_date', 'Decommission Date',
        'Date the asset left service. Empty while it is in service.',
        'DATE', NULL, NULL, NULL, NULL, '2024-03-01',
        FALSE, FALSE, TRUE, TRUE, 'COLUMN', NULL, 'decommission_date', 60),
    (NULL, 'geom', 'Geometry',
        'The authoritative geometry in EPSG:4326. Comes from the source file, never from a mapped column.',
        'GEOMETRY', NULL, NULL, NULL, NULL, 'POINT (78.1420 11.6643)',
        TRUE, FALSE, FALSE, TRUE, 'GEOMETRY', NULL, 'geom', 70),
    /*
     * Longitude and latitude are not stored — they are how a CSV of points describes the
     * geometry it cannot otherwise carry, and the importer folds them into `geom`. Active, so
     * the import mapper offers them; not visible, so an export does not emit two derived
     * columns beside the geometry they were derived from. This is exactly the split the
     * active/visible pair exists for.
     */
    (NULL, 'lon', 'Longitude',
        'Decimal degrees east. CSV point import only — folded into the geometry.',
        'DOUBLE', NULL, NULL, NULL, NULL, '78.142000',
        FALSE, FALSE, FALSE, FALSE, 'GEOMETRY', NULL, 'lon', 80),
    (NULL, 'lat', 'Latitude',
        'Decimal degrees north. CSV point import only — folded into the geometry.',
        'DOUBLE', NULL, NULL, NULL, NULL, '11.664300',
        FALSE, FALSE, FALSE, FALSE, 'GEOMETRY', NULL, 'lat', 90),

    -- ---- Pipe-network deliverable fields (attribute bag) ----------------------------------
    -- What a survey contractor's shapefile actually carries. See BulkImportService.
    ('PIPELINE', 'slno', 'Sl. No.',
        'The deliverable''s own row number. Not unique across files.',
        'TEXT', 40, NULL, NULL, NULL, '17',
        FALSE, FALSE, TRUE, TRUE, 'JSONB', NULL, NULL, 110),
    ('PIPELINE', 'diameter', 'Diameter (source)',
        'Diameter exactly as the source file spelt it, before any engineering review.',
        'DECIMAL', NULL, 7, 1, NULL, '150.0',
        FALSE, FALSE, TRUE, TRUE, 'JSONB', NULL, NULL, 120),
    ('PIPELINE', 'digital_length', 'Digital Length',
        'Length the GIS measured off the digitised line, in metres. ArcGIS writes this as Shape_Length.',
        'DECIMAL', NULL, 12, 2, NULL, '432.75',
        FALSE, FALSE, TRUE, TRUE, 'JSONB', NULL, NULL, 130),
    ('PIPELINE', 'panchayat', 'Panchayat',
        'Local body the reach falls in. The dashboard groups network length by this.',
        'TEXT', 120, NULL, NULL, NULL, 'Kadambur',
        FALSE, FALSE, TRUE, TRUE, 'JSONB', NULL, NULL, 140),
    ('PIPELINE', 'start_date', 'Start Date',
        'Date the laying work commenced.',
        'DATE', NULL, NULL, NULL, NULL, '2021-11-02',
        FALSE, FALSE, TRUE, TRUE, 'JSONB', NULL, NULL, 150),

    -- ---- Facility register fields (attribute bag) -----------------------------------------
    -- Over head tanks, open wells and bore wells arrive as one numbered register per panchayat
    -- with the same descriptive columns, which is why the three share a field set.
    ('TANK', 'slno', 'Sl. No.', 'The register''s own row number.',
        'TEXT', 40, NULL, NULL, NULL, '4',
        FALSE, FALSE, TRUE, TRUE, 'JSONB', NULL, NULL, 110),
    ('TANK', 'asset_number', 'Asset Number',
        'The utility''s own reference for the structure. Repeats across panchayats, so it is not the asset code.',
        'TEXT', 60, NULL, NULL, NULL, 'OHT/KDB/04',
        FALSE, FALSE, TRUE, TRUE, 'JSONB', NULL, NULL, 115),
    ('TANK', 'panchayat', 'Panchayat', 'Local body the structure stands in.',
        'TEXT', 120, NULL, NULL, NULL, 'Kadambur',
        FALSE, FALSE, TRUE, TRUE, 'JSONB', NULL, NULL, 140),
    ('OPEN_WELL', 'slno', 'Sl. No.', 'The register''s own row number.',
        'TEXT', 40, NULL, NULL, NULL, '9',
        FALSE, FALSE, TRUE, TRUE, 'JSONB', NULL, NULL, 110),
    ('OPEN_WELL', 'asset_number', 'Asset Number',
        'The utility''s own reference for the well.',
        'TEXT', 60, NULL, NULL, NULL, 'OW/KDB/09',
        FALSE, FALSE, TRUE, TRUE, 'JSONB', NULL, NULL, 115),
    ('OPEN_WELL', 'panchayat', 'Panchayat', 'Local body the well stands in.',
        'TEXT', 120, NULL, NULL, NULL, 'Kadambur',
        FALSE, FALSE, TRUE, TRUE, 'JSONB', NULL, NULL, 140),
    ('BORE_WELL', 'slno', 'Sl. No.', 'The register''s own row number.',
        'TEXT', 40, NULL, NULL, NULL, '23',
        FALSE, FALSE, TRUE, TRUE, 'JSONB', NULL, NULL, 110),
    ('BORE_WELL', 'asset_number', 'Asset Number',
        'The utility''s own reference for the borehole.',
        'TEXT', 60, NULL, NULL, NULL, 'BW/KDB/23',
        FALSE, FALSE, TRUE, TRUE, 'JSONB', NULL, NULL, 115),
    ('BORE_WELL', 'panchayat', 'Panchayat', 'Local body the borehole stands in.',
        'TEXT', 120, NULL, NULL, NULL, 'Kadambur',
        FALSE, FALSE, TRUE, TRUE, 'JSONB', NULL, NULL, 140),

    -- ---- Typed detail tables ---------------------------------------------------------------
    -- Catalogued and exported; not offered for import mapping, because these tables carry
    -- constraints the supertype import cannot satisfy alone. See V1330's note on TYPE_TABLE.
    ('TANK', 'capacity_m3', 'Capacity (m³)', 'Nominal storage capacity.',
        'DECIMAL', NULL, 10, 2, NULL, '150.00',
        TRUE, FALSE, TRUE, TRUE, 'TYPE_TABLE', 'tanks', 'capacity_m3', 210),
    ('TANK', 'current_level_m3', 'Current Level (m³)', 'Live volume, refreshed by telemetry.',
        'DECIMAL', NULL, 10, 2, NULL, '96.40',
        FALSE, FALSE, FALSE, TRUE, 'TYPE_TABLE', 'tanks', 'current_level_m3', 215),
    ('TANK', 'tank_type', 'Tank Type', 'ELEVATED, GROUND, UNDERGROUND or SERVICE.',
        'TEXT', 30, NULL, NULL, 'ELEVATED', 'ELEVATED',
        TRUE, FALSE, TRUE, TRUE, 'TYPE_TABLE', 'tanks', 'tank_type', 220),
    ('TANK', 'material', 'Material', 'Construction material of the structure.',
        'TEXT', 40, NULL, NULL, NULL, 'RCC',
        FALSE, FALSE, TRUE, TRUE, 'TYPE_TABLE', 'tanks', 'material', 225),
    ('TANK', 'base_elevation_m', 'Base Elevation (m)', 'Ground level at the structure.',
        'DECIMAL', NULL, 8, 2, NULL, '212.50',
        FALSE, FALSE, TRUE, TRUE, 'TYPE_TABLE', 'tanks', 'base_elevation_m', 230),
    ('TANK', 'overflow_elevation_m', 'Overflow Elevation (m)', 'Level at which the tank overflows.',
        'DECIMAL', NULL, 8, 2, NULL, '224.00',
        FALSE, FALSE, TRUE, TRUE, 'TYPE_TABLE', 'tanks', 'overflow_elevation_m', 235),
    ('TANK', 'inlet_elevation_m', 'Inlet Elevation (m)', 'Level of the inlet pipe.',
        'DECIMAL', NULL, 8, 2, NULL, '223.10',
        FALSE, FALSE, TRUE, TRUE, 'TYPE_TABLE', 'tanks', 'inlet_elevation_m', 240),

    ('RESERVOIR', 'max_capacity_m3', 'Maximum Capacity (m³)', 'Design capacity of the water body.',
        'DECIMAL', NULL, 12, 2, NULL, '12500.00',
        TRUE, FALSE, TRUE, TRUE, 'TYPE_TABLE', 'reservoirs', 'max_capacity_m3', 210),
    ('RESERVOIR', 'current_volume_m3', 'Current Volume (m³)', 'Live volume, refreshed by telemetry.',
        'DECIMAL', NULL, 12, 2, NULL, '8210.00',
        FALSE, FALSE, FALSE, TRUE, 'TYPE_TABLE', 'reservoirs', 'current_volume_m3', 215),
    ('RESERVOIR', 'source_type', 'Source Type', 'RIVER, LAKE, GROUNDWATER, DESALINATION or TREATED.',
        'TEXT', 30, NULL, NULL, NULL, 'RIVER',
        TRUE, FALSE, TRUE, TRUE, 'TYPE_TABLE', 'reservoirs', 'source_type', 220),
    ('RESERVOIR', 'surface_area_m2', 'Surface Area (m²)', 'Drives evaporation loss in the water balance.',
        'DECIMAL', NULL, 10, 2, NULL, '3400.00',
        FALSE, FALSE, TRUE, TRUE, 'TYPE_TABLE', 'reservoirs', 'surface_area_m2', 225),
    ('RESERVOIR', 'max_depth_m', 'Maximum Depth (m)', 'Depth at the deepest point.',
        'DECIMAL', NULL, 6, 2, NULL, '6.50',
        FALSE, FALSE, TRUE, TRUE, 'TYPE_TABLE', 'reservoirs', 'max_depth_m', 230),
    ('RESERVOIR', 'intake_elevation_m', 'Intake Elevation (m)', 'Level of the intake structure.',
        'DECIMAL', NULL, 8, 2, NULL, '198.20',
        FALSE, FALSE, TRUE, TRUE, 'TYPE_TABLE', 'reservoirs', 'intake_elevation_m', 235),

    ('PUMP_STATION', 'pump_count', 'Pump Count', 'Number of pump slots at the station.',
        'INTEGER', NULL, NULL, NULL, NULL, '3',
        TRUE, FALSE, TRUE, TRUE, 'TYPE_TABLE', 'pump_stations', 'pump_count', 210),
    ('PUMP_STATION', 'rated_flow_lpm', 'Rated Flow (L/min)', 'Flow at the best-efficiency point.',
        'DECIMAL', NULL, 10, 2, NULL, '1800.00',
        FALSE, FALSE, TRUE, TRUE, 'TYPE_TABLE', 'pump_stations', 'rated_flow_lpm', 215),
    ('PUMP_STATION', 'rated_head_m', 'Rated Head (m)', 'Head at the best-efficiency point.',
        'DECIMAL', NULL, 10, 2, NULL, '42.00',
        FALSE, FALSE, TRUE, TRUE, 'TYPE_TABLE', 'pump_stations', 'rated_head_m', 220),
    ('PUMP_STATION', 'rated_power_kw', 'Rated Power (kW)', 'Nameplate power draw.',
        'DECIMAL', NULL, 10, 2, NULL, '15.00',
        FALSE, FALSE, TRUE, TRUE, 'TYPE_TABLE', 'pump_stations', 'rated_power_kw', 225),
    ('PUMP_STATION', 'suction_elevation_m', 'Suction Elevation (m)', 'Level of the suction side.',
        'DECIMAL', NULL, 8, 2, NULL, '196.00',
        FALSE, FALSE, TRUE, TRUE, 'TYPE_TABLE', 'pump_stations', 'suction_elevation_m', 230),
    ('PUMP_STATION', 'discharge_elevation_m', 'Discharge Elevation (m)', 'Level of the discharge side.',
        'DECIMAL', NULL, 8, 2, NULL, '238.00',
        FALSE, FALSE, TRUE, TRUE, 'TYPE_TABLE', 'pump_stations', 'discharge_elevation_m', 235),

    ('PIPELINE', 'diameter_mm', 'Diameter (mm)', 'Reviewed engineering diameter, as used by the hydraulic model.',
        'DECIMAL', NULL, 7, 1, NULL, '150.0',
        FALSE, FALSE, TRUE, TRUE, 'TYPE_TABLE', 'pipelines', 'diameter_mm', 210),
    ('PIPELINE', 'material', 'Material', 'Pipe material — DI, PVC, HDPE, CI.',
        'TEXT', 40, NULL, NULL, NULL, 'DI',
        FALSE, FALSE, TRUE, TRUE, 'TYPE_TABLE', 'pipelines', 'material', 215),
    ('PIPELINE', 'length_m', 'Length (m)', 'Ellipsoidal length, computed by the database from the geometry.',
        'DECIMAL', NULL, 10, 2, NULL, '432.61',
        FALSE, FALSE, FALSE, TRUE, 'TYPE_TABLE', 'pipelines', 'length_m', 220),
    ('PIPELINE', 'flow_direction', 'Flow Direction', 'BIDIRECTIONAL, FROM_TO or TO_FROM.',
        'TEXT', 20, NULL, NULL, 'BIDIRECTIONAL', 'BIDIRECTIONAL',
        TRUE, FALSE, TRUE, TRUE, 'TYPE_TABLE', 'pipelines', 'flow_direction', 225),
    ('PIPELINE', 'roughness', 'Roughness (C)', 'Hazen-Williams coefficient, for head-loss calculation.',
        'DECIMAL', NULL, 4, 3, NULL, '130.000',
        FALSE, FALSE, TRUE, TRUE, 'TYPE_TABLE', 'pipelines', 'roughness', 230),
    ('PIPELINE', 'pressure_class', 'Pressure Class (bar)', 'PN rating of the pipe.',
        'DECIMAL', NULL, 6, 2, NULL, '10.00',
        FALSE, FALSE, TRUE, TRUE, 'TYPE_TABLE', 'pipelines', 'pressure_class', 235),

    ('VALVE', 'valve_type', 'Valve Type', 'GATE, BUTTERFLY, PRV, AIR_RELEASE, CHECK or BALL.',
        'TEXT', 30, NULL, NULL, 'GATE', 'GATE',
        TRUE, FALSE, TRUE, TRUE, 'TYPE_TABLE', 'valves', 'valve_type', 210),
    -- gis.valves.status is the valve's open/closed position, not the asset's lifecycle status,
    -- and both are on this layer. The field name disambiguates; storage_target keeps the link.
    ('VALVE', 'valve_status', 'Valve Position', 'OPEN, CLOSED, PARTIAL or FAULTY. The isolation-trace boundary.',
        'TEXT', 20, NULL, NULL, 'CLOSED', 'OPEN',
        TRUE, FALSE, TRUE, TRUE, 'TYPE_TABLE', 'valves', 'status', 215),
    ('VALVE', 'normal_state', 'Normal State', 'Designed-default position: OPEN or CLOSED.',
        'TEXT', 20, NULL, NULL, 'OPEN', 'OPEN',
        TRUE, FALSE, TRUE, TRUE, 'TYPE_TABLE', 'valves', 'normal_state', 220),
    ('VALVE', 'diameter_mm', 'Diameter (mm)', 'Nominal bore.',
        'DECIMAL', NULL, 7, 1, NULL, '100.0',
        FALSE, FALSE, TRUE, TRUE, 'TYPE_TABLE', 'valves', 'diameter_mm', 225),
    ('VALVE', 'pressure_setpoint_bar', 'Pressure Setpoint (bar)', 'PRVs only. Empty for isolation valves.',
        'DECIMAL', NULL, 5, 2, NULL, '3.50',
        FALSE, FALSE, TRUE, TRUE, 'TYPE_TABLE', 'valves', 'pressure_setpoint_bar', 230),
    ('VALVE', 'turns_to_operate', 'Turns to Operate', 'How many turns to fully open or close. Field-crew planning.',
        'INTEGER', NULL, NULL, NULL, NULL, '14',
        FALSE, FALSE, TRUE, TRUE, 'TYPE_TABLE', 'valves', 'turns_to_operate', 235),
    ('VALVE', 'manufacturer', 'Manufacturer', 'Valve manufacturer.',
        'TEXT', 80, NULL, NULL, NULL, 'Kirloskar',
        FALSE, FALSE, TRUE, TRUE, 'TYPE_TABLE', 'valves', 'manufacturer', 240),
    ('VALVE', 'model_number', 'Model Number', 'Manufacturer''s model reference.',
        'TEXT', 80, NULL, NULL, NULL, 'KV-DN100-PN16',
        FALSE, FALSE, TRUE, TRUE, 'TYPE_TABLE', 'valves', 'model_number', 245)
) AS a(asset_type, field_name, display_name, description, data_type,
       max_length, numeric_precision, numeric_scale, default_value, sample_value,
       is_mandatory, is_unique, is_editable, is_visible,
       storage, storage_table, storage_target, sort_order)
  ON a.asset_type IS NULL OR a.asset_type = l.asset_type
ON CONFLICT (layer_id, field_name) DO NOTHING;

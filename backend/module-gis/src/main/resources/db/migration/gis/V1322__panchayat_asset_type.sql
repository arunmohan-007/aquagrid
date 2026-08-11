-- =====================================================================================
-- Add PANCHAYAT to the permitted asset types
--
-- The Import Hub offers two kinds of boundary: DMA (a hydraulic district, defined by the
-- network) and Panchayat (a local-government area, defined by administration). They are not
-- interchangeable — a leakage figure reported per DMA answers an engineering question, the same
-- figure per Panchayat answers a governance one — so folding Panchayats into DMA would corrupt
-- both reports.
--
-- Two CHECK constraints enumerate the asset types; both are rebuilt here. A new value cannot be
-- appended in place, so each constraint is dropped and recreated with the full list.
-- =====================================================================================

ALTER TABLE gis.assets DROP CONSTRAINT IF EXISTS ck_assets_type;
ALTER TABLE gis.assets
    ADD CONSTRAINT ck_assets_type CHECK (asset_type IN
        ('METER', 'VALVE', 'PIPELINE', 'HYDRANT', 'TANK', 'RESERVOIR',
         'PUMP_STATION', 'DMA', 'PANCHAYAT', 'SERVICE_CONNECTION', 'SENSOR'));

ALTER TABLE gis.layers DROP CONSTRAINT IF EXISTS ck_layers_asset_type;
ALTER TABLE gis.layers
    ADD CONSTRAINT ck_layers_asset_type CHECK (asset_type IN
        ('METER', 'VALVE', 'PIPELINE', 'HYDRANT', 'TANK', 'RESERVOIR',
         'PUMP_STATION', 'DMA', 'PANCHAYAT', 'SERVICE_CONNECTION', 'SENSOR'));

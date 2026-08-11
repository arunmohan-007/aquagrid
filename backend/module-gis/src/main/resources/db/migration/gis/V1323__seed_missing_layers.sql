-- =====================================================================================
-- Seed the layer rows for the three asset types that never had one
--
-- V1300 seeded eight layers. SENSOR and SERVICE_CONNECTION existed in the asset-type CHECK
-- from the start and PANCHAYAT was added in V1322, but none of the three ever received a
-- gis.layers row. The consequence was silent and expensive: the Import Hub happily accepted a
-- file of sensors, the rows landed in gis.assets, and the map had no layer to draw or toggle
-- them with — so a successful import looked like a failed one.
--
-- Idempotent on (organization_id, code), which uq_layers_org_code already enforces, so this is
-- safe on a tenant that somehow acquired these rows by hand.
-- =====================================================================================

INSERT INTO gis.layers (organization_id, code, title, asset_type, is_visible, sort_order)
SELECT o.id, l.code, l.title, l.asset_type::varchar, l.is_visible, l.sort_order
FROM core.organizations o
CROSS JOIN (VALUES
    -- Off by default: an operator opening the map wants the everyday operational layers, and
    -- service connections in particular are the densest layer a utility owns.
    ('sensors',     'Sensors',              'SENSOR',             FALSE, 45),
    ('connections', 'Service Connections',  'SERVICE_CONNECTION', FALSE, 75),
    ('panchayats',  'Panchayat Boundaries', 'PANCHAYAT',          FALSE, 85)
) AS l(code, title, asset_type, is_visible, sort_order)
ON CONFLICT (organization_id, code) DO NOTHING;

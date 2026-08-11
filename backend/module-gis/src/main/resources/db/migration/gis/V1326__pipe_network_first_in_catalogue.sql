-- =====================================================================================
-- The pipe network sorts first in the layer catalogue
--
-- V1300 gave pipelines sort_order 30, behind meters and valves — an ordering that made sense
-- when four layers were on by default and none of them led. Now that the network is the only
-- layer the console opens with (V1325), it should also be the first thing the layers panel and
-- the legend list, rather than the third.
--
-- 5 rather than 0: it leaves room for a layer that must precede the network later without
-- renumbering the whole catalogue.
-- =====================================================================================

UPDATE gis.layers SET sort_order = 5 WHERE asset_type = 'PIPELINE';

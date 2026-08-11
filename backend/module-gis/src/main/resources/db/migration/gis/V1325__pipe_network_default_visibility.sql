-- =====================================================================================
-- The pipe network is the only layer on by default
--
-- V1300 seeded four layers visible (meters, valves, pipelines, hydrants). On a real tenant
-- that opens the console over three dense point layers drawn on top of each other, and the
-- network — the thing every other asset hangs off — is the hardest of the four to read.
--
-- The new default is: pipelines on, everything else off. The operator adds what they need
-- rather than subtracting what they don't, and the map opens on the network so the auto-zoom
-- (GET /gis/layers/{code}/extent, applied on first load) has something to frame.
--
-- is_visible is a per-tenant default for the *initial* state of the layer panel; the panel's
-- own toggles are client-side only, so rewriting it here changes what the console opens with
-- and nothing an operator has persisted.
-- =====================================================================================

UPDATE gis.layers SET is_visible = (asset_type = 'PIPELINE');

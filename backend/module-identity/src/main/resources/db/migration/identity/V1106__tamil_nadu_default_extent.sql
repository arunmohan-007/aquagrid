-- =====================================================================================
-- Move the platform operator's default map extent to Tamil Nadu
--
-- V1103 seeded the SYSTEM tenant on Thiruvananthapuram at zoom 11 — a city-level view of
-- Kerala. The deployment's service area is Tamil Nadu, so the map opened on the wrong state
-- and every operator had to pan before doing anything.
--
-- Applied as a new migration rather than an edit to V1103: that migration has already run on
-- existing databases, and rewriting it would fail Flyway's checksum validation on every one of
-- them. A fresh database runs V1103 then this, and lands in the same place.
--
--   centroid     POINT(78.6569 11.1271) — the geographic centre of Tamil Nadu, EPSG:4326,
--                stored longitude-first as PostGIS expects.
--   default_zoom 7 — frames the whole state (roughly 76.2°–80.4°E, 8.1°–13.6°N) rather than
--                a single city. Operators zoom in; they should not have to zoom out first.
-- =====================================================================================

UPDATE core.organizations
SET centroid     = ST_SetSRID(ST_MakePoint(78.6569, 11.1271), 4326),
    default_zoom = 7
WHERE code = 'SYSTEM';

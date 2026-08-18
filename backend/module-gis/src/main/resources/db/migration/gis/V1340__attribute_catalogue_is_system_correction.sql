-- =====================================================================================
-- V1331 seeded every catalogue row with is_system = TRUE, not only the ones the platform
-- actually reads by name. A field the application never references — a pipe's Diameter, a
-- tank's Material, anything living in the JSONB bag or a typed detail table — is exactly the
-- kind of attribute an administrator is meant to own in full (V1330's comment on is_system:
-- "Administrator-created attributes have this false and are editable in full"). Left it as-is
-- there (already applied, checksummed) and corrected here instead.
--
-- The true system set is the gis.assets supertype columns the Java code reads by name:
-- asset_code, name, asset_type, status, install_date, decommission_date, geom, lon, lat —
-- storage COLUMN or GEOMETRY. Everything else (JSONB, TYPE_TABLE) is not.
-- =====================================================================================

UPDATE gis.layer_attribute_master
SET is_system = FALSE
WHERE is_system = TRUE
  AND storage NOT IN ('COLUMN', 'GEOMETRY');

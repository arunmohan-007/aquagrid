-- =====================================================================================
-- Asset Code stops being editable after creation, same as Geometry — both are the platform's own
-- values (the identity key and the authoritative shape), not something a user fills in on a form
-- after the row exists. V1331 seeded asset_code with is_editable = TRUE by mistake; left as-is
-- there (already applied, checksummed) and corrected here instead, for every layer of every
-- tenant the catalogue currently covers.
-- =====================================================================================

UPDATE gis.layer_attribute_master
SET is_editable = FALSE
WHERE field_name = 'asset_code'
  AND is_system = TRUE
  AND is_editable = TRUE;

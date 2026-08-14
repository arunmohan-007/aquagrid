-- =====================================================================================
-- DATE attributes move from YYYY-MM-DD to dd-Mon-yyyy (e.g. 02-Jan-2025) — AttributeDataType.DATE
-- now parses and stores that form instead of ISO. Two stores carry text in the old format and
-- both need to move with the code, or an administrator editing an untouched DATE attribute (its
-- own catalogue row, e.g. the Pipelines layer's Start Date) would immediately hit the new
-- validator on a sample value nobody changed, and a grid rendering already-imported data would
-- show the two formats side by side.
--
-- Guarded by the ISO regex throughout, so this only ever touches a value still in the old form —
-- safe to leave in place rather than requiring a follow-up cleanup migration.
-- =====================================================================================

UPDATE gis.layer_attribute_master
SET default_value = to_char(to_date(default_value, 'YYYY-MM-DD'), 'DD-Mon-YYYY')
WHERE data_type = 'DATE'
  AND default_value ~ '^\d{4}-\d{2}-\d{2}$';

UPDATE gis.layer_attribute_master
SET sample_value = to_char(to_date(sample_value, 'YYYY-MM-DD'), 'DD-Mon-YYYY')
WHERE data_type = 'DATE'
  AND sample_value ~ '^\d{4}-\d{2}-\d{2}$';

-- Every asset's JSONB attribute bag, one DATE-typed field at a time. A bag can hold keys from
-- several layers' worth of history, so this walks the catalogue rather than naming a field.
DO $$
DECLARE
    attr RECORD;
BEGIN
    FOR attr IN
        SELECT DISTINCT field_name
        FROM gis.layer_attribute_master
        WHERE data_type = 'DATE' AND storage = 'JSONB'
    LOOP
        UPDATE gis.assets
        SET attributes = jsonb_set(
            attributes,
            ARRAY[attr.field_name],
            to_jsonb(to_char(to_date(attributes ->> attr.field_name, 'YYYY-MM-DD'), 'DD-Mon-YYYY'))
        )
        WHERE attributes ? attr.field_name
          AND attributes ->> attr.field_name ~ '^\d{4}-\d{2}-\d{2}$';
    END LOOP;
END $$;

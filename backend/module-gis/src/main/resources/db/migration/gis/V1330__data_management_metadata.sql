-- =====================================================================================
-- Data Management — the attribute metadata catalogue
-- Owner : module-gis (version range V1300–V1399)
--
-- This is the schema half of the module that makes the platform's field definitions data
-- rather than code. Everything that reads or writes a layer's attributes — bulk import,
-- export, and the dynamic forms that follow — resolves them from here, so adding a field to
-- a layer is an INSERT rather than a release.
--
-- Design decisions, each of which is load-bearing:
--
--  * **gis.layers IS the layer master.** There is no new `layer_master` table. A second table
--    naming the same layers would have to be kept in step with the one the map, the tile
--    endpoint and the import hub already read, and the first time they disagreed the map and
--    the metadata would describe different products. `gis.layers` already carries the tenant,
--    code, title and asset type; this migration adds the descriptive columns the Data
--    Management screen needs (V1331) and hangs attributes off its primary key.
--
--  * **New attributes need no DDL.** `gis.assets.attributes` is a JSONB bag with a GIN index,
--    present since V1300 for exactly this purpose. An attribute row therefore declares *where*
--    its value lives — `storage` — rather than assuming a column exists. Attributes that
--    correspond to real columns on gis.assets are seeded as storage = 'COLUMN' and are locked
--    against rename and retype; everything an administrator creates is storage = 'JSONB' and
--    is free. This is what lets "create an attribute" be a runtime operation without granting
--    the application DDL rights on its own schema, which is a privilege no web tier should
--    hold.
--
--  * **Soft delete, always.** There is no DELETE path. `is_active = false` hides an attribute
--    from imports, exports and forms while every value already written under it stays in the
--    JSONB bag, readable the moment the attribute is reactivated. Deleting the definition
--    would orphan data that nothing else describes.
--
--  * `numeric_precision` / `numeric_scale` / `max_length` rather than `precision`, `scale`,
--    `length`. All three are reserved or non-reserved keywords in SQL:2016 and Postgres will
--    accept some and not others depending on position. The module validates user-supplied
--    field names against the same list (ReservedWords.java); its own columns should not need
--    the exemption.
-- =====================================================================================

-- ---- Layer attribute master ------------------------------------------------------------
CREATE TABLE gis.layer_attribute_master
(
    id                UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    organization_id   UUID         NOT NULL,
    layer_id          UUID         NOT NULL,

    -- 63 is Postgres's identifier limit (NAMEDATALEN - 1). A field name that could never
    -- become a column, a JSON key we can index, or a shapefile DBF field is not a field name.
    field_name        VARCHAR(63)  NOT NULL,
    display_name      VARCHAR(120) NOT NULL,
    description       VARCHAR(500),

    data_type         VARCHAR(20)  NOT NULL,
    max_length        INTEGER,
    numeric_precision INTEGER,
    numeric_scale     INTEGER,
    default_value     VARCHAR(255),
    sample_value      VARCHAR(255),

    is_mandatory      BOOLEAN      NOT NULL DEFAULT FALSE,
    is_unique         BOOLEAN      NOT NULL DEFAULT FALSE,
    is_editable       BOOLEAN      NOT NULL DEFAULT TRUE,
    is_visible        BOOLEAN      NOT NULL DEFAULT TRUE,
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,

    /*
     * A system attribute is one the application's own code reads by name — asset_code, geom,
     * install_date. Its label, description, sample and visibility are the tenant's to change;
     * its name, type and mandatory-ness are not, because a rename would break the Java that
     * reads it and a retype would break the column it lives in. Administrator-created
     * attributes have this false and are editable in full.
     */
    is_system         BOOLEAN      NOT NULL DEFAULT FALSE,

    /*
     * Where the value physically lives:
     *   JSONB      — a key in gis.assets.attributes. The default, and the only kind an
     *                administrator can create. Import, export and forms all handle it.
     *   COLUMN     — a real column on gis.assets; `storage_target` names it.
     *   GEOMETRY   — the geometry column itself, or one of the lon/lat pseudo-fields a CSV
     *                import builds a point from. Never written to the attribute bag.
     *   TYPE_TABLE — a column on a strongly-typed detail table (gis.tanks, gis.valves …),
     *                named by `storage_table` + `storage_target`.
     *
     * TYPE_TABLE attributes are catalogued and exported but not offered for import mapping.
     * Those tables carry constraints the supertype import cannot satisfy on its own — a tank
     * row requires capacity_m3 NOT NULL and > 0, a pipeline row requires its own LineString —
     * so a generic writer would either have to weaken them or fail on most files. They are
     * written through their own typed endpoints (AssetTypeService, PipelineService,
     * ValveService), and appear here so the catalogue describes the layer completely rather
     * than only the part one code path happens to handle.
     */
    storage           VARCHAR(10)  NOT NULL DEFAULT 'JSONB',
    storage_table     VARCHAR(63),
    storage_target    VARCHAR(63),

    sort_order        INTEGER      NOT NULL DEFAULT 100,

    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by        UUID,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by        UUID,
    version           BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_layer_attr_organization
        FOREIGN KEY (organization_id) REFERENCES core.organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_layer_attr_layer
        FOREIGN KEY (layer_id) REFERENCES gis.layers (id) ON DELETE CASCADE,

    CONSTRAINT ck_layer_attr_data_type CHECK (data_type IN
        ('TEXT', 'INTEGER', 'LONG_INTEGER', 'SHORT_INTEGER', 'DECIMAL', 'DOUBLE',
         'BOOLEAN', 'DATE', 'DATE_TIME', 'TIME', 'UUID', 'GEOMETRY')),
    CONSTRAINT ck_layer_attr_storage CHECK (storage IN
        ('COLUMN', 'JSONB', 'GEOMETRY', 'TYPE_TABLE')),

    /*
     * Field-name grammar, enforced at the last line of defence as well as in the service. The
     * service produces the good error message; this stops a bad row arriving by any other
     * route — a script, a restore, a future module — because a field name that is not a legal
     * identifier will eventually be interpolated somewhere that cannot quote it.
     */
    CONSTRAINT ck_layer_attr_field_name CHECK (field_name ~ '^[a-z][a-z0-9_]*$'),

    CONSTRAINT ck_layer_attr_length CHECK (max_length IS NULL OR max_length BETWEEN 1 AND 10485760),
    CONSTRAINT ck_layer_attr_precision CHECK (numeric_precision IS NULL OR numeric_precision BETWEEN 1 AND 1000),
    -- Scale cannot exceed precision: NUMERIC(4,6) is not a narrower number, it is a rejected type.
    CONSTRAINT ck_layer_attr_scale CHECK (
        numeric_scale IS NULL
            OR (numeric_precision IS NOT NULL AND numeric_scale BETWEEN 0 AND numeric_precision)),
    CONSTRAINT ck_layer_attr_storage_target CHECK (
        storage = 'JSONB' OR storage_target IS NOT NULL),
    CONSTRAINT ck_layer_attr_storage_table CHECK (
        (storage = 'TYPE_TABLE') = (storage_table IS NOT NULL))
);

/*
 * Uniqueness covers inactive rows deliberately. Reusing the name of a deactivated attribute
 * would silently adopt every value already written under it — which is either exactly what the
 * administrator wanted, in which case they should reactivate it, or a data-corruption bug they
 * will not notice for months. Reactivation is the supported path and the error message says so.
 */
CREATE UNIQUE INDEX uq_layer_attr_layer_field
    ON gis.layer_attribute_master (layer_id, field_name);
CREATE INDEX ix_layer_attr_org_layer ON gis.layer_attribute_master (organization_id, layer_id);
CREATE INDEX ix_layer_attr_active ON gis.layer_attribute_master (organization_id, is_active);

CREATE TRIGGER trg_layer_attr_updated_at
    BEFORE UPDATE ON gis.layer_attribute_master
    FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

COMMENT ON TABLE gis.layer_attribute_master IS
    'The attribute catalogue. Import, export and dynamic forms read field definitions from here rather than from code.';
COMMENT ON COLUMN gis.layer_attribute_master.storage IS
    'JSONB = a key in gis.assets.attributes; COLUMN = a gis.assets column; GEOMETRY = the geometry or its lon/lat pseudo-fields; TYPE_TABLE = a typed detail table column, catalogued and exported but not import-mappable.';
COMMENT ON COLUMN gis.layer_attribute_master.is_system IS
    'True for attributes the application reads by name. Their name, type and mandatory flag are locked; labels are not.';

-- ---- Validation rules ------------------------------------------------------------------
-- Constraints that are worth expressing per attribute but not worth a column on the master:
-- a regex for a consumer number, a value range for a diameter, an allowed-value list for a
-- material. Kept as rows so a utility can add one without a release, and evaluated by the
-- importer in the order given.
CREATE TABLE gis.attribute_validation_rules
(
    id              UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL,
    attribute_id    UUID         NOT NULL,

    rule_type       VARCHAR(20)  NOT NULL,
    -- Interpretation depends on rule_type: a number for MIN/MAX_VALUE and MIN/MAX_LENGTH, a
    -- regular expression for PATTERN, a comma-separated list for ALLOWED_VALUES.
    rule_value      VARCHAR(500) NOT NULL,
    -- What the operator sees when the rule rejects a row. A rule that says
    -- "does not match ^[0-9]{8}$" is a rule nobody can act on.
    error_message   VARCHAR(300),
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order      INTEGER      NOT NULL DEFAULT 100,

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by      UUID,
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_attr_rule_organization
        FOREIGN KEY (organization_id) REFERENCES core.organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_attr_rule_attribute
        FOREIGN KEY (attribute_id) REFERENCES gis.layer_attribute_master (id) ON DELETE CASCADE,
    CONSTRAINT ck_attr_rule_type CHECK (rule_type IN
        ('MIN_LENGTH', 'MAX_LENGTH', 'MIN_VALUE', 'MAX_VALUE', 'PATTERN', 'ALLOWED_VALUES'))
);

CREATE INDEX ix_attr_rule_attribute ON gis.attribute_validation_rules (attribute_id, is_active);

CREATE TRIGGER trg_attr_rule_updated_at
    BEFORE UPDATE ON gis.attribute_validation_rules
    FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

COMMENT ON TABLE gis.attribute_validation_rules IS
    'Per-attribute value constraints, evaluated by the importer and by dynamic forms.';

-- ---- Saved import mappings -------------------------------------------------------------
-- A utility receives the same contractor's deliverable every quarter, with the same column
-- headings. Re-mapping twenty columns by hand each time is where mis-mappings come from, so a
-- mapping is saved once under a profile name and offered back on the next import of that layer.
--
-- A NULL attribute_id means "ignore this column", stored rather than omitted: the difference
-- between a column the operator decided to drop and one they never saw is the difference
-- between a reviewed mapping and an incomplete one.
CREATE TABLE gis.import_field_mapping
(
    id              UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL,
    layer_id        UUID         NOT NULL,
    profile_name    VARCHAR(120) NOT NULL,
    source_field    VARCHAR(200) NOT NULL,
    attribute_id    UUID,

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by      UUID,
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_import_map_organization
        FOREIGN KEY (organization_id) REFERENCES core.organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_import_map_layer
        FOREIGN KEY (layer_id) REFERENCES gis.layers (id) ON DELETE CASCADE,
    -- The mapping outlives the attribute being deactivated but not its deletion; since there is
    -- no delete path, this is belt-and-braces for a manual clean-up.
    CONSTRAINT fk_import_map_attribute
        FOREIGN KEY (attribute_id) REFERENCES gis.layer_attribute_master (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_import_map_profile_source
    ON gis.import_field_mapping (organization_id, layer_id, profile_name, source_field);

CREATE TRIGGER trg_import_map_updated_at
    BEFORE UPDATE ON gis.import_field_mapping
    FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

COMMENT ON TABLE gis.import_field_mapping IS
    'Saved source-column to attribute mappings, per layer and profile. NULL attribute_id means the column is ignored.';

-- ---- Change history --------------------------------------------------------------------
-- Append-only. The platform's audit trail (audit.audit_events) records that an attribute
-- changed and who did it; this records *what the definition was*, so a value imported two years
-- ago can be read against the definition in force when it was written. An attribute whose
-- max_length was widened, or whose type moved from TEXT to DECIMAL, has data on both sides of
-- the change and no way to interpret it without this table.
CREATE TABLE gis.layer_attribute_history
(
    id              BIGSERIAL PRIMARY KEY,
    organization_id UUID         NOT NULL,
    attribute_id    UUID         NOT NULL,
    layer_id        UUID         NOT NULL,
    field_name      VARCHAR(63)  NOT NULL,

    change_type     VARCHAR(20)  NOT NULL,
    -- The full definition before and after, as JSON. Storing the whole snapshot rather than a
    -- diff means reading history never requires replaying every prior row to reconstruct state.
    previous_state  JSONB,
    new_state       JSONB,
    -- Free text from the administrator: why the field was widened, why it was retired.
    change_reason   VARCHAR(500),

    changed_by      UUID,
    changed_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_attr_history_organization
        FOREIGN KEY (organization_id) REFERENCES core.organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_attr_history_attribute
        FOREIGN KEY (attribute_id) REFERENCES gis.layer_attribute_master (id) ON DELETE CASCADE,
    CONSTRAINT ck_attr_history_type CHECK (change_type IN
        ('CREATED', 'UPDATED', 'DEACTIVATED', 'REACTIVATED'))
);

CREATE INDEX ix_attr_history_attribute ON gis.layer_attribute_history (attribute_id, changed_at DESC);
CREATE INDEX ix_attr_history_org_time ON gis.layer_attribute_history (organization_id, changed_at DESC);

COMMENT ON TABLE gis.layer_attribute_history IS
    'Append-only definition history. Answers "what did this field mean when that row was written".';

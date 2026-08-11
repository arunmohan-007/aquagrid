-- =====================================================================================
-- Device Data Configuration — the parameter catalogue
-- Owner : module-iot (version range V1400–V1499)
--
-- What a device *is* was settled by V1400–V1403; what it *sends* was, until now, decided in
-- Java. `MetricCatalog` declares eight metrics with their units, kinds and categories, and
-- anything a device reported outside that list fell through to an "Other" bucket with a null
-- unit and no opinion attached. That is the right default and the wrong ceiling: a pump
-- monitor reports voltage, current, power and running hours, and none of them can be given a
-- unit, a range or a place on a dashboard without a release.
--
-- This is the same move Data Management made for GIS layers (gis.layer_attribute_master,
-- V1330) and it is made here for the same reason and in the same shape: **field definitions
-- are data, not code**. A parameter is an INSERT, not a deployment.
--
-- The load-bearing difference from the GIS catalogue, and the point of the whole module:
--
--   **Configuration decides how data is USED, never whether it is ALLOWED IN.**
--
-- A layer attribute governs a write the platform controls — an import file it may reject and
-- ask the operator to fix. Telemetry is not that. A device in the ground sends what its
-- firmware sends; refusing a packet because it carried a field nobody catalogued would
-- discard a reading that cannot be re-requested, to enforce a table an administrator has
-- simply not filled in yet. So nothing here is ever consulted to reject a packet. Configured
-- parameters get units, ranges, dashboards, alarms and reports. Unconfigured ones get stored
-- anyway (V1406) and listed for configuration (`device_discovered_parameter` below), and the
-- day someone configures one, the history is already there.
--
-- Three tables, plus a unit lookup:
--
--   iot.unit_master                 the selectable units, as rows rather than a Java constant
--   iot.device_data_parameter       the catalogue itself, at device-type or device scope
--   iot.device_parameter_history    append-only definition history
--   iot.device_discovered_parameter what arrived that nothing describes
-- =====================================================================================


-- =====================================================================================
-- 1. Unit master
-- =====================================================================================
--
-- A lookup rather than an enum in Java, because the brief for this module asks for exactly
-- that and because the platform has been here before: the metric unit used to live in a
-- `switch` in TelemetryIngestService and its label in a TypeScript map, and adding one meant
-- edits in two languages that nothing checked for agreement.
--
-- `organization_id` is NULLABLE and that is the whole design of the table. A NULL row is
-- platform-supplied and visible to every tenant — which is what lets this migration seed the
-- units without knowing which organisations exist, now or later. A tenant that needs a unit
-- the platform does not ship (a district that meters in kilolitres, a vendor reporting in
-- MGD) inserts its own row against its own id, and sees it alongside the shipped ones.

CREATE TABLE iot.unit_master
(
    id              UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    organization_id UUID,                                  -- NULL = platform-supplied, all tenants
    -- 20 to match iot.device_readings.unit, which is where these values end up. A unit that
    -- cannot be written onto a reading is not a unit this platform can offer.
    code            VARCHAR(20)  NOT NULL,
    label           VARCHAR(80)  NOT NULL,
    -- What is being measured. Groups the picker, and stops "m" (length) being offered beside
    -- "m3" (volume) as though they were alternatives for the same reading.
    quantity        VARCHAR(24)  NOT NULL,
    description     VARCHAR(200),
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order      INTEGER      NOT NULL DEFAULT 100,

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by      UUID,
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_unit_master_organization
        FOREIGN KEY (organization_id) REFERENCES core.organizations (id) ON DELETE CASCADE,
    CONSTRAINT ck_unit_master_quantity CHECK (quantity IN
        ('LENGTH', 'VOLUME', 'FLOW', 'PRESSURE', 'RATIO', 'VOLTAGE', 'CURRENT', 'POWER',
         'ENERGY', 'TEMPERATURE', 'FREQUENCY', 'TIME', 'SIGNAL', 'COUNT', 'OTHER'))
);

-- COALESCE in the key for the reason V1404's transport statistics needed it: two NULLs are
-- not equal in SQL, so a plain (organization_id, code) index would let a tenant insert a
-- second "bar" beside the platform's and see it twice in the picker.
CREATE UNIQUE INDEX uq_unit_master_org_code
    ON iot.unit_master (COALESCE(organization_id, '00000000-0000-0000-0000-000000000000'::uuid), code);
CREATE INDEX ix_unit_master_active ON iot.unit_master (is_active, sort_order);

CREATE TRIGGER trg_unit_master_updated_at
    BEFORE UPDATE ON iot.unit_master
    FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

COMMENT ON TABLE iot.unit_master IS
    'Selectable units for device parameters. Rows, not a Java enum, so a tenant can add one without a release.';
COMMENT ON COLUMN iot.unit_master.organization_id IS
    'NULL for platform-supplied units, which every tenant sees. Set for a tenant''s own additions.';

INSERT INTO iot.unit_master (organization_id, code, label, quantity, sort_order)
VALUES
    (NULL, 'm',      'Metre',                    'LENGTH',      10),
    (NULL, 'cm',     'Centimetre',               'LENGTH',      20),
    (NULL, 'mm',     'Millimetre',               'LENGTH',      30),
    (NULL, 'L',      'Litre',                    'VOLUME',      40),
    (NULL, 'm3',     'Cubic metre',              'VOLUME',      50),
    (NULL, 'L/min',  'Litres per minute',        'FLOW',        60),
    (NULL, 'm3/hr',  'Cubic metres per hour',    'FLOW',        70),
    (NULL, 'bar',    'Bar',                      'PRESSURE',    80),
    (NULL, 'psi',    'Pounds per square inch',   'PRESSURE',    90),
    (NULL, '%',      'Percent',                  'RATIO',      100),
    (NULL, 'V',      'Volt',                     'VOLTAGE',    110),
    (NULL, 'A',      'Ampere',                   'CURRENT',    120),
    (NULL, 'kW',     'Kilowatt',                 'POWER',      130),
    (NULL, 'kWh',    'Kilowatt hour',            'ENERGY',     140),
    (NULL, '°C',     'Degrees Celsius',          'TEMPERATURE',150),
    (NULL, 'Hz',     'Hertz',                    'FREQUENCY',  160),
    (NULL, 'hours',  'Hours',                    'TIME',       170),
    (NULL, 'dBm',    'Decibel-milliwatts',       'SIGNAL',     180),
    (NULL, 'dB',     'Decibel',                  'SIGNAL',     190),
    -- Already written on every flag reading since V1400. Catalogued so the picker offers what
    -- the data actually contains rather than a tidier list the existing rows contradict.
    (NULL, 'flag',   'Flag (set or clear)',      'COUNT',      200);


-- =====================================================================================
-- 2. The parameter catalogue
-- =====================================================================================
--
-- **Two scopes, one table.** A parameter is declared either for a device type — the template
-- every water level sensor inherits — or for one device, which overrides the template where a
-- particular unit differs. They are one table because they are one thing described at two
-- levels of specificity: a second table would duplicate every column and force every reader
-- to union them, and the first divergence between the two copies would be a parameter that
-- validates differently depending on which screen created it.
--
-- Resolution is the obvious one and lives in ParameterResolver: take the device type's rows,
-- then let the device's own rows of the same name replace them. `sort_order` and the flags
-- come from whichever row won, entire — a partial merge would leave an operator unable to say
-- what a device's configuration actually is by reading either row.
--
-- **No FK to iot.devices, and none to a device-type table**, because there is no device-type
-- table: device type is a CHECK-constrained VARCHAR on iot.devices (V1401), and this column
-- is the same vocabulary. The device_id column does carry a FK — a parameter override for a
-- deleted device describes nothing — but device *types* are values, not rows.

CREATE TABLE iot.device_data_parameter
(
    id                UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    organization_id   UUID         NOT NULL,

    -- Which question this row answers: "what does every device of this type send?" or "what
    -- does this one device send?". Kept as its own column rather than inferred from which of
    -- the two id columns is populated, so the CHECK below can be written at all and so an
    -- index can be partial on it.
    scope             VARCHAR(12)  NOT NULL,
    device_type       VARCHAR(40),
    device_id         UUID,

    -- The canonical name, as it lands in iot.device_readings.metric — which is VARCHAR(60).
    -- 63 would be a name the catalogue accepts and the readings table truncates.
    parameter_name    VARCHAR(60)  NOT NULL,
    display_name      VARCHAR(120) NOT NULL,
    description       VARCHAR(500),

    data_type         VARCHAR(20)  NOT NULL,
    unit              VARCHAR(20),
    category          VARCHAR(20)  NOT NULL DEFAULT 'OTHER',

    /*
     * The vendor's spelling of this parameter in the payload, where it differs from the
     * canonical name — `totalVolume` for `volume`, `motorTemp` for `motor_temperature`.
     *
     * NULL means "same as parameter_name", which is the common case and deliberately not
     * defaulted to a copy: a copy would have to be kept in step by hand every time the name
     * changed, and the first time it was not, the parameter would silently stop matching.
     *
     * MetricVocabulary still canonicalises the well-known spellings in code. This column is
     * how a tenant adds one for a vendor the platform has never seen, without a release.
     */
    payload_key       VARCHAR(120),

    /*
     * Mandatory means "a packet without this is incomplete", NOT "reject a packet without
     * this". A missing mandatory value is recorded as a reading with a NULL value and quality
     * MISSING (V1406), which is a fact an operator can query and act on. Refusing the packet
     * would discard the parameters that *did* arrive in order to complain about the one that
     * did not.
     */
    is_mandatory      BOOLEAN      NOT NULL DEFAULT FALSE,
    dashboard_visible BOOLEAN      NOT NULL DEFAULT TRUE,
    use_for_alarm     BOOLEAN      NOT NULL DEFAULT FALSE,
    use_for_reports   BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Range. Violations are stored and marked OUT_OF_RANGE, never dropped — a pressure of
    -- 47 bar on a 10 bar main is the most important reading of the day.
    min_value         DOUBLE PRECISION,
    max_value         DOUBLE PRECISION,
    -- Digits after the point. Applied by rounding, not by rejection: a device with more
    -- resolution than the field declares has better data, not wrong data.
    decimal_precision INTEGER,

    sample_value      VARCHAR(255),
    default_value     VARCHAR(255),

    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order        INTEGER      NOT NULL DEFAULT 100,

    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by        UUID,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by        UUID,
    version           BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_device_param_organization
        FOREIGN KEY (organization_id) REFERENCES core.organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_device_param_device
        FOREIGN KEY (device_id) REFERENCES iot.devices (id) ON DELETE CASCADE,

    CONSTRAINT ck_device_param_scope CHECK (scope IN ('DEVICE_TYPE', 'DEVICE')),
    -- Exactly one target, and it must match the scope. Without this a row could name both and
    -- the resolver would have to invent a precedence rule that no reader of the table could see.
    CONSTRAINT ck_device_param_target CHECK (
        (scope = 'DEVICE_TYPE' AND device_type IS NOT NULL AND device_id IS NULL)
            OR (scope = 'DEVICE' AND device_id IS NOT NULL AND device_type IS NULL)),

    CONSTRAINT ck_device_param_data_type CHECK (data_type IN
        ('TEXT', 'INTEGER', 'LONG_INTEGER', 'DECIMAL', 'DOUBLE', 'BOOLEAN',
         'DATE', 'DATE_TIME', 'JSON', 'ARRAY')),
    -- The categories MetricCatalog.Category already declares. Reused rather than re-invented:
    -- the telemetry screen groups readings by these, and a category this table could hold but
    -- that screen could not render would be a group nobody ever sees.
    CONSTRAINT ck_device_param_category CHECK (category IN
        ('CONSUMPTION', 'PRESSURE', 'DEVICE_HEALTH', 'CONDITION', 'ENVIRONMENT', 'OTHER')),

    -- Same grammar as gis.layer_attribute_master.field_name, and for the same reason: a
    -- parameter name is a JSON key, a column heading in an export and a series name in a
    -- chart. Anything that is not a legal identifier will eventually be interpolated
    -- somewhere that cannot quote it.
    CONSTRAINT ck_device_param_name CHECK (parameter_name ~ '^[a-z][a-z0-9_]*$'),

    CONSTRAINT ck_device_param_range CHECK (
        min_value IS NULL OR max_value IS NULL OR min_value <= max_value),
    CONSTRAINT ck_device_param_precision CHECK (
        decimal_precision IS NULL OR decimal_precision BETWEEN 0 AND 10)
);

/*
 * One definition per name per target. Partial indexes rather than one index over a COALESCE,
 * because the two scopes genuinely are two namespaces: a device may legitimately override a
 * parameter its type already declares, and a single unique key spanning both would forbid the
 * override — which is the feature.
 *
 * Inactive rows are covered, as in the GIS catalogue: reusing the name of a deactivated
 * parameter would silently adopt every reading already stored under it. Reactivation is the
 * supported path and the service's error message says so.
 */
CREATE UNIQUE INDEX uq_device_param_type_name
    ON iot.device_data_parameter (organization_id, device_type, parameter_name)
    WHERE scope = 'DEVICE_TYPE';
CREATE UNIQUE INDEX uq_device_param_device_name
    ON iot.device_data_parameter (organization_id, device_id, parameter_name)
    WHERE scope = 'DEVICE';

-- The resolver's two reads, one per scope.
CREATE INDEX ix_device_param_type ON iot.device_data_parameter (organization_id, device_type, is_active)
    WHERE scope = 'DEVICE_TYPE';
CREATE INDEX ix_device_param_device ON iot.device_data_parameter (organization_id, device_id, is_active)
    WHERE scope = 'DEVICE';
-- The grid's cross-cutting filters: "every alarm parameter", "every parameter in dBm".
CREATE INDEX ix_device_param_org_name ON iot.device_data_parameter (organization_id, parameter_name);

CREATE TRIGGER trg_device_param_updated_at
    BEFORE UPDATE ON iot.device_data_parameter
    FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

COMMENT ON TABLE iot.device_data_parameter IS
    'What a device is expected to send, and what to do with it. Consulted for units, validation, dashboards, alarms and reports — never to decide whether a packet is accepted.';
COMMENT ON COLUMN iot.device_data_parameter.scope IS
    'DEVICE_TYPE rows are the template every device of that type inherits; DEVICE rows override the template for one device.';
COMMENT ON COLUMN iot.device_data_parameter.payload_key IS
    'The vendor''s spelling in the payload, where it differs from parameter_name. NULL means they are the same.';
COMMENT ON COLUMN iot.device_data_parameter.is_mandatory IS
    'A packet without this parameter is incomplete, not invalid. The absence is recorded as a MISSING-quality reading; the packet is still accepted.';


-- =====================================================================================
-- 3. Definition history
-- =====================================================================================
--
-- Append-only, and the same shape as gis.layer_attribute_history for the same reason: the
-- platform's audit trail records *that* a parameter changed and who changed it, while this
-- records *what the definition was*, so a reading written two years ago can be read against
-- the definition in force when it was written. A parameter whose unit moved from L/min to
-- m3/hr has data on both sides of the change and no way to interpret it without this table.

CREATE TABLE iot.device_parameter_history
(
    id              BIGSERIAL PRIMARY KEY,
    organization_id UUID         NOT NULL,
    parameter_id    UUID         NOT NULL,
    parameter_name  VARCHAR(60)  NOT NULL,
    scope           VARCHAR(12)  NOT NULL,
    device_type     VARCHAR(40),
    device_id       UUID,

    change_type     VARCHAR(20)  NOT NULL,
    -- The whole definition either side, not a diff: reading history should never require
    -- replaying every prior row to reconstruct state.
    previous_state  JSONB,
    new_state       JSONB,
    change_reason   VARCHAR(500),

    changed_by      UUID,
    changed_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_param_history_organization
        FOREIGN KEY (organization_id) REFERENCES core.organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_param_history_parameter
        FOREIGN KEY (parameter_id) REFERENCES iot.device_data_parameter (id) ON DELETE CASCADE,
    CONSTRAINT ck_param_history_type CHECK (change_type IN
        ('CREATED', 'UPDATED', 'DEACTIVATED', 'REACTIVATED'))
);

CREATE INDEX ix_param_history_parameter ON iot.device_parameter_history (parameter_id, changed_at DESC);
CREATE INDEX ix_param_history_org_time ON iot.device_parameter_history (organization_id, changed_at DESC);

COMMENT ON TABLE iot.device_parameter_history IS
    'Append-only definition history. Answers "what did this parameter mean when that reading was written".';


-- =====================================================================================
-- 4. Discovered parameters
-- =====================================================================================
--
-- The queue that makes "accept everything" actionable rather than merely tolerant.
--
-- Storing an unrecognised field (V1406) means nothing is lost. It does not mean anyone finds
-- out. A parameter the platform has no definition for is invisible on every dashboard, absent
-- from every report and outside every alarm rule — indistinguishable, from the operator's
-- chair, from a field the device never sent. This table is the difference: one row per
-- (device, parameter) the receiver has seen and the catalogue does not describe, with a
-- sample, a guessed type and a count, and a button that opens the configuration form
-- pre-filled.
--
-- Aggregated per device rather than appended per packet. A pump reporting powerFactor every
-- five minutes would otherwise produce a hundred thousand identical discovery rows a year to
-- convey one fact. The occurrence count and the two timestamps carry the volume; the raw rows
-- themselves are in iot.device_raw_telemetry, which is where "show me the actual data" goes.
--
-- **IGNORED deletes nothing.** It removes the parameter from the pending list and nothing
-- else: the raw payloads stay, the counters keep climbing, and a parameter ignored last year
-- can be configured this year with its whole history intact.

CREATE TABLE iot.device_discovered_parameter
(
    id               UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    organization_id  UUID         NOT NULL,
    device_id        UUID         NOT NULL,
    -- Denormalised so the discovery list needs no join to render, and so a row survives the
    -- device being re-typed. module-iot avoids joins it does not need on the hot path, and
    -- this table is written from inside packet reception.
    device_code      VARCHAR(60),
    device_type      VARCHAR(40),

    -- The key exactly as it arrived. NOT canonicalised and NOT lower-cased: the operator has
    -- to recognise it in the vendor's documentation, and `motorTemp` and `motor_temp` are
    -- different strings in a payload even where they would be the same parameter.
    parameter_name   VARCHAR(120) NOT NULL,
    -- What the value looked like, as text. Text rather than typed, because the whole point is
    -- that the platform does not yet know the type — see detected_data_type, which is a guess.
    sample_value     VARCHAR(255),
    detected_data_type VARCHAR(20),

    first_seen_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    occurrences      BIGINT       NOT NULL DEFAULT 1,

    status           VARCHAR(12)  NOT NULL DEFAULT 'PENDING',
    -- Set when the discovery was turned into a definition, so the list can show the outcome
    -- rather than simply dropping the row and losing the fact that it was ever unknown.
    parameter_id     UUID,
    resolved_by      UUID,
    resolved_at      TIMESTAMPTZ,

    version          BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_discovered_organization
        FOREIGN KEY (organization_id) REFERENCES core.organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_discovered_device
        FOREIGN KEY (device_id) REFERENCES iot.devices (id) ON DELETE CASCADE,
    CONSTRAINT fk_discovered_parameter
        FOREIGN KEY (parameter_id) REFERENCES iot.device_data_parameter (id) ON DELETE SET NULL,
    CONSTRAINT ck_discovered_status CHECK (status IN ('PENDING', 'CONFIGURED', 'IGNORED')),
    CONSTRAINT ck_discovered_type CHECK (detected_data_type IS NULL OR detected_data_type IN
        ('TEXT', 'INTEGER', 'LONG_INTEGER', 'DECIMAL', 'DOUBLE', 'BOOLEAN',
         'DATE', 'DATE_TIME', 'JSON', 'ARRAY'))
);

-- The conflict target for the discovery upsert. Reception does INSERT .. ON CONFLICT DO
-- UPDATE, so a field seen a thousand times an hour costs one row and one counter increment
-- per packet rather than a read followed by a write.
CREATE UNIQUE INDEX uq_discovered_device_parameter
    ON iot.device_discovered_parameter (device_id, parameter_name);
-- The screen's default query: what still needs a decision, newest first.
CREATE INDEX ix_discovered_pending ON iot.device_discovered_parameter (organization_id, last_seen_at DESC)
    WHERE status = 'PENDING';
CREATE INDEX ix_discovered_org_status ON iot.device_discovered_parameter (organization_id, status);

COMMENT ON TABLE iot.device_discovered_parameter IS
    'Parameters devices have sent that the catalogue does not describe. One row per device and name, upserted on every sighting. IGNORED hides a row from the queue and deletes nothing.';
COMMENT ON COLUMN iot.device_discovered_parameter.parameter_name IS
    'The payload key verbatim — not canonicalised — because the operator has to match it against the vendor''s documentation.';
COMMENT ON COLUMN iot.device_discovered_parameter.detected_data_type IS
    'Inferred from the observed value. A guess that pre-fills the configuration form, never a decision.';

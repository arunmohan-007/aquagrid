-- =====================================================================================
-- Layer Style Management — how a layer is drawn, as data
-- Owner : module-gis (version range V1300–V1399)
--
-- Until now a layer's appearance lived in `frontend/src/features/gis/layerStyle.ts`: a hard-coded
-- map from layer code to colour and shape, with a grey fallback for anything it did not name. That
-- worked while the layer set was fixed in a migration. It cannot survive layers being created at
-- runtime — a new layer would render grey until someone shipped a release, which is the same
-- failure mode Data Management removed for fields.
--
-- So styles become rows, and the server composes them into MapLibre layer specifications. The
-- client applies what it is given and decides nothing about appearance.
--
-- Design decisions:
--
--  * **Symbology is JSONB, not a property-per-row table.** The brief suggests a `layer_style_property`
--    table. A row per paint property would be an EAV of a document that is already a document:
--    every read becomes a join and a pivot, every write a diff, and the values are heterogeneous
--    (colours, widths, opacities, dash arrays) so the column would be text and the types would live
--    in code anyway. MapLibre's own model is a JSON object; storing a JSON object is the honest
--    representation. What genuinely *is* relational — one rule per category or range — is a table.
--
--  * **The stored JSONB is AquaGrid's symbology vocabulary, not raw MapLibre paint.** Storing raw
--    paint would weld the database to one renderer's spelling and let the style editor write
--    expressions nobody validated. `MapLibreStyleComposer` translates; swapping renderers is then a
--    composer, not a data migration.
--
--  * **One default style per layer, enforced by a partial unique index.** "Which style does the map
--    use" must have exactly one answer, and the place to guarantee that is the database — a service
--    that clears the old default before setting the new one is correct until two administrators do
--    it at once.
--
--  * **Rules are ordered and evaluated first-match.** A MapLibre `case` expression is first-match,
--    so the stored order has to be the evaluated order or the preview and the map disagree.
-- =====================================================================================

-- ---- Style ------------------------------------------------------------------------------
CREATE TABLE gis.layer_style
(
    id              UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL,
    layer_id        UUID         NOT NULL,

    name            VARCHAR(120) NOT NULL,
    description     VARCHAR(500),

    /*
     * SIMPLE     — one symbol for every feature.
     * CATEGORICAL— one symbol per value of a field: status ACTIVE green, FAULT red.
     * GRADUATED  — one symbol per numeric band: water_level 0–20 red, 20–50 amber.
     * RULE_BASED — arbitrary field/operator/value predicates, first match wins.
     *
     * The distinction is not cosmetic: it decides which MapLibre expression is composed (`match`
     * for categorical, `step` for graduated, `case` for rule-based) and which columns of
     * layer_style_rule mean anything. A categorical style's rules carry a value; a graduated
     * style's carry a band.
     */
    style_type      VARCHAR(20)  NOT NULL DEFAULT 'SIMPLE',

    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    is_default      BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Style-level zoom window, narrower than or equal to the layer's. A layer visible from z10 can
    -- carry a detailed style that only appears from z15, which is how a network reads as lines at
    -- district scale and as annotated assets at street scale without two layers.
    min_zoom        SMALLINT     NOT NULL DEFAULT 0,
    max_zoom        SMALLINT     NOT NULL DEFAULT 24,

    /*
     * The base symbol, in AquaGrid's vocabulary. Which keys are meaningful follows the layer's
     * geometry — point (renderMode, size, fillColor, strokeColor, strokeWidth, opacity, icon,
     * iconSize), line (lineColor, lineWidth, lineOpacity, dashPattern, lineCap, lineJoin), polygon
     * (fillColor, fillOpacity, outlineColor, outlineWidth, outlineOpacity, dashPattern).
     *
     * Every geometry's keys live in one document rather than three columns because a GEOMETRY layer
     * genuinely carries all three at once — the facility layers hold footprints and locations in the
     * same layer, and a style that could only describe one of them would leave the other unpainted.
     */
    symbol          JSONB        NOT NULL DEFAULT '{}'::jsonb,

    /*
     * Label configuration: enabled, field, textSize, textColor, haloColor, haloWidth, minZoom,
     * maxZoom. `field` names an attribute in gis.layer_attribute_master — Data Management's
     * catalogue — and is validated against it on write. There is no second field list.
     */
    label           JSONB        NOT NULL DEFAULT '{}'::jsonb,

    /*
     * For CATEGORICAL and GRADUATED: the attribute the rules classify on. Also validated against
     * the Data Management catalogue. Null for SIMPLE, and for RULE_BASED where each rule names its
     * own field.
     */
    classify_field  VARCHAR(63),

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by      UUID,
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_layer_style_organization
        FOREIGN KEY (organization_id) REFERENCES core.organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_layer_style_layer
        FOREIGN KEY (layer_id) REFERENCES gis.layers (id) ON DELETE CASCADE,
    CONSTRAINT ck_layer_style_type CHECK (style_type IN
        ('SIMPLE', 'CATEGORICAL', 'GRADUATED', 'RULE_BASED')),
    CONSTRAINT ck_layer_style_zoom CHECK (
        min_zoom BETWEEN 0 AND 24 AND max_zoom BETWEEN 0 AND 24 AND min_zoom <= max_zoom),
    -- A classified style with nothing to classify on is a style that silently renders as SIMPLE.
    CONSTRAINT ck_layer_style_classify CHECK (
        style_type NOT IN ('CATEGORICAL', 'GRADUATED') OR classify_field IS NOT NULL)
);

CREATE UNIQUE INDEX uq_layer_style_layer_name ON gis.layer_style (layer_id, lower(name));
CREATE INDEX ix_layer_style_org_layer ON gis.layer_style (organization_id, layer_id);

/*
 * Exactly one default per layer, and only among active styles: deactivating the default should not
 * be blocked by its own defaultness, and a deactivated style is not what the map draws. The map
 * falls back to the platform's built-in symbology when a layer has no active default, which is what
 * makes deactivation safe rather than a blank layer.
 */
CREATE UNIQUE INDEX uq_layer_style_one_default
    ON gis.layer_style (layer_id) WHERE is_default AND is_active;

CREATE TRIGGER trg_layer_style_updated_at
    BEFORE UPDATE ON gis.layer_style
    FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

COMMENT ON TABLE gis.layer_style IS
    'How a layer is drawn. Composed into MapLibre layer specifications server-side so no layer''s appearance is decided in JavaScript.';
COMMENT ON COLUMN gis.layer_style.symbol IS
    'Base symbology in AquaGrid''s vocabulary, not raw MapLibre paint. MapLibreStyleComposer translates.';

-- ---- Rules ------------------------------------------------------------------------------
-- One row per category, band or predicate. Relational rather than an array inside the style
-- document because these are the rows an administrator adds, removes and reorders one at a time,
-- and because a rule needs to be validated against the attribute catalogue individually.
CREATE TABLE gis.layer_style_rule
(
    id              UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL,
    style_id        UUID         NOT NULL,

    /*
     * The field this rule tests. Redundant with layer_style.classify_field for CATEGORICAL and
     * GRADUATED — deliberately, because RULE_BASED styles let each rule name its own field, and a
     * nullable column that is sometimes the authority and sometimes a copy is harder to read than
     * one that always says what it tests.
     */
    field_name      VARCHAR(63)  NOT NULL,

    operator        VARCHAR(20)  NOT NULL,

    /*
     * Operands, as text. A single value for EQ/NEQ/LT/LTE/GT/GTE, the low bound for BETWEEN, and
     * unused for IS_NULL/IS_NOT_NULL. Text rather than typed columns because the same rule table
     * serves TEXT, numeric and date attributes, and the attribute's declared data type in
     * gis.layer_attribute_master is what says how to read it — the type lives in one place, which
     * is the point of the catalogue.
     */
    value_1         VARCHAR(255),
    -- The high bound for BETWEEN; unused otherwise.
    value_2         VARCHAR(255),
    -- The member list for IN, one value per element. A jsonb array rather than a comma-separated
    -- string because a category value can legitimately contain a comma.
    value_list      JSONB,

    -- What the legend calls this class. 'Faulty' reads better than 'status = FAULT'.
    label           VARCHAR(120),

    -- The symbology this class overrides the base with. Same vocabulary as layer_style.symbol;
    -- keys absent here fall through to the base symbol, so a rule that only changes colour says
    -- only that.
    symbol          JSONB        NOT NULL DEFAULT '{}'::jsonb,

    -- Evaluation order. MapLibre `case` is first-match, so this is not cosmetic: overlapping bands
    -- resolve to whichever comes first, both here and in the preview.
    sort_order      INTEGER      NOT NULL DEFAULT 100,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by      UUID,
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_style_rule_organization
        FOREIGN KEY (organization_id) REFERENCES core.organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_style_rule_style
        FOREIGN KEY (style_id) REFERENCES gis.layer_style (id) ON DELETE CASCADE,
    CONSTRAINT ck_style_rule_operator CHECK (operator IN
        ('EQ', 'NEQ', 'LT', 'LTE', 'GT', 'GTE', 'IN', 'BETWEEN', 'IS_NULL', 'IS_NOT_NULL')),
    -- Operand arity, at the last line of defence. A BETWEEN with no upper bound composes into a
    -- MapLibre expression that throws in the worker and blanks the map, which is a long way from
    -- where the mistake was made.
    CONSTRAINT ck_style_rule_operands CHECK (
        CASE operator
            WHEN 'IS_NULL'     THEN TRUE
            WHEN 'IS_NOT_NULL' THEN TRUE
            WHEN 'IN'          THEN value_list IS NOT NULL AND jsonb_array_length(value_list) > 0
            WHEN 'BETWEEN'     THEN value_1 IS NOT NULL AND value_2 IS NOT NULL
            ELSE value_1 IS NOT NULL
        END),
    CONSTRAINT ck_style_rule_field_name CHECK (field_name ~ '^[a-z][a-z0-9_]*$')
);

CREATE INDEX ix_style_rule_style ON gis.layer_style_rule (style_id, sort_order);

CREATE TRIGGER trg_style_rule_updated_at
    BEFORE UPDATE ON gis.layer_style_rule
    FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

COMMENT ON TABLE gis.layer_style_rule IS
    'One category, band or predicate of a classified style. Evaluated first-match in sort_order, which is how MapLibre''s case expression behaves.';

-- ---- Migrate the styles that already exist ----------------------------------------------
/*
 * The colours below are lifted verbatim from layerStyle.ts, which is where every existing layer's
 * appearance has lived. Seeding them means the map looks identical the moment this ships — the
 * styles become editable without becoming different, which is the only acceptable outcome for a
 * migration that takes over rendering.
 *
 * The reasoning behind the palette moves with it, because it is not arbitrary: valves and hydrants
 * keep red and orange because those are the assets an operator is sent to touch during an incident,
 * and the wells and sensors reach for fuchsia and pink only because the aqua family was spent and
 * green and amber are reserved for alarm severity.
 *
 * `render` selects which of the three symbol families the composer emits. Facility layers are
 * GEOMETRY and get all three, because a tank may be a footprint or a location depending on how the
 * project was surveyed.
 */
INSERT INTO gis.layer_style
    (organization_id, layer_id, name, description, style_type, is_active, is_default, symbol, label)
SELECT l.organization_id,
       l.id,
       'Default',
       'The platform''s built-in symbology for ' || l.title || ', migrated from the client palette so the map looks unchanged.',
       'SIMPLE',
       TRUE,
       TRUE,
       jsonb_build_object(
           'fillColor',    s.colour,
           'glowColor',    s.glow,
           'strokeColor',  'rgba(255,255,255,0.9)',
           'strokeWidth',  1.5,
           'size',         5,
           'opacity',      1.0,
           'lineColor',    s.colour,
           'lineWidth',    3,
           'lineOpacity',  1.0,
           'lineCap',      'round',
           'lineJoin',     'round',
           'fillOpacity',  0.14,
           'outlineColor', s.glow,
           'outlineWidth', 1.5,
           'outlineOpacity', 1.0),
       -- Labels off by default: the migrated appearance must match what the map draws today, and
       -- today it draws no labels. Turning them on is one edit in Layer Styles.
       jsonb_build_object('enabled', FALSE)
FROM gis.layers l
JOIN (VALUES
    ('meters',      '#3B82F6', '#93C5FD'),
    ('valves',      '#EF4444', '#FCA5A5'),
    ('pipelines',   '#06B6D4', '#67E8F9'),
    ('hydrants',    '#F97316', '#FDBA74'),
    ('tanks',       '#A855F7', '#D8B4FE'),
    ('reservoirs',  '#0EA5E9', '#7DD3FC'),
    ('pumps',       '#6366F1', '#A5B4FC'),
    ('dmas',        '#64748B', '#94A3B8'),
    ('open-wells',  '#0891B2', '#67E8F9'),
    ('bore-wells',  '#C026D3', '#F0ABFC'),
    ('sensors',     '#EC4899', '#F9A8D4'),
    ('connections', '#14B8A6', '#5EEAD4'),
    ('panchayats',  '#8B5CF6', '#C4B5FD')
) AS s(code, colour, glow) ON s.code = l.code
ON CONFLICT DO NOTHING;

/*
 * Any layer the palette above did not name — one a tenant added by hand before this migration —
 * still needs a default style, or it would render with the composer's fallback and be the one layer
 * an administrator cannot restyle. The fallback grey is the same one layerStyle.ts used, so these
 * layers also look exactly as they did.
 */
INSERT INTO gis.layer_style
    (organization_id, layer_id, name, description, style_type, is_active, is_default, symbol, label)
SELECT l.organization_id, l.id, 'Default',
       'Built-in symbology for ' || l.title || '.',
       'SIMPLE', TRUE, TRUE,
       jsonb_build_object(
           'fillColor', '#B9C2D0', 'glowColor', '#CBD5E1',
           'strokeColor', 'rgba(255,255,255,0.9)', 'strokeWidth', 1.5,
           'size', 5, 'opacity', 1.0,
           'lineColor', '#B9C2D0', 'lineWidth', 3, 'lineOpacity', 1.0,
           'lineCap', 'round', 'lineJoin', 'round',
           'fillOpacity', 0.14, 'outlineColor', '#CBD5E1',
           'outlineWidth', 1.5, 'outlineOpacity', 1.0),
       jsonb_build_object('enabled', FALSE)
FROM gis.layers l
WHERE NOT EXISTS (SELECT 1 FROM gis.layer_style st WHERE st.layer_id = l.id);

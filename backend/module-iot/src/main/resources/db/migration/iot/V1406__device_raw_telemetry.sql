-- =====================================================================================
-- Device Data Configuration — raw payload retention and reading quality
-- Owner : module-iot (version range V1400–V1499)
--
-- The module's central rule is that **every parameter every device sends is accepted and
-- permanently preserved**, whether or not anyone has configured it. Nothing in V1405 achieves
-- that on its own: a catalogue says what to do with the fields it knows about and is silent
-- about the rest. This migration is where "the rest" is kept.
--
-- Why the existing tables are not enough, having checked each:
--
--   * `iot.device_readings` (V1400) is one row per *metric*, and a metric is a number. A field
--     the parsers did not turn into a number — a string status, an array of sub-meter
--     readings, a nested object — produces no row at all. It also stores canonicalised names,
--     so the vendor's own spelling is already gone by the time a row is written.
--
--   * `iot.receiver_packet_logs` (V1404) does hold the original bytes, and deliberately holds
--     them only sometimes: `raw_payload BYTEA` is written for rejected packets and, by
--     default, *not* for accepted ones, because "an accepted payload's information is already
--     in the readings table". That reasoning is exactly right for a forensic log and exactly
--     backwards for this requirement — the information is in the readings table only for the
--     fields somebody configured. It is also BYTEA, so no query can ask which payloads carry
--     a `powerFactor`. The packet log keeps its retention policy unchanged; this table is the
--     durable, queryable, always-written record beside it.
--
-- So: one row per packet, JSONB, never modified, written whatever the outcome.
-- =====================================================================================


-- =====================================================================================
-- 1. Raw telemetry
-- =====================================================================================
--
-- FK-free for the same reasons receiver_packet_logs is (V1404): the row must outlive the
-- device it describes, and it must accept packets from devices nobody has registered — which
-- are precisely the packets an operator most needs to see. A foreign key would forbid both.
--
-- `message_id` is the receiver's packet id, so a row here, a row in receiver_packet_logs and
-- the correlation id in the application log all name the same reception. Unique, so a retry
-- inside the reception path cannot produce two copies of one packet.

CREATE TABLE iot.device_raw_telemetry
(
    id                  UUID PRIMARY KEY,
    -- NULL until the device is resolved. Not tenant-filtered at the database level; every API
    -- query scopes explicitly, as with the packet log.
    organization_id     UUID,
    device_id           UUID,
    device_code         VARCHAR(60),
    -- Denormalised from the device row rather than joined. module-iot must not join
    -- gis.assets, and this is written from inside packet reception where a join is a cost paid
    -- per packet.
    asset_id            UUID,
    asset_number        VARCHAR(80),

    -- The device's own clock. NULL when the payload carried no timestamp or could not be
    -- decoded at all — which is a fact worth keeping, not a reason to refuse the row.
    device_timestamp    TIMESTAMPTZ,
    received_at         TIMESTAMPTZ  NOT NULL,

    -- The two halves of "how did this get here", kept apart because they answer different
    -- questions and are routinely different values. A ChirpStack webhook is a LORAWAN device
    -- arriving over an HTTP connection; collapsing them would label it one or the other and
    -- lose the other.
    communication_type  VARCHAR(20),                    -- the device's network: LORAWAN, NB_IOT, …
    connection_mode     VARCHAR(20)  NOT NULL,          -- the bearer it arrived on: HTTP, MQTT, TCP, …
    message_id          UUID         NOT NULL,
    correlation_id      VARCHAR(64),
    source_ip           INET,

    /*
     * The complete original payload. **Never modified** — no normalisation, no canonicalising
     * of key names, no dropping of fields the platform had no use for. This column is the
     * answer to "what did the device actually send", and a column that had been tidied could
     * not answer it.
     *
     * JSONB rather than BYTEA so it can be queried: `payload ? 'powerFactor'`, `payload ->>
     * 'temperature'`, and the discovery sweep that finds fields nobody configured. That is the
     * whole difference between preserving data and merely retaining it.
     *
     * Payloads that are not JSON — a LoRaWAN frame, a raw meter binary — are still one JSONB
     * document: {"encoding":"BASE64","data":"…"}, with the bytes intact and recoverable.
     * `payload_encoding` says which case a row is, so nothing has to guess by inspection.
     */
    payload             JSONB        NOT NULL,
    payload_encoding    VARCHAR(10)  NOT NULL DEFAULT 'JSON',
    payload_size        INTEGER      NOT NULL DEFAULT 0,

    -- What the pipeline made of it. ACCEPTED/DUPLICATE/REJECTED mirror ReceptionStatus so the
    -- two records agree; the error is copied here rather than joined, because the commonest
    -- use of this table is reading a rejected payload beside the reason it was rejected.
    processing_status   VARCHAR(12)  NOT NULL,
    processing_error    VARCHAR(500),

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_raw_telemetry_status CHECK (processing_status IN
        ('ACCEPTED', 'DUPLICATE', 'REJECTED')),
    CONSTRAINT ck_raw_telemetry_encoding CHECK (payload_encoding IN ('JSON', 'BASE64', 'TEXT'))
);

CREATE UNIQUE INDEX uq_raw_telemetry_message ON iot.device_raw_telemetry (message_id);
-- "Show me this meter's raw payloads, newest first" — the View Raw Payload action, and the
-- reason device_id leads the key.
CREATE INDEX ix_raw_telemetry_device_time ON iot.device_raw_telemetry (device_id, received_at DESC);
CREATE INDEX ix_raw_telemetry_org_time ON iot.device_raw_telemetry (organization_id, received_at DESC);
-- Retention sweeps and window searches both scan by receipt time.
CREATE INDEX ix_raw_telemetry_received_at ON iot.device_raw_telemetry (received_at);
/*
 * GIN over the payload. This is what makes the "which devices have ever sent a field called
 * X" question answerable at all — the query behind the discovery screen's backfill and behind
 * an operator asking whether it is worth configuring a parameter before they configure it.
 * jsonb_path_ops rather than the default: half the size, and containment is the only operator
 * class this column is ever queried with.
 */
CREATE INDEX ix_raw_telemetry_payload ON iot.device_raw_telemetry USING GIN (payload jsonb_path_ops);
-- Unattributed packets — the commissioning queue, same shape as the packet log's.
CREATE INDEX ix_raw_telemetry_unattributed ON iot.device_raw_telemetry (received_at DESC)
    WHERE organization_id IS NULL;

COMMENT ON TABLE iot.device_raw_telemetry IS
    'The complete original payload of every packet, accepted or not, as JSONB. Never modified. Configuration decides how data is used; this table is why configuration never decides whether data is kept.';
COMMENT ON COLUMN iot.device_raw_telemetry.payload IS
    'The payload exactly as received. Non-JSON payloads are wrapped as {"encoding":"BASE64","data":"…"} with the bytes intact — see payload_encoding.';
COMMENT ON COLUMN iot.device_raw_telemetry.communication_type IS
    'The device''s registered network (LORAWAN, NB_IOT …). Distinct from connection_mode, which is the bearer the packet arrived on — a ChirpStack uplink is LORAWAN over HTTP.';


-- =====================================================================================
-- 2. Reading quality
-- =====================================================================================
--
-- Validation with nowhere to put its verdict has only one thing it can do with a bad value,
-- which is discard it. These two columns are what let validation be strict and lossless at
-- the same time: a pressure of 47 bar on a 10 bar main is stored, marked OUT_OF_RANGE, and is
-- the most important reading of the day. Discarding it to satisfy a range check configured
-- last March would be the single worst thing this module could do.
--
--   VALID        configured, and inside every rule declared for it
--   INVALID      configured, and could not be read as its declared type
--   OUT_OF_RANGE configured, readable, outside min/max
--   MISSING      configured and mandatory, and absent from the packet. Value is NULL
--   UNKNOWN      not configured. The default, and not a defect — it is the honest answer
--                before an administrator has said anything about the parameter
--
-- Nullable with no default rather than DEFAULT 'UNKNOWN', so existing rows keep saying
-- nothing rather than newly claiming a verdict nothing evaluated. Rows written from now on
-- always set it.

ALTER TABLE iot.device_readings
    ADD COLUMN quality      VARCHAR(16),
    -- Which definition judged this reading. Kept because a parameter's range can be widened
    -- later, and "this row was OUT_OF_RANGE under the definition in force at the time" is only
    -- reconstructible with the id and iot.device_parameter_history beside it.
    ADD COLUMN parameter_id UUID;

ALTER TABLE iot.device_readings
    ADD CONSTRAINT ck_readings_quality CHECK (quality IS NULL OR quality IN
        ('VALID', 'INVALID', 'OUT_OF_RANGE', 'MISSING', 'UNKNOWN'));

-- Partial: suspect readings are a small fraction of the table and the whole of the diagnostic
-- traffic, so the index that serves "what has this device been reporting badly" stays small
-- enough to remain cached. The dominant query — good readings for a chart — is unaffected.
CREATE INDEX ix_readings_suspect ON iot.device_readings (organization_id, quality, observed_at DESC)
    WHERE quality IS NOT NULL AND quality <> 'VALID';
CREATE INDEX ix_readings_parameter ON iot.device_readings (parameter_id, observed_at DESC)
    WHERE parameter_id IS NOT NULL;

COMMENT ON COLUMN iot.device_readings.quality IS
    'Verdict of the configured parameter''s validation. A failing value is stored and marked, never discarded. UNKNOWN means the parameter is not configured, which is not a defect.';
COMMENT ON COLUMN iot.device_readings.parameter_id IS
    'The definition this reading was judged against, so a later widening of the range does not make the historical verdict unreadable.';

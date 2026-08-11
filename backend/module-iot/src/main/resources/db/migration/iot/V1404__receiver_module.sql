-- =====================================================================================
-- Module 18 — The Receiver Module
-- Owner : module-iot (version range V1400–V1499)
--
-- Eight tables behind one entry point. The receiver terminates every transport, so everything
-- it learns about a packet is recorded here once rather than per-transport — which is the point
-- of having a single entry point at all.
--
-- The tables divide along two axes, and both divisions are deliberate:
--
--   per-packet, high volume     receiver_packet_logs, receiver_replay_cache
--   per-event, low volume       receiver_logs, receiver_connection_history, receiver_dead_letters
--   current state               receiver_sessions
--   pre-aggregated history      receiver_packet_statistics, receiver_transport_statistics
--
-- Retention differs by an order of magnitude between them, which is why they are not one table
-- with a discriminator: an incident record that must survive an audit cycle cannot share a
-- retention policy with rows written thousands per second.
--
-- Version range note: these are receiver tables, not telemetry. V1500–V1599 is reserved for
-- telemetry (the Module 13 hypertable), and iot.device_readings already lives at V1400 — the
-- receiver's own infrastructure belongs with the module that owns it.
-- =====================================================================================

-- =====================================================================================
-- 1. Packet log — one row per packet, whatever became of it
-- =====================================================================================
--
-- No foreign keys, and that is a decision rather than an omission. This is a forensic record:
-- it must outlive the device it describes, or decommissioning a disputed meter would delete the
-- evidence of what it sent. It also holds rows whose organization_id and device_id are NULL by
-- construction — packets from devices nobody registered, which are exactly the rows an operator
-- needs to see. A FK would forbid both.

CREATE TABLE iot.receiver_packet_logs
(
    id                    UUID PRIMARY KEY,
    organization_id       UUID,                       -- NULL until the device is resolved
    device_id             UUID,
    transport             VARCHAR(20)  NOT NULL,
    communication_profile VARCHAR(20),
    received_at           TIMESTAMPTZ  NOT NULL,
    observed_at           TIMESTAMPTZ,                -- the device's clock; NULL if undecodable
    status                VARCHAR(12)  NOT NULL,
    error_code            VARCHAR(60),                -- ErrorCode name; NULL when accepted
    error_detail          VARCHAR(500),
    payload_size          INTEGER      NOT NULL,
    source_ip             INET,
    correlation_id        VARCHAR(64),
    processing_time_ms    INTEGER      NOT NULL,
    authentication_scheme VARCHAR(40),
    principal             VARCHAR(120),
    resolution_strategy   VARCHAR(60),                -- which strategy placed the device
    parser                VARCHAR(60),
    content_type          VARCHAR(80),
    -- Retained per policy: rejected payloads by default, accepted ones only when asked for.
    -- Keeping every accepted payload doubles the storage cost of telemetry to store information
    -- the readings table already holds.
    raw_payload           BYTEA,
    metadata              JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_packet_logs_status CHECK (status IN ('ACCEPTED', 'DUPLICATE', 'REJECTED'))
);

-- "What did this meter send, most recent first" — the support query, and the reason device_id
-- leads the key rather than trailing it.
CREATE INDEX ix_packet_logs_device_time ON iot.receiver_packet_logs (device_id, received_at DESC);
CREATE INDEX ix_packet_logs_org_time ON iot.receiver_packet_logs (organization_id, received_at DESC);
-- Retention sweeps and time-window searches both scan by receipt time.
CREATE INDEX ix_packet_logs_received_at ON iot.receiver_packet_logs (received_at);
-- Partial: failures are a small fraction of rows and the whole of the diagnostic traffic, so the
-- index that serves them should be small enough to stay cached.
CREATE INDEX ix_packet_logs_failures ON iot.receiver_packet_logs (transport, error_code, received_at DESC)
    WHERE status = 'REJECTED';
-- The commissioning queue: packets nobody could attribute.
CREATE INDEX ix_packet_logs_unattributed ON iot.receiver_packet_logs (received_at DESC)
    WHERE organization_id IS NULL;
CREATE INDEX ix_packet_logs_correlation ON iot.receiver_packet_logs (correlation_id);

COMMENT ON TABLE iot.receiver_packet_logs IS
    'One row per packet the receiver took delivery of, accepted or not. Deliberately FK-free: it must survive deletion of the device it describes, and must accept rows belonging to no tenant.';
COMMENT ON COLUMN iot.receiver_packet_logs.organization_id IS
    'NULL when the packet could not be attributed to a device, and therefore to a tenant. Not tenant-filtered at the database level — every API query must scope explicitly.';

-- =====================================================================================
-- 2. Receiver log — the receiver's own operational diary
-- =====================================================================================
--
-- Separate from the packet log because the questions are different ("what is the receiver
-- doing?" versus "what happened to this reading?"), the volumes differ by orders of magnitude,
-- and an incident record has to be retained long after the traffic around it has been swept.

CREATE TABLE iot.receiver_logs
(
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID,                              -- NULL for deployment-wide events
    transport       VARCHAR(20),
    event_type      VARCHAR(60)  NOT NULL,
    severity        VARCHAR(10)  NOT NULL DEFAULT 'INFO',
    message         VARCHAR(1000),
    details         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    correlation_id  VARCHAR(64),
    actor_user_id   UUID,                              -- set for operator-initiated events
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT ck_receiver_logs_severity CHECK (severity IN ('INFO', 'WARN', 'ERROR'))
);

CREATE INDEX ix_receiver_logs_time ON iot.receiver_logs (occurred_at DESC);
CREATE INDEX ix_receiver_logs_transport ON iot.receiver_logs (transport, occurred_at DESC);
CREATE INDEX ix_receiver_logs_severity ON iot.receiver_logs (severity, occurred_at DESC)
    WHERE severity <> 'INFO';

COMMENT ON TABLE iot.receiver_logs IS
    'Receiver lifecycle and operational events: listeners starting and failing, dead letters replayed, thresholds tripped. Low volume, long retention — the rows that belong on a status page.';

-- =====================================================================================
-- 3. Communication sessions — current state of stateful transports
-- =====================================================================================

CREATE TABLE iot.receiver_sessions
(
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID,
    device_id        UUID,
    transport        VARCHAR(20)  NOT NULL,
    session_key      VARCHAR(120) NOT NULL,            -- MQTT client id, socket id, WS session id
    remote_address   INET,
    remote_port      INTEGER,
    state            VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    protocol_version VARCHAR(20),
    opened_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_activity_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    closed_at        TIMESTAMPTZ,
    close_reason     VARCHAR(300),
    packets_received BIGINT       NOT NULL DEFAULT 0,
    bytes_received   BIGINT       NOT NULL DEFAULT 0,
    attributes       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    version          BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT ck_receiver_sessions_state CHECK (state IN ('OPEN', 'CLOSED'))
);

-- One open session per connection key. Partial, so the closed history of a reconnecting device
-- accumulates freely while a reconnect can still find and retire the stale open row rather than
-- adding a second one beside it — which is how "connected devices" starts over-reporting.
CREATE UNIQUE INDEX uq_receiver_sessions_open
    ON iot.receiver_sessions (transport, session_key)
    WHERE state = 'OPEN';
CREATE INDEX ix_receiver_sessions_org ON iot.receiver_sessions (organization_id, last_activity_at DESC);
CREATE INDEX ix_receiver_sessions_device ON iot.receiver_sessions (device_id);
-- Drives the idle sweep, which is the only thing that retires a half-open connection.
CREATE INDEX ix_receiver_sessions_idle ON iot.receiver_sessions (last_activity_at)
    WHERE state = 'OPEN';

COMMENT ON TABLE iot.receiver_sessions IS
    'Open (and last-closed) connections for stateful transports — TCP, MQTT, WebSocket. Stateless transports produce none: a session row per HTTP request would be the packet log under another name.';

-- =====================================================================================
-- 4. Connection history — append-only connect/disconnect record
-- =====================================================================================

CREATE TABLE iot.receiver_connection_history
(
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      UUID,
    organization_id UUID,
    device_id       UUID,
    transport       VARCHAR(20) NOT NULL,
    event           VARCHAR(16) NOT NULL,
    remote_address  INET,
    reason          VARCHAR(300),
    duration_ms     BIGINT,                            -- NULL on connect and reject events
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT ck_connection_history_event CHECK (event IN
        ('CONNECTED', 'DISCONNECTED', 'REJECTED', 'TIMED_OUT', 'ERROR'))
);

CREATE INDEX ix_connection_history_org_time
    ON iot.receiver_connection_history (organization_id, occurred_at DESC);
CREATE INDEX ix_connection_history_device_time
    ON iot.receiver_connection_history (device_id, occurred_at DESC);
CREATE INDEX ix_connection_history_session ON iot.receiver_connection_history (session_id);

COMMENT ON TABLE iot.receiver_connection_history IS
    'Append-only connection events. Sessions hold current state and are overwritten; this holds the sequence. A flapping link looks healthy in the former and is obvious in the latter.';

-- =====================================================================================
-- 5. Replay cache — durable packet-identity claims
-- =====================================================================================

CREATE TABLE iot.receiver_replay_cache
(
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id     UUID         NOT NULL,
    -- A nonce, a frame counter, or a payload hash where the transport offers neither.
    replay_key    VARCHAR(200) NOT NULL,
    transport     VARCHAR(20),
    first_seen_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ  NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0
);

-- This index *is* the replay check. The receiver claims an identity with INSERT .. ON CONFLICT
-- DO NOTHING and reads the affected-row count: exactly one caller can win, so the decision is
-- atomic. A SELECT-then-INSERT would leave a window that the attack — the same captured packet
-- delivered twice, fast — is precisely shaped to exploit.
-- Scoped to the device, never global: two meters legitimately emit frame counter 1.
CREATE UNIQUE INDEX uq_replay_cache_device_key
    ON iot.receiver_replay_cache (device_id, replay_key);
CREATE INDEX ix_replay_cache_expiry ON iot.receiver_replay_cache (expires_at);

COMMENT ON TABLE iot.receiver_replay_cache IS
    'Durable, cluster-wide replay protection. The in-process DedupCache absorbs the common case; this is consulted on a miss, because a per-instance in-memory cache is not a security control — it is bypassed by a second replica and reset by a rolling restart.';

-- =====================================================================================
-- 6. Dead-letter queue — packets that failed recoverably
-- =====================================================================================

CREATE TABLE iot.receiver_dead_letters
(
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    packet_id       UUID         NOT NULL,             -- the packet-log row this came from
    organization_id UUID,
    device_id       UUID,
    transport       VARCHAR(20)  NOT NULL,
    error_code      VARCHAR(60)  NOT NULL,
    error_detail    VARCHAR(500),
    -- NOT NULL, unlike on the packet log: a dead letter without its payload cannot be replayed,
    -- which is the only reason the row exists.
    payload         BYTEA        NOT NULL,
    content_type    VARCHAR(80),
    source_ip       INET,
    -- Transport attributes and identifier claims. Without them a replay is not the same packet:
    -- an MQTT publish stripped of its topic cannot be resolved.
    envelope        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER      NOT NULL DEFAULT 1,
    received_at     TIMESTAMPTZ  NOT NULL,
    replayed_at     TIMESTAMPTZ,
    replayed_by     UUID,                              -- who authored the replayed telemetry
    replay_result   VARCHAR(300),
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT ck_dead_letters_status CHECK (status IN ('PENDING', 'REPLAYED', 'DISCARDED'))
);

CREATE INDEX ix_dead_letters_pending ON iot.receiver_dead_letters (received_at)
    WHERE status = 'PENDING';
CREATE INDEX ix_dead_letters_org ON iot.receiver_dead_letters (organization_id, received_at DESC);
CREATE INDEX ix_dead_letters_packet ON iot.receiver_dead_letters (packet_id);

COMMENT ON TABLE iot.receiver_dead_letters IS
    'Packets that would have been accepted but for a recoverable fault — database unreachable, parser defect. Not a bin for every rejection: an unregistered device would still be unregistered on replay.';
COMMENT ON COLUMN iot.receiver_dead_letters.replayed_by IS
    'Replay writes telemetry the device never re-sent. Without an author on the row that is indistinguishable from fabricating a reading, which is why iot:receiver:replay is a permission of its own.';

-- =====================================================================================
-- 7 & 8. Pre-aggregated statistics, at two grains
-- =====================================================================================
--
-- Both answer questions whose honest form is a scan of receiver_packet_logs — the largest table
-- in the module — on every dashboard refresh. Aggregating on write turns that into an indexed
-- read. The two grains are separate tables because neither derives from the other: a transport
-- at a 99% success rate hides one meter failing every packet, and a healthy meter says nothing
-- about the path its neighbours use.

CREATE TABLE iot.receiver_packet_statistics
(
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID        NOT NULL,
    device_id           UUID        NOT NULL,
    transport           VARCHAR(20) NOT NULL,
    bucket_start        TIMESTAMPTZ NOT NULL,          -- start of the hour, UTC
    accepted            BIGINT      NOT NULL DEFAULT 0,
    duplicates          BIGINT      NOT NULL DEFAULT 0,
    rejected            BIGINT      NOT NULL DEFAULT 0,
    bytes_received      BIGINT      NOT NULL DEFAULT 0,
    -- Summed rather than averaged so buckets can be merged without carrying a weight.
    total_processing_ms BIGINT      NOT NULL DEFAULT 0,
    last_packet_at      TIMESTAMPTZ,
    version             BIGINT      NOT NULL DEFAULT 0
);

-- The conflict target for the accumulate upsert. Every replica folds its flush into the same
-- bucket, so the addition has to happen inside the database — read-modify-write would have two
-- instances each read 100, each write 140, and lose 40 packets from the record.
CREATE UNIQUE INDEX uq_packet_statistics_bucket
    ON iot.receiver_packet_statistics (organization_id, device_id, transport, bucket_start);
CREATE INDEX ix_packet_statistics_silent ON iot.receiver_packet_statistics (organization_id, last_packet_at);

CREATE TABLE iot.receiver_transport_statistics
(
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Nullable here and not above: a packet is counted against its transport the moment it
    -- arrives, including the ones refused before any tenant was known. Those are exactly the rows
    -- a transport dashboard exists to show.
    organization_id     UUID,
    transport           VARCHAR(20) NOT NULL,
    bucket_start        TIMESTAMPTZ NOT NULL,
    accepted            BIGINT      NOT NULL DEFAULT 0,
    duplicates          BIGINT      NOT NULL DEFAULT 0,
    rejected            BIGINT      NOT NULL DEFAULT 0,
    bytes_received      BIGINT      NOT NULL DEFAULT 0,
    total_processing_ms BIGINT      NOT NULL DEFAULT 0,
    -- Kept beside the sum because a mean hides the stall that woke someone up.
    max_processing_ms   BIGINT      NOT NULL DEFAULT 0,
    last_packet_at      TIMESTAMPTZ,
    version             BIGINT      NOT NULL DEFAULT 0
);

-- COALESCE in the key, and it is load-bearing. Two NULLs are not equal in SQL, so a plain
-- three-column unique index would let every flush of unattributed rejections insert a fresh
-- bucket instead of adding to the existing one — and the upsert's ON CONFLICT clause has to
-- repeat this expression exactly for PostgreSQL to infer this index.
CREATE UNIQUE INDEX uq_transport_statistics_bucket
    ON iot.receiver_transport_statistics
        (transport, bucket_start,
         COALESCE(organization_id, '00000000-0000-0000-0000-000000000000'::uuid));
CREATE INDEX ix_transport_statistics_time ON iot.receiver_transport_statistics (bucket_start DESC);

COMMENT ON TABLE iot.receiver_packet_statistics IS
    'Hourly per-device packet counters. Answers "is this meter reporting as often as it should" — an asset question, asked from the device screen.';
COMMENT ON TABLE iot.receiver_transport_statistics IS
    'Hourly per-transport packet counters. Answers "is the LoRaWAN path healthy" — an infrastructure question, asked from the receiver status screen.';

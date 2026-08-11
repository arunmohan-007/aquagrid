-- Runs once, on first initialisation of an empty data directory.
--
-- Only the extensions that require superuser are created here; the schema itself is owned
-- entirely by Flyway. Splitting it this way means the application's database role does not
-- need superuser, which is what allows a least-privilege role in production.

-- TimescaleDB must be preloaded before the extension can be created. ALTER SYSTEM writes to
-- postgresql.conf (in PGDATA); the official entrypoint restarts the server after initdb scripts
-- run, so the preload is active by the time CREATE EXTENSION executes on the next start.
-- We create non-preload-dependent extensions now, and timescaledb is created by Flyway/the app
-- after the preload takes effect (V1000 already does CREATE EXTENSION IF NOT EXISTS timescaledb).
ALTER SYSTEM SET shared_preload_libraries = 'timescaledb';

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS postgis_topology;
CREATE EXTENSION IF NOT EXISTS pgrouting;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS btree_gist;
-- timescaledb: created after restart, once the preload above is active. See note.


-- Reject connections that would transmit the password in the clear.
ALTER SYSTEM SET password_encryption = 'scram-sha-256';

-- Sensible defaults for a spatial workload; tune to the host during deployment.
ALTER SYSTEM SET max_connections = 200;
ALTER SYSTEM SET random_page_cost = 1.1;          -- SSD-backed storage
ALTER SYSTEM SET effective_io_concurrency = 200;
ALTER SYSTEM SET log_min_duration_statement = 1000;  -- log any statement over 1s
ALTER SYSTEM SET log_lock_waits = on;
ALTER SYSTEM SET track_io_timing = on;

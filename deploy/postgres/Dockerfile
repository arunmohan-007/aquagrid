# =====================================================================================
# AquaGrid database image
#
# postgis/postgis:16-3.4 ships PostGIS but NOT pgRouting, TimescaleDB, or the contrib modules
# (citext, pg_trgm, btree_gist). This image layers all of them on so one container provides the
# full spatial + time-series + routing + text-search capability the platform needs.
#
# Build:  docker build -t aquagrid-db deploy/postgres
# =====================================================================================
FROM postgis/postgis:16-3.4

# The base image is Debian-based (bookworm). The PostgreSQL upstream APT repository is already
# configured in the postgis image, so postgresql-16-pgrouting, -contrib and TimescaleDB resolve.

# 1. pgRouting — network tracing (Modules 11, 12)
# 2. postgresql-contrib — citext (case-insensitive text), pg_trgm (fuzzy search), btree_gist
# 3. TimescaleDB — telemetry hypertables (Modules 13–15). The packagecloud repo must be added.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        postgresql-16-pgrouting \
        postgresql-contrib \
        wget \
        lsb-release \
        gnupg \
    && echo "deb [signed-by=/usr/share/keyrings/timescale.key] https://packagecloud.io/timescale/timescaledb/debian/ $(lsb_release -c -s) main" \
        > /etc/apt/sources.list.d/timescaledb.list \
    && wget --quiet -O - https://packagecloud.io/timescale/timescaledb/gpgkey \
        | gpg --dearmor -o /usr/share/keyrings/timescale.key \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        timescaledb-2-postgresql-16 \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# TimescaleDB MUST be in shared_preload_libraries or CREATE EXTENSION fails. The postgis base
# image's config lives in PGDATA (not /etc), which is populated at first init. We set the preload
# via the docker-entrypoint-initdb.d mechanism in the init script (ALTER SYSTEM SET), which the
# official postgres entrypoint applies on first boot. See 00-extensions.sql.

-- =====================================================================================
-- Module 11 — Pipeline network management
-- Owner : module-gis (version range V1300–V1399)
--
-- A pipeline network is a directed graph: junctions (nodes) connected by pipes (edges). The value
-- of this module is topology and tracing, not CRUD — so the schema separates the source of truth
-- (gis.pipelines) from the derived graph pgRouting queries (gis.pipe_network).
--
-- pgRouting requires its extension; added here. It builds on PostGIS (already present).
-- =====================================================================================

CREATE EXTENSION IF NOT EXISTS pgrouting;

-- ---- Network nodes (junctions) ---------------------------------------------------------
-- Auto-created when a pipe's endpoint snaps to a location with no existing node within tolerance.
-- Carries geometry so traces can return spatial results (highlight on map, nearest-asset lookup).
CREATE TABLE gis.network_nodes
(
    id          UUID PRIMARY KEY                  DEFAULT gen_random_uuid(),
    organization_id UUID     NOT NULL,
    -- pgRouting requires BIGINT vertex ids. This is a stable per-node integer assigned at insert;
    -- the UUID remains the public identity, this is the graph-internal id only.
    pgr_vertex_id  BIGSERIAL,
    geom        geometry(Point, 4326) NOT NULL,
    -- 3857 for cheap bbox operations, generated like the assets supertype.
    geom_3857   geometry(Point, 3857) GENERATED ALWAYS AS (ST_Transform(geom, 3857)) STORED,
    label       VARCHAR(120),
    node_type   VARCHAR(30) NOT NULL DEFAULT 'JUNCTION',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_nodes_organization
        FOREIGN KEY (organization_id) REFERENCES core.organizations (id) ON DELETE CASCADE,
    CONSTRAINT ck_nodes_type CHECK (node_type IN
        ('JUNCTION', 'VALVE', 'METER', 'TANK_CONNECTION', 'RESERVOIR_CONNECTION', 'PUMP_CONNECTION'))
);

CREATE INDEX ix_network_nodes_org ON gis.network_nodes (organization_id);
CREATE INDEX ix_network_nodes_geom ON gis.network_nodes USING GIST (geom);
-- The snap query: "find the nearest node to this point within tolerance". GiST + KNN.
CREATE INDEX ix_network_nodes_geom_3857 ON gis.network_nodes USING GIST (geom_3857);

COMMENT ON TABLE gis.network_nodes IS
    'Network junctions. Auto-created on pipe snap; the graph vertices pgRouting traces over.';

-- ---- Pipelines (type table + edges) ----------------------------------------------------
-- Extends gis.assets (PK = FK) with pipe-specific engineering data AND the from/to node references
-- that make it a graph edge. length_m is computed in metres via geography(), not degrees.
CREATE TABLE gis.pipelines
(
    asset_id        UUID PRIMARY KEY,
    -- The pipe's own geometry (LineString). Denormalised from gis.assets because length_m is
    -- GENERATED from it and tracing needs the edge geometry directly. Kept in sync by the
    -- application writing both the asset geom and this column together.
    geom            geometry(LineString, 4326) NOT NULL,
    -- The graph edge: which nodes this pipe connects.
    from_node_id    UUID,
    to_node_id      UUID,
    -- Engineering data.
    diameter_mm     NUMERIC(7,1),
    material        VARCHAR(40),
    -- Length in metres, computed from the geometry. Stored (not computed per query) because every
    -- pipe list shows it and ST_Length(geography) per row is wasteful at scale.
    length_m        NUMERIC(10,2) GENERATED ALWAYS AS (
        ST_Length(geom::geography)
    ) STORED,
    -- Flow direction relative to the from->to geometry. BIDIRECTIONAL is the default for distribution
    -- mains where flow reverses by demand; TRANSMISSION mains are often one-way.
    flow_direction  VARCHAR(20) NOT NULL DEFAULT 'BIDIRECTIONAL',
    roughness       NUMERIC(4,3),     -- Hazen-Williams C, for head-loss calculation
    pressure_class  NUMERIC(6,2),     -- PN rating (bar)

    CONSTRAINT fk_pipelines_asset
        FOREIGN KEY (asset_id) REFERENCES gis.assets (id) ON DELETE CASCADE,
    CONSTRAINT fk_pipelines_from_node
        FOREIGN KEY (from_node_id) REFERENCES gis.network_nodes (id) ON DELETE SET NULL,
    CONSTRAINT fk_pipelines_to_node
        FOREIGN KEY (to_node_id) REFERENCES gis.network_nodes (id) ON DELETE SET NULL,
    CONSTRAINT ck_pipelines_flow CHECK (flow_direction IN ('BIDIRECTIONAL', 'FROM_TO', 'TO_FROM'))
);

CREATE INDEX ix_pipelines_from_node ON gis.pipelines (from_node_id);
CREATE INDEX ix_pipelines_to_node ON gis.pipelines (to_node_id);
CREATE INDEX ix_pipelines_geom ON gis.pipelines USING GIST (geom);

COMMENT ON TABLE gis.pipelines IS
    'Pipes as graph edges. from_node/to_node make it a network; length_m is auto-computed in metres.';
COMMENT ON COLUMN gis.pipelines.flow_direction IS
    'BIDIRECTIONAL (distribution), FROM_TO or TO_FROM (transmission one-way mains).';

-- ---- pgRouting edge table (derived) ----------------------------------------------------
-- The graph pgRouting traces over. Rebuilt by NetworkTopologyService from gis.pipelines whenever a
-- pipe changes. cost/reverse_cost encode direction: a one-way FROM_TO pipe has reverse_cost = -1
-- (impassable backwards), which pgRouting treats as a barrier.
CREATE TABLE gis.pipe_network
(
    id              BIGSERIAL PRIMARY KEY,
    organization_id UUID NOT NULL,
    pipe_id         UUID NOT NULL,
    source          BIGINT NOT NULL,       -- references network_nodes internal pgr id (see view below)
    target          BIGINT NOT NULL,
    cost            NUMERIC(12,3) NOT NULL,    -- forward traversal cost (length_m)
    reverse_cost    NUMERIC(12,3) NOT NULL,    -- -1 if one-way
    geom            geometry(LineString, 4326),

    CONSTRAINT fk_pgr_pipe FOREIGN KEY (pipe_id) REFERENCES gis.pipelines (asset_id) ON DELETE CASCADE
);

CREATE INDEX ix_pipe_network_source ON gis.pipe_network (source);
CREATE INDEX ix_pipe_network_target ON gis.pipe_network (target);
CREATE INDEX ix_pipe_network_org ON gis.pipe_network (organization_id);

COMMENT ON TABLE gis.pipe_network IS
    'Derived pgRouting edge table. Rebuilt from gis.pipelines on change; never edited directly.';

-- pgRouting identifies vertices by BIGINT. The pgr_vertex_id column on network_nodes is the stable
-- integer pgRouting uses; the UUID is the public identity. This view exposes the mapping.
CREATE OR REPLACE VIEW gis.network_node_vertices AS
SELECT id AS node_id, pgr_vertex_id, organization_id, geom
FROM gis.network_nodes;

-- HomeClaim schema (Postgres empfohlen; MariaDB/SQLite evtl. Anpassungen noetig)

CREATE TABLE regions (
    id UUID PRIMARY KEY,
    world VARCHAR(64) NOT NULL,
    shape VARCHAR(16) NOT NULL,
    min_x INT NOT NULL,
    max_x INT NOT NULL,
    min_y INT NOT NULL,
    max_y INT NOT NULL,
    min_z INT NOT NULL,
    max_z INT NOT NULL,
    owner UUID NOT NULL,
    merge_group_id UUID,
    metadata JSON DEFAULT '{}'
);

CREATE TABLE region_roles (
    region_id UUID NOT NULL REFERENCES regions(id) ON DELETE CASCADE,
    player_id UUID NOT NULL,
    role VARCHAR(16) NOT NULL,
    PRIMARY KEY(region_id, player_id, role)
);

CREATE TABLE region_flags (
    region_id UUID NOT NULL REFERENCES regions(id) ON DELETE CASCADE,
    flag_key VARCHAR(64) NOT NULL,
    flag_value JSON NOT NULL,
    PRIMARY KEY(region_id, flag_key)
);

CREATE TABLE region_limits (
    region_id UUID NOT NULL REFERENCES regions(id) ON DELETE CASCADE,
    limit_key VARCHAR(64) NOT NULL,
    limit_value JSON NOT NULL,
    PRIMARY KEY(region_id, limit_key)
);

CREATE TABLE zones (
    id UUID PRIMARY KEY,
    world VARCHAR(64) NOT NULL,
    shape VARCHAR(16) NOT NULL,
    min_x INT NOT NULL,
    max_x INT NOT NULL,
    min_y INT NOT NULL,
    max_y INT NOT NULL,
    min_z INT NOT NULL,
    max_z INT NOT NULL,
    priority INT NOT NULL,
    locked_flags JSON NOT NULL DEFAULT '[]',
    tags JSON NOT NULL DEFAULT '[]',
    default_flags JSON NOT NULL DEFAULT '{}',
    default_limits JSON NOT NULL DEFAULT '{}',
    allowed_trigger_blocks JSON NOT NULL DEFAULT '[]'
);

CREATE TABLE components (
    id UUID PRIMARY KEY,
    region_id UUID NOT NULL REFERENCES regions(id) ON DELETE CASCADE,
    type VARCHAR(32) NOT NULL,
    world VARCHAR(64) NOT NULL,
    x INT NOT NULL,
    y INT NOT NULL,
    z INT NOT NULL,
    state VARCHAR(16) NOT NULL,
    policy JSON NOT NULL,
    config JSON NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    actor_id UUID,
    target_id UUID,
    category VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    payload JSON NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE flag_profiles (
    name VARCHAR(64) PRIMARY KEY,
    flags JSON NOT NULL DEFAULT '{}',
    limits JSON NOT NULL DEFAULT '{}',
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

-- Indices fuer schnelles Lookup
CREATE INDEX idx_regions_world_chunk ON regions (world, min_x, max_x, min_z, max_z);
CREATE INDEX idx_components_world_chunk ON components (world, x, z);
CREATE INDEX idx_region_roles_owner ON region_roles (player_id);
CREATE INDEX idx_zones_world_priority ON zones (world, priority DESC);

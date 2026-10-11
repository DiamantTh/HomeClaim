-- Shared pre-release baseline. PostgreSQL is the reference dialect; MariaDB and SQLite
-- have explicit runtime verification and dialect-specific Flyway locations where needed.

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
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE audit_log (
    audit_id UUID PRIMARY KEY,
    actor_id UUID,
    target_id UUID,
    category VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    correlation_id UUID,
    parent_correlation_id UUID,
    actor_kind VARCHAR(32),
    actor_ref VARCHAR(128),
    action_key VARCHAR(255),
    world_id UUID,
    area_id UUID,
    source_world_id UUID,
    source_area_id UUID,
    source_x INT,
    source_y INT,
    source_z INT,
    source_resource_type VARCHAR(64),
    source_resource_id VARCHAR(36),
    target_world_id UUID,
    target_area_id UUID,
    target_x INT,
    target_y INT,
    target_z INT,
    target_resource_type VARCHAR(64),
    target_resource_id VARCHAR(36),
    role VARCHAR(32),
    policy_result VARCHAR(32),
    permission_result VARCHAR(32),
    bypass VARCHAR(128),
    reason VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    target_count INT,
    error TEXT,
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_correlation ON audit_log(correlation_id);
CREATE INDEX idx_audit_target_created ON audit_log(target_id, created_at);

-- Operational journal for long-running region/world mutations. Active jobs are
-- marked RECOVERY_REQUIRED at the next startup instead of being mistaken for
-- completed work after a crash.
CREATE TABLE plot_mutation_jobs (
    id UUID PRIMARY KEY,
    correlation_id UUID NOT NULL,
    operation VARCHAR(32) NOT NULL,
    world_id UUID,
    area_id UUID NOT NULL,
    world_name VARCHAR(128) NOT NULL,
    region_ids TEXT NOT NULL,
    backend VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    completed_units INT NOT NULL DEFAULT 0,
    total_units INT NOT NULL DEFAULT 0,
    error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

CREATE INDEX idx_plot_mutation_jobs_status_created ON plot_mutation_jobs(status, created_at);
CREATE INDEX idx_plot_mutation_jobs_area_created ON plot_mutation_jobs(area_id, created_at);

CREATE TABLE flag_profiles (
    name VARCHAR(64) PRIMARY KEY,
    flags JSON NOT NULL DEFAULT '{}',
    limits JSON NOT NULL DEFAULT '{}',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indices fuer schnelles Lookup
CREATE INDEX idx_regions_world_chunk ON regions (world, min_x, max_x, min_z, max_z);
CREATE INDEX idx_components_world_chunk ON components (world, x, z);
CREATE INDEX idx_region_roles_owner ON region_roles (player_id);
CREATE INDEX idx_zones_world_priority ON zones (world, priority DESC);

-- Pre-release canonical baseline: include all schema additions made before the first release.
ALTER TABLE regions ADD COLUMN price DOUBLE PRECISION NOT NULL DEFAULT 0.0;

CREATE TABLE plot_ratings (
    id UUID PRIMARY KEY,
    region_id UUID NOT NULL REFERENCES regions(id) ON DELETE CASCADE,
    rater_id UUID NOT NULL,
    rating INT NOT NULL CHECK (rating >= 1 AND rating <= 5),
    comment VARCHAR(256),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(region_id, rater_id)
);

CREATE INDEX idx_plot_ratings_region ON plot_ratings(region_id);
CREATE INDEX idx_plot_ratings_rater ON plot_ratings(rater_id);

CREATE TABLE region_entry_denies (
    id UUID PRIMARY KEY,
    region_id UUID NOT NULL REFERENCES regions(id) ON DELETE CASCADE,
    target_type VARCHAR(24) NOT NULL,
    target_value VARCHAR(128) NOT NULL,
    reason VARCHAR(512) NOT NULL,
    created_by UUID,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    reported_by UUID,
    reported_at TIMESTAMP,
    report_reason VARCHAR(512),
    revoked_by UUID,
    revoked_at TIMESTAMP,
    revoke_reason VARCHAR(512)
);

CREATE INDEX idx_region_entry_denies_region ON region_entry_denies(region_id);
CREATE INDEX idx_region_entry_denies_active ON region_entry_denies(region_id, status, expires_at);
CREATE INDEX idx_region_entry_denies_target ON region_entry_denies(target_type, target_value);

CREATE TABLE worlds (
    id UUID PRIMARY KEY,
    platform_uuid UUID UNIQUE,
    name VARCHAR(128) NOT NULL,
    metadata JSON
);

CREATE TABLE areas (
    id UUID PRIMARY KEY,
    world_id UUID NOT NULL REFERENCES worlds(id) ON DELETE CASCADE,
    area_key VARCHAR(128) NOT NULL,
    whole_world BOOLEAN NOT NULL DEFAULT FALSE,
    min_x INT,
    max_x INT,
    min_y INT,
    max_y INT,
    min_z INT,
    max_z INT,
    priority INT NOT NULL DEFAULT 0,
    metadata JSON,
    CONSTRAINT uq_areas_world_key UNIQUE(world_id, area_key),
    CONSTRAINT ck_area_scope CHECK (
        (whole_world = TRUE AND min_x IS NULL AND max_x IS NULL AND min_y IS NULL AND max_y IS NULL AND min_z IS NULL AND max_z IS NULL)
        OR
        (whole_world = FALSE AND min_x IS NOT NULL AND max_x IS NOT NULL AND min_y IS NOT NULL AND max_y IS NOT NULL AND min_z IS NOT NULL AND max_z IS NOT NULL)
    )
);

ALTER TABLE regions ADD COLUMN area_id UUID REFERENCES areas(id);
ALTER TABLE zones ADD COLUMN area_id UUID REFERENCES areas(id);

CREATE INDEX idx_areas_world_priority ON areas(world_id, priority);
CREATE INDEX idx_regions_area_bounds ON regions(area_id, min_x, max_x, min_z, max_z);
CREATE INDEX idx_zones_area_priority ON zones(area_id, priority);

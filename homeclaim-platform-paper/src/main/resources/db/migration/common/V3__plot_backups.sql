-- Persistent index for reset pre-images stored as versioned files under the plugin data folder.
CREATE TABLE plot_backups (
    id UUID PRIMARY KEY,
    correlation_id UUID NOT NULL,
    world_id UUID NOT NULL,
    paper_world_id UUID NOT NULL,
    area_id UUID NOT NULL,
    region_id UUID NOT NULL,
    min_x INT NOT NULL,
    max_x INT NOT NULL,
    min_y INT NOT NULL,
    max_y INT NOT NULL,
    min_z INT NOT NULL,
    max_z INT NOT NULL,
    created_by_kind VARCHAR(16) NOT NULL,
    created_by_ref VARCHAR(128),
    cause VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    format_version INT NOT NULL,
    compression VARCHAR(16) NOT NULL,
    block_count BIGINT NOT NULL,
    payload_bytes BIGINT NOT NULL DEFAULT 0,
    checksum_sha256 VARCHAR(64),
    error TEXT,
    created_at TIMESTAMP NOT NULL,
    ready_at TIMESTAMP
);

CREATE INDEX idx_plot_backups_region_created ON plot_backups(region_id, created_at);
CREATE INDEX idx_plot_backups_status_created ON plot_backups(status, created_at);

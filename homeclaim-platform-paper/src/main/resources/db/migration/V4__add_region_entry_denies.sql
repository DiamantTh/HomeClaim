CREATE TABLE region_entry_denies (
    id UUID PRIMARY KEY,
    region_id UUID NOT NULL REFERENCES regions(id) ON DELETE CASCADE,
    target_type VARCHAR(24) NOT NULL,
    target_value VARCHAR(128) NOT NULL,
    reason VARCHAR(512) NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
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

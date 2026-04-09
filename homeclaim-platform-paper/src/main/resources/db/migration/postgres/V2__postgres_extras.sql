-- Postgres extras: GIN-Indizes auf JSON-Felder
CREATE INDEX IF NOT EXISTS idx_region_flags_json ON region_flags USING GIN (flag_value::jsonb);
CREATE INDEX IF NOT EXISTS idx_region_limits_json ON region_limits USING GIN (limit_value::jsonb);
CREATE INDEX IF NOT EXISTS idx_region_metadata_json ON regions USING GIN (metadata::jsonb);
CREATE INDEX IF NOT EXISTS idx_zone_defaults_json ON zones USING GIN (default_flags::jsonb);
CREATE INDEX IF NOT EXISTS idx_zone_limits_json ON zones USING GIN (default_limits::jsonb);
CREATE INDEX IF NOT EXISTS idx_zone_tags ON zones USING GIN (tags::jsonb);
CREATE INDEX IF NOT EXISTS idx_components_policy ON components USING GIN (policy::jsonb);
CREATE INDEX IF NOT EXISTS idx_components_config ON components USING GIN (config::jsonb);
CREATE INDEX IF NOT EXISTS idx_flag_profiles ON flag_profiles USING GIN (flags::jsonb);

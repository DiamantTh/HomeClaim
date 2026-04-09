-- MariaDB extras (10.11+) – Indizes und Generated Columns fuer haeufige Flags/Limits

-- Key-Indizes
CREATE INDEX IF NOT EXISTS idx_region_flags_key ON region_flags(flag_key);
CREATE INDEX IF NOT EXISTS idx_region_limits_key ON region_limits(limit_key);

-- BUILD Flag
ALTER TABLE region_flags
  ADD COLUMN IF NOT EXISTS flag_build BOOL
    AS (CASE WHEN flag_key = 'BUILD' THEN JSON_VALUE(flag_value, '$') END) PERSISTENT;
CREATE INDEX IF NOT EXISTS idx_region_flags_build ON region_flags(flag_build);

-- PVP Flag
ALTER TABLE region_flags
  ADD COLUMN IF NOT EXISTS flag_pvp BOOL
    AS (CASE WHEN flag_key = 'PVP' THEN JSON_VALUE(flag_value, '$') END) PERSISTENT;
CREATE INDEX IF NOT EXISTS idx_region_flags_pvp ON region_flags(flag_pvp);

-- EXPLOSION_DAMAGE Flag
ALTER TABLE region_flags
  ADD COLUMN IF NOT EXISTS flag_explosion BOOL
    AS (CASE WHEN flag_key = 'EXPLOSION_DAMAGE' THEN JSON_VALUE(flag_value, '$') END) PERSISTENT;
CREATE INDEX IF NOT EXISTS idx_region_flags_explosion ON region_flags(flag_explosion);

-- FIRE_SPREAD Flag
ALTER TABLE region_flags
  ADD COLUMN IF NOT EXISTS flag_fire BOOL
    AS (CASE WHEN flag_key = 'FIRE_SPREAD' THEN JSON_VALUE(flag_value, '$') END) PERSISTENT;
CREATE INDEX IF NOT EXISTS idx_region_flags_fire ON region_flags(flag_fire);

-- Cooldown-Limit
ALTER TABLE region_limits
  ADD COLUMN IF NOT EXISTS limit_cooldown_ms INT
    AS (CASE WHEN limit_key = 'COMPONENT_COOLDOWN_MS' THEN JSON_VALUE(limit_value, '$') END) PERSISTENT;
CREATE INDEX IF NOT EXISTS idx_region_limits_cooldown ON region_limits(limit_cooldown_ms);

-- MariaDB 11.x+ optionale Optimierungen (JSON_VALUE/Generated Columns)
-- Nur anwenden, wenn du Flags/Keys so nutzt wie im Core (BUILD, PVP, FIRE_SPREAD, EXPLOSION_DAMAGE).
-- FKs bleiben unveraendert; keine Partitionierung.

-- Schneller Filter auf BUILD (region_flags)
ALTER TABLE region_flags
  ADD COLUMN flag_build BOOL
    AS (CASE WHEN flag_key = 'BUILD' THEN JSON_VALUE(flag_value, '$') END) PERSISTENT;
CREATE INDEX IF NOT EXISTS idx_region_flags_build ON region_flags(flag_build);

-- PVP-Flag
ALTER TABLE region_flags
  ADD COLUMN flag_pvp BOOL
    AS (CASE WHEN flag_key = 'PVP' THEN JSON_VALUE(flag_value, '$') END) PERSISTENT;
CREATE INDEX IF NOT EXISTS idx_region_flags_pvp ON region_flags(flag_pvp);

-- EXPLOSION_DAMAGE
ALTER TABLE region_flags
  ADD COLUMN flag_explosion BOOL
    AS (CASE WHEN flag_key = 'EXPLOSION_DAMAGE' THEN JSON_VALUE(flag_value, '$') END) PERSISTENT;
CREATE INDEX IF NOT EXISTS idx_region_flags_explosion ON region_flags(flag_explosion);

-- FIRE_SPREAD
ALTER TABLE region_flags
  ADD COLUMN flag_fire BOOL
    AS (CASE WHEN flag_key = 'FIRE_SPREAD' THEN JSON_VALUE(flag_value, '$') END) PERSISTENT;
CREATE INDEX IF NOT EXISTS idx_region_flags_fire ON region_flags(flag_fire);

-- Optional: Cooldown-Limit als INT fuer schnelle Filter/Sortierung
ALTER TABLE region_limits
  ADD COLUMN limit_cooldown_ms INT
    AS (CASE WHEN limit_key = 'COMPONENT_COOLDOWN_MS' THEN JSON_VALUE(limit_value, '$') END) PERSISTENT;
CREATE INDEX IF NOT EXISTS idx_region_limits_cooldown ON region_limits(limit_cooldown_ms);

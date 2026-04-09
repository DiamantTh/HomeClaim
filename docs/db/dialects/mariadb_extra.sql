-- MariaDB-spezifische Optimierungen (optional, manuell anwenden)
-- Hinweis: Partitionierung ist mit InnoDB+FKs eingeschraenkt; hier nur sichere Indizes/Funktionsindizes.

-- Schneller Lookup auf Flag-/Limit-Keys
CREATE INDEX IF NOT EXISTS idx_region_flags_key ON region_flags(flag_key);
CREATE INDEX IF NOT EXISTS idx_region_limits_key ON region_limits(limit_key);

-- Beispiel fuer Funktionsindex auf JSON-Werte (MariaDB >= 10.4)
--CREATE INDEX idx_region_flags_value ON region_flags ((JSON_EXTRACT(flag_value, '$')));
--CREATE INDEX idx_region_limits_value ON region_limits ((JSON_EXTRACT(limit_value, '$')));

-- Beispiel fuer Partitionierung nach world (nur nutzen, wenn FKs/Partition-Support passt!)
--ALTER TABLE regions PARTITION BY KEY(world) PARTITIONS 8;

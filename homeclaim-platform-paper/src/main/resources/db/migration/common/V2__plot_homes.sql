-- Persistent typed plot homes. World and Area remain derived from regions.area_id.
ALTER TABLE regions ADD COLUMN home_x DOUBLE PRECISION;
ALTER TABLE regions ADD COLUMN home_y DOUBLE PRECISION;
ALTER TABLE regions ADD COLUMN home_z DOUBLE PRECISION;
ALTER TABLE regions ADD COLUMN home_yaw DOUBLE PRECISION;
ALTER TABLE regions ADD COLUMN home_pitch DOUBLE PRECISION;

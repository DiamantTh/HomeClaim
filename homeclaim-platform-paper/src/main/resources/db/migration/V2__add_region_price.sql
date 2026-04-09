-- Add price column to regions table for buy/sell functionality
ALTER TABLE regions ADD COLUMN price DOUBLE PRECISION NOT NULL DEFAULT 0.0;

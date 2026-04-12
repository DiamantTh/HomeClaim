-- Add plot ratings/votes system
CREATE TABLE plot_ratings (
    id UUID PRIMARY KEY,
    region_id UUID NOT NULL REFERENCES regions(id) ON DELETE CASCADE,
    rater_id UUID NOT NULL,
    rating INT NOT NULL CHECK (rating >= 1 AND rating <= 5),
    comment VARCHAR(256),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE(region_id, rater_id)
);

CREATE INDEX idx_plot_ratings_region ON plot_ratings(region_id);
CREATE INDEX idx_plot_ratings_rater ON plot_ratings(rater_id);

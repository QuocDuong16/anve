-- Intervals are retained with the account so delayed uploads cannot recreate deleted points.
CREATE TABLE location_history_deletions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    deleted_range TSTZRANGE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (NOT isempty(deleted_range))
);
CREATE INDEX idx_history_deletions_owner ON location_history_deletions (owner_user_id);
CREATE INDEX idx_history_deletions_range ON location_history_deletions USING GIST (deleted_range);

CREATE TABLE location_deleted_events (
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    event_id UUID NOT NULL,
    PRIMARY KEY (owner_user_id, event_id)
);

ALTER TABLE users ADD COLUMN location_retention_days INTEGER NOT NULL DEFAULT 30 CHECK (location_retention_days BETWEEN 1 AND 30);

ALTER TABLE users ADD COLUMN location_history_generation BIGINT NOT NULL DEFAULT 0 CHECK (location_history_generation >= 0);
ALTER TABLE location_points ADD COLUMN history_generation BIGINT NOT NULL DEFAULT 0 CHECK (history_generation >= 0);
ALTER TABLE users ADD COLUMN location_capture_generation BIGINT NOT NULL DEFAULT 1 CHECK (location_capture_generation > 0);
ALTER TABLE location_points ADD COLUMN capture_generation BIGINT NOT NULL DEFAULT 0 CHECK (capture_generation >= 0);
ALTER TABLE location_audience_grants ADD COLUMN capture_generation BIGINT NOT NULL DEFAULT 0 CHECK (capture_generation >= 0);
CREATE INDEX idx_location_history_order ON location_points (user_id, recorded_at, received_at, event_id);

-- One current-authorization policy shared by latest and history reads.
CREATE VIEW active_location_audience AS
SELECT g.id, g.owner_user_id, g.viewer_user_id, g.circle_id,
       g.sharing_epoch_id, g.visible_from, g.visible_until, g.capture_generation,
       o.display_name, o.location_retention_days
FROM location_audience_grants g
JOIN users o ON o.id = g.owner_user_id AND o.location_sharing_enabled
JOIN location_sharing_epochs e ON e.id = g.sharing_epoch_id AND e.ended_at IS NULL
JOIN memberships om ON om.user_id = g.owner_user_id AND om.circle_id = g.circle_id
JOIN circle_membership_periods op ON op.id = g.owner_membership_period_id
    AND op.membership_id = om.id AND op.ended_at IS NULL
JOIN memberships vm ON vm.user_id = g.viewer_user_id AND vm.circle_id = g.circle_id
JOIN circle_membership_periods vp ON vp.id = g.viewer_membership_period_id
    AND vp.membership_id = vm.id AND vp.ended_at IS NULL
WHERE g.revoked_at IS NULL AND g.visible_from <= now()
  AND (g.visible_until IS NULL OR g.visible_until > now());

-- Monotonic global cutoff prevents a later configuration increase from
-- admitting points already expired under an earlier server policy.
CREATE TABLE location_retention_watermark (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    cutoff TIMESTAMPTZ
);
INSERT INTO location_retention_watermark (singleton) VALUES (TRUE);

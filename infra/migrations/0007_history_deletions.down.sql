-- Removing deletion fences while clients can still upload old points would
-- restore deleted history. An operator must explicitly handle that data first.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM location_history_deletions) OR EXISTS (SELECT 1 FROM location_deleted_events) OR EXISTS (SELECT 1 FROM location_retention_watermark WHERE cutoff IS NOT NULL) OR EXISTS (SELECT 1 FROM users WHERE location_capture_generation <> 1) THEN
        RAISE EXCEPTION 'cannot remove history deletion fences while they exist';
    END IF;
END;
$$;
DROP TABLE location_retention_watermark;
DROP VIEW active_location_audience;
DROP INDEX idx_location_history_order;
ALTER TABLE location_points DROP COLUMN history_generation;
ALTER TABLE users DROP COLUMN location_history_generation;
ALTER TABLE location_audience_grants DROP COLUMN capture_generation;
ALTER TABLE location_points DROP COLUMN capture_generation;
ALTER TABLE users DROP COLUMN location_capture_generation;
ALTER TABLE users DROP COLUMN location_retention_days;
DROP TABLE location_deleted_events;
DROP TABLE location_history_deletions;

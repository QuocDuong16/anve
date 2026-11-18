ALTER TABLE sos_events
    DROP CONSTRAINT IF EXISTS sos_events_location_complete,
    DROP COLUMN IF EXISTS battery_percent,
    DROP COLUMN IF EXISTS location_recorded_at,
    DROP COLUMN IF EXISTS horizontal_accuracy_m,
    DROP COLUMN IF EXISTS longitude,
    DROP COLUMN IF EXISTS latitude;

-- Optional single-snapshot location and battery percentage attached at SOS creation.
ALTER TABLE sos_events
    ADD COLUMN latitude DOUBLE PRECISION CHECK (latitude IS NULL OR (latitude >= -90 AND latitude <= 90)),
    ADD COLUMN longitude DOUBLE PRECISION CHECK (longitude IS NULL OR (longitude >= -180 AND longitude <= 180)),
    ADD COLUMN horizontal_accuracy_m DOUBLE PRECISION CHECK (horizontal_accuracy_m IS NULL OR (horizontal_accuracy_m >= 0 AND horizontal_accuracy_m <= 100000)),
    ADD COLUMN location_recorded_at TIMESTAMPTZ,
    ADD COLUMN battery_percent SMALLINT CHECK (battery_percent IS NULL OR (battery_percent >= 0 AND battery_percent <= 100)),
    ADD CONSTRAINT sos_events_location_complete CHECK (
        (latitude IS NULL AND longitude IS NULL AND horizontal_accuracy_m IS NULL AND location_recorded_at IS NULL) OR
        (latitude IS NOT NULL AND longitude IS NOT NULL AND horizontal_accuracy_m IS NOT NULL AND location_recorded_at IS NOT NULL)
    );

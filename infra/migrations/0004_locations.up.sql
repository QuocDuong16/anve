-- AnVề — M3: consented location ingestion.
--
-- One row per location event accepted from a registered device. The event
-- identity is (user_id, event_id): a retried batch can never create a
-- duplicate row, even when the retry arrives from a different app install
-- of the same account. device_id is audit/query metadata, not part of the
-- idempotency key, because the owner is the stable subject.
--
-- recorded_at is the device's original capture time (kept as-is even when
-- the upload arrives days later); received_at is the server write time.
-- The two must never be collapsed: freshness and backfill depend on both.
--
-- Coordinates are stored twice on purpose: explicit latitude/longitude
-- columns make the value readable/exportable, while `geom` is the PostGIS
-- geography used for spatial queries in M4/M5. Rows are immutable, so the
-- two representations cannot drift.

CREATE TABLE location_points (
    id                        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Client-generated UUID; idempotency key together with user_id.
    event_id                  UUID NOT NULL,
    user_id                   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id                 UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    recorded_at               TIMESTAMPTZ NOT NULL,
    received_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    latitude                  DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude                 DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    geom                      GEOGRAPHY(Point, 4326) NOT NULL,
    horizontal_accuracy_m     DOUBLE PRECISION NOT NULL CHECK (horizontal_accuracy_m >= 0),
    altitude_m                DOUBLE PRECISION,
    speed_mps                 DOUBLE PRECISION CHECK (speed_mps IS NULL OR speed_mps >= 0),
    bearing_deg               DOUBLE PRECISION
                              CHECK (bearing_deg IS NULL OR (bearing_deg >= 0 AND bearing_deg <= 360)),
    provider                  TEXT NOT NULL CHECK (length(provider) BETWEEN 1 AND 32),
    -- Client-reported mock-provider indication. Stored so later consumers
    -- can exclude synthetic fixes; never a substitute for real validation.
    is_mock                   BOOLEAN NOT NULL DEFAULT FALSE,
    schema_version            SMALLINT NOT NULL DEFAULT 1 CHECK (schema_version BETWEEN 1 AND 16),
    -- One durable row per owner event. A retry, a lost response or two
    -- concurrent uploads of the same event all collapse here.
    UNIQUE (user_id, event_id)
);

-- Newest-first per-member history (M5) and per-device diagnostics.
CREATE INDEX idx_location_points_user_recorded
    ON location_points (user_id, recorded_at DESC);
CREATE INDEX idx_location_points_device_recorded
    ON location_points (device_id, recorded_at DESC);

-- Retention deletes by capture time across all users; without this index
-- the purge becomes a sequential scan as history grows.
CREATE INDEX idx_location_points_recorded ON location_points (recorded_at);

-- Spatial queries for M4/M5 (nearby/last known, polylines).
CREATE INDEX idx_location_points_geom ON location_points USING GIST (geom);

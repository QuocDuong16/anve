-- Serialize consent mutations across devices and delayed worker responses.
ALTER TABLE users
    ADD COLUMN location_sharing_version BIGINT NOT NULL DEFAULT 0
        CHECK (location_sharing_version >= 0);

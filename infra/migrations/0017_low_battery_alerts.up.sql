-- Opt-in low-battery alerts (15% and 5%) during active sharing or Safe Journey.
-- Preferences default OFF with compare-and-set version; alert payloads honor
-- history deletion and retention while fences survive until account deletion.

ALTER TABLE users
    ADD COLUMN low_battery_alerts_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN low_battery_alerts_version BIGINT NOT NULL DEFAULT 0
        CHECK (low_battery_alerts_version >= 0);

CREATE TABLE low_battery_alert_fences (
    event_id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE low_battery_alerts (
    id UUID PRIMARY KEY REFERENCES low_battery_alert_fences(event_id) ON DELETE CASCADE,
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    circle_id UUID NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
    owner_period_id UUID NOT NULL REFERENCES circle_membership_periods(id) ON DELETE CASCADE,
    sharing_epoch_id UUID NOT NULL REFERENCES location_sharing_epochs(id) ON DELETE CASCADE,
    journey_id UUID REFERENCES journeys(id) ON DELETE CASCADE,
    scope TEXT NOT NULL CHECK (scope IN ('SHARING', 'JOURNEY')),
    threshold SMALLINT NOT NULL CHECK (threshold IN (15, 5)),
    battery_percent SMALLINT NOT NULL CHECK (battery_percent BETWEEN 0 AND 100),
    latitude DOUBLE PRECISION CHECK (latitude IS NULL OR (latitude >= -90 AND latitude <= 90)),
    longitude DOUBLE PRECISION CHECK (longitude IS NULL OR (longitude >= -180 AND longitude <= 180)),
    horizontal_accuracy_m DOUBLE PRECISION CHECK (horizontal_accuracy_m IS NULL OR (horizontal_accuracy_m >= 0 AND horizontal_accuracy_m <= 100000)),
    location_recorded_at TIMESTAMPTZ,
    geom GEOGRAPHY(Point, 4326),
    capture_generation BIGINT NOT NULL DEFAULT 0 CHECK (capture_generation >= 0),
    recorded_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    resolved_at TIMESTAMPTZ,
    request_hash BYTEA NOT NULL CHECK (octet_length(request_hash) = 32),
    CHECK ((scope = 'SHARING' AND journey_id IS NULL) OR (scope = 'JOURNEY' AND journey_id IS NOT NULL)),
    CONSTRAINT low_battery_alerts_location_complete CHECK (
        (latitude IS NULL AND longitude IS NULL AND horizontal_accuracy_m IS NULL AND location_recorded_at IS NULL AND geom IS NULL) OR
        (latitude IS NOT NULL AND longitude IS NOT NULL AND horizontal_accuracy_m IS NOT NULL AND location_recorded_at IS NOT NULL AND geom IS NOT NULL)
    )
);

CREATE INDEX idx_low_battery_alerts_owner_recorded
    ON low_battery_alerts (owner_user_id, recorded_at DESC, received_at DESC);
CREATE INDEX idx_low_battery_alerts_cooldown
    ON low_battery_alerts (owner_user_id, threshold, received_at DESC)
    WHERE resolved_at IS NULL;
CREATE INDEX idx_low_battery_alerts_journey
    ON low_battery_alerts (journey_id, recorded_at DESC)
    WHERE journey_id IS NOT NULL;
CREATE INDEX idx_low_battery_alerts_retention
    ON low_battery_alerts (recorded_at, id);

CREATE TABLE low_battery_alert_recipients (
    alert_id UUID NOT NULL REFERENCES low_battery_alerts(id) ON DELETE CASCADE,
    recipient_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    grant_id UUID NOT NULL REFERENCES location_audience_grants(id) ON DELETE CASCADE,
    PRIMARY KEY (alert_id, recipient_user_id)
);

CREATE INDEX idx_low_battery_alert_recipients_user
    ON low_battery_alert_recipients (recipient_user_id, alert_id);
CREATE INDEX idx_low_battery_alert_recipients_grant
    ON low_battery_alert_recipients (grant_id, alert_id);

CREATE TABLE low_battery_notification_outbox (
    alert_id UUID NOT NULL,
    recipient_user_id UUID NOT NULL,
    state TEXT NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED', 'ACCEPTED', 'SUPPRESSED', 'EXPIRED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    accepted_at TIMESTAMPTZ,
    PRIMARY KEY (alert_id, recipient_user_id),
    FOREIGN KEY (alert_id, recipient_user_id)
        REFERENCES low_battery_alert_recipients(alert_id, recipient_user_id) ON DELETE CASCADE
);

CREATE INDEX idx_low_battery_notifications_due
    ON low_battery_notification_outbox (next_attempt_at, alert_id)
    WHERE state = 'QUEUED';

CREATE TABLE low_battery_notification_targets (
    alert_id UUID NOT NULL,
    recipient_user_id UUID NOT NULL,
    device_id UUID NOT NULL REFERENCES device_push_tokens(device_id) ON DELETE CASCADE,
    state TEXT NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED', 'ACCEPTED', 'SUPPRESSED', 'INVALID', 'EXPIRED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    accepted_at TIMESTAMPTZ,
    PRIMARY KEY (alert_id, recipient_user_id, device_id),
    FOREIGN KEY (alert_id, recipient_user_id)
        REFERENCES low_battery_notification_outbox(alert_id, recipient_user_id) ON DELETE CASCADE
);

CREATE FUNCTION purge_low_battery_alerts_on_history_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'users' THEN
        IF NEW.location_retention_days = OLD.location_retention_days THEN
            RETURN NEW;
        END IF;
        DELETE FROM low_battery_alerts WHERE owner_user_id = NEW.id;
    ELSE
        DELETE FROM low_battery_alerts WHERE owner_user_id = NEW.owner_user_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER history_deletions_purge_low_battery_alerts
    AFTER INSERT ON location_history_deletions
    FOR EACH ROW EXECUTE FUNCTION purge_low_battery_alerts_on_history_change();

CREATE TRIGGER retention_changes_purge_low_battery_alerts
    AFTER UPDATE OF location_retention_days ON users
    FOR EACH ROW EXECUTE FUNCTION purge_low_battery_alerts_on_history_change();

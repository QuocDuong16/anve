-- SOS metadata is explicitly addressed and contains no location data.
-- A cancellation may precede a delayed create with the same client ID.
CREATE TABLE sos_cancelled_requests (
    event_id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    cancelled_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
-- Retired IDs remain reserved so a delayed or reused ID cannot create a new
-- event after the event payload has been purged.
CREATE TABLE sos_retired_requests (
    event_id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    retired_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE sos_events (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    circle_id UUID NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
    owner_period_id UUID NOT NULL REFERENCES circle_membership_periods(id) ON DELETE CASCADE,
    recorded_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    state TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE','CANCELLED','RESOLVED','EXPIRED')),
    transitioned_at TIMESTAMPTZ,
    request_hash BYTEA NOT NULL CHECK (octet_length(request_hash) = 32)
);
CREATE INDEX idx_sos_owner_time ON sos_events(owner_user_id, received_at DESC, id DESC);
CREATE INDEX idx_sos_retention ON sos_events(received_at, id);

CREATE TABLE sos_recipients (
    event_id UUID NOT NULL REFERENCES sos_events(id) ON DELETE CASCADE,
    recipient_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    membership_period_id UUID NOT NULL REFERENCES circle_membership_periods(id) ON DELETE CASCADE,
    acknowledged_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    PRIMARY KEY (event_id, recipient_user_id)
);
CREATE INDEX idx_sos_recipient ON sos_recipients(recipient_user_id, event_id);

-- A committed event and its recipient work are created in one transaction.
-- A future dispatcher must recheck authorization immediately before send.
CREATE TABLE sos_notification_outbox (
    event_id UUID NOT NULL,
    recipient_user_id UUID NOT NULL,
    state TEXT NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED','ACCEPTED','SUPPRESSED','EXPIRED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    accepted_at TIMESTAMPTZ,
    PRIMARY KEY (event_id, recipient_user_id),
    FOREIGN KEY (event_id, recipient_user_id) REFERENCES sos_recipients(event_id, recipient_user_id) ON DELETE CASCADE
);
CREATE INDEX idx_sos_notification_due ON sos_notification_outbox(next_attempt_at, event_id) WHERE state='QUEUED';

CREATE TABLE device_push_tokens (
    device_id UUID PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    token TEXT NOT NULL UNIQUE CHECK (length(token) BETWEEN 1 AND 4096),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE FUNCTION remove_revoked_push_token() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.revoked_at IS NULL AND NEW.revoked_at IS NOT NULL THEN
        DELETE FROM device_push_tokens WHERE session_id=NEW.id;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER sessions_remove_push_token AFTER UPDATE OF revoked_at ON sessions
FOR EACH ROW EXECUTE FUNCTION remove_revoked_push_token();

CREATE TABLE sos_notification_targets (
    event_id UUID NOT NULL,
    recipient_user_id UUID NOT NULL,
    device_id UUID NOT NULL REFERENCES device_push_tokens(device_id) ON DELETE CASCADE,
    state TEXT NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED','ACCEPTED','SUPPRESSED','INVALID','EXPIRED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    accepted_at TIMESTAMPTZ,
    PRIMARY KEY (event_id, recipient_user_id, device_id),
    FOREIGN KEY (event_id, recipient_user_id) REFERENCES sos_notification_outbox(event_id, recipient_user_id) ON DELETE CASCADE
);
CREATE INDEX idx_sos_target_due ON sos_notification_targets(next_attempt_at, event_id) WHERE state='QUEUED';

CREATE TABLE place_notification_outbox (
    event_id UUID NOT NULL REFERENCES place_events(id) ON DELETE CASCADE,
    recipient_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    audience_grant_id UUID NOT NULL REFERENCES location_audience_grants(id) ON DELETE CASCADE,
    place_access_id UUID REFERENCES place_access(id) ON DELETE CASCADE,
    state TEXT NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED','ACCEPTED','SUPPRESSED','EXPIRED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    accepted_at TIMESTAMPTZ,
    PRIMARY KEY (event_id, recipient_user_id)
);
CREATE INDEX idx_place_notification_due ON place_notification_outbox(next_attempt_at, event_id) WHERE state='QUEUED';
CREATE TABLE place_notification_targets (
    event_id UUID NOT NULL,
    recipient_user_id UUID NOT NULL,
    device_id UUID NOT NULL REFERENCES device_push_tokens(device_id) ON DELETE CASCADE,
    state TEXT NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED','ACCEPTED','SUPPRESSED','INVALID','EXPIRED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    accepted_at TIMESTAMPTZ,
    PRIMARY KEY (event_id, recipient_user_id, device_id),
    FOREIGN KEY (event_id, recipient_user_id) REFERENCES place_notification_outbox(event_id, recipient_user_id) ON DELETE CASCADE
);
CREATE FUNCTION enqueue_place_notifications() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO place_notification_outbox(event_id, recipient_user_id, audience_grant_id, place_access_id)
    SELECT NEW.id, r.recipient_user_id, r.audience_grant_id, r.place_access_id
    FROM place_monitor_recipients r WHERE r.monitor_id=NEW.monitor_id
    ON CONFLICT DO NOTHING;
    RETURN NEW;
END;
$$;
CREATE TRIGGER place_events_enqueue_notification AFTER INSERT ON place_events
FOR EACH ROW EXECUTE FUNCTION enqueue_place_notifications();

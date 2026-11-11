-- Journey selection only narrows the exact pre-existing location grants.
CREATE TABLE journey_requests (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    cancelled BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE journeys (
    id UUID PRIMARY KEY REFERENCES journey_requests(id) ON DELETE CASCADE,
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sharing_epoch_id UUID NOT NULL REFERENCES location_sharing_epochs(id) ON DELETE CASCADE,
    destination_label TEXT NOT NULL CHECK (length(destination_label) BETWEEN 1 AND 80),
    destination GEOGRAPHY(POINT,4326) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    state TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE','ARRIVED','ENDED','CANCELLED','EXPIRED')),
    request_hash BYTEA NOT NULL CHECK (octet_length(request_hash)=32),
    CHECK (expires_at>started_at AND expires_at<=started_at+interval '8 hours')
);
CREATE UNIQUE INDEX one_active_journey_per_owner ON journeys(owner_user_id) WHERE state='ACTIVE';
CREATE INDEX idx_journeys_retention ON journeys(started_at,id);
CREATE TABLE journey_recipients (
    journey_id UUID NOT NULL REFERENCES journeys(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    grant_id UUID NOT NULL REFERENCES location_audience_grants(id) ON DELETE CASCADE,
    revoked_at TIMESTAMPTZ,
    PRIMARY KEY (journey_id,user_id)
);
CREATE INDEX idx_journey_recipients_user ON journey_recipients(user_id,journey_id);
CREATE TABLE journey_notification_outbox (
    journey_id UUID NOT NULL,
    recipient_user_id UUID NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('ACTIVE','ARRIVED','ENDED','CANCELLED','EXPIRED')),
    state TEXT NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED','ACCEPTED','SUPPRESSED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts>=0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    accepted_at TIMESTAMPTZ,
    PRIMARY KEY (journey_id,recipient_user_id,kind),
    FOREIGN KEY (journey_id,recipient_user_id) REFERENCES journey_recipients(journey_id,user_id) ON DELETE CASCADE
);
CREATE INDEX idx_journey_notifications_due ON journey_notification_outbox(next_attempt_at) WHERE state='QUEUED';
CREATE TABLE journey_notification_targets (
    journey_id UUID NOT NULL,
    recipient_user_id UUID NOT NULL,
    kind TEXT NOT NULL,
    device_id UUID NOT NULL REFERENCES device_push_tokens(device_id) ON DELETE CASCADE,
    accepted_at TIMESTAMPTZ,
    PRIMARY KEY (journey_id,recipient_user_id,kind,device_id),
    FOREIGN KEY (journey_id,recipient_user_id,kind) REFERENCES journey_notification_outbox(journey_id,recipient_user_id,kind) ON DELETE CASCADE
);

-- This view is checked on every read and dispatch, including terminal states.
CREATE VIEW authorized_journey_recipients AS
SELECT r.journey_id,r.user_id,g.id AS grant_id,g.visible_from,g.capture_generation
FROM journey_recipients r
JOIN journeys j ON j.id=r.journey_id
JOIN active_location_audience g ON g.id=r.grant_id
    AND g.owner_user_id=j.owner_user_id AND g.viewer_user_id=r.user_id
    AND g.sharing_epoch_id=j.sharing_epoch_id
WHERE r.revoked_at IS NULL
  AND g.visible_from<=clock_timestamp()
  AND (g.visible_until IS NULL OR g.visible_until>clock_timestamp());

CREATE VIEW current_journeys AS
SELECT j.*,
    CASE WHEN j.state<>'ACTIVE' THEN j.state
         WHEN j.expires_at<=clock_timestamp() THEN 'EXPIRED'
         WHEN NOT EXISTS (SELECT 1 FROM authorized_journey_recipients r WHERE r.journey_id=j.id) THEN 'CANCELLED'
         ELSE 'ACTIVE' END AS effective_state
FROM journeys j;

-- A destination is sensitive too. Deleting history or changing retention
-- removes all journey content, even if no copied point exists. ID fences stay.
CREATE FUNCTION purge_journeys_on_history_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME='users' THEN
        IF NEW.location_retention_days=OLD.location_retention_days THEN RETURN NEW; END IF;
        DELETE FROM journeys WHERE owner_user_id=NEW.id;
    ELSE
        DELETE FROM journeys WHERE owner_user_id=NEW.owner_user_id;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER history_deletions_purge_journeys AFTER INSERT ON location_history_deletions
FOR EACH ROW EXECUTE FUNCTION purge_journeys_on_history_change();
CREATE TRIGGER retention_changes_purge_journeys AFTER UPDATE OF location_retention_days ON users
FOR EACH ROW EXECUTE FUNCTION purge_journeys_on_history_change();

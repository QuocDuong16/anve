-- Places are shared separately from location grants. Membership alone never
-- activates monitoring or authorizes a recipient of presence events.
CREATE TABLE places (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    circle_id UUID NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
    owner_membership_period_id UUID NOT NULL,
    name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 80),
    latitude DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    radius_m DOUBLE PRECISION NOT NULL CHECK (radius_m BETWEEN 50 AND 5000),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (owner_membership_period_id, circle_id, owner_user_id)
      REFERENCES circle_membership_periods(id, circle_id, user_id) ON DELETE CASCADE
);
CREATE INDEX idx_places_owner ON places(owner_user_id, created_at);

CREATE TABLE place_access (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    place_id UUID NOT NULL REFERENCES places(id) ON DELETE CASCADE,
    member_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    member_period_id UUID NOT NULL REFERENCES circle_membership_periods(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    revoked_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX one_active_place_access ON place_access(place_id, member_user_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_place_access_member ON place_access(member_user_id) WHERE revoked_at IS NULL;

CREATE TABLE place_monitors (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    place_id UUID NOT NULL REFERENCES places(id) ON DELETE CASCADE,
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    owner_period_id UUID NOT NULL REFERENCES circle_membership_periods(id) ON DELETE CASCADE,
    sharing_epoch_id UUID NOT NULL REFERENCES location_sharing_epochs(id) ON DELETE CASCADE,
    place_revision BIGINT NOT NULL,
    capture_generation BIGINT NOT NULL CHECK (capture_generation > 0),
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    revoked_at TIMESTAMPTZ,
    stable_state TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK (stable_state IN ('UNKNOWN', 'INSIDE', 'OUTSIDE')),
    candidate_state TEXT CHECK (candidate_state IN ('INSIDE', 'OUTSIDE')),
    candidate_since TIMESTAMPTZ,
    last_observed_at TIMESTAMPTZ,
    stable_point_id BIGINT REFERENCES location_points(id) ON DELETE SET NULL,
    candidate_point_id BIGINT REFERENCES location_points(id) ON DELETE SET NULL
);
CREATE UNIQUE INDEX one_active_place_monitor ON place_monitors(place_id, owner_user_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_place_monitors_owner ON place_monitors(owner_user_id) WHERE revoked_at IS NULL;

CREATE TABLE place_monitor_recipients (
    monitor_id UUID NOT NULL REFERENCES place_monitors(id) ON DELETE CASCADE,
    recipient_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    audience_grant_id UUID NOT NULL REFERENCES location_audience_grants(id) ON DELETE CASCADE,
    place_access_id UUID REFERENCES place_access(id) ON DELETE CASCADE,
    PRIMARY KEY (monitor_id, recipient_user_id)
);
CREATE INDEX idx_place_recipients_user ON place_monitor_recipients(recipient_user_id);

CREATE TABLE place_event_queue (
    point_id BIGINT PRIMARY KEY REFERENCES location_points(id) ON DELETE CASCADE,
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    recorded_at TIMESTAMPTZ NOT NULL,
    enqueued_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 100),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_place_event_queue_retry ON place_event_queue(next_attempt_at, recorded_at, point_id);
CREATE INDEX idx_place_event_queue_owner_time ON place_event_queue(owner_user_id, recorded_at, point_id);

CREATE TABLE place_events (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    monitor_id UUID NOT NULL REFERENCES place_monitors(id) ON DELETE CASCADE,
    point_id BIGINT NOT NULL REFERENCES location_points(id) ON DELETE CASCADE,
    baseline_point_id BIGINT NOT NULL REFERENCES location_points(id) ON DELETE CASCADE,
    evidence_point_id BIGINT NOT NULL REFERENCES location_points(id) ON DELETE CASCADE,
    transition TEXT NOT NULL CHECK (transition IN ('ENTER', 'EXIT')),
    recorded_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (monitor_id, point_id, transition)
);
CREATE INDEX idx_place_events_time ON place_events(recorded_at DESC, id DESC);

-- A consent/epoch or membership boundary cannot silently re-enable monitoring.
CREATE FUNCTION close_place_monitors_for_epoch() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.ended_at IS NULL AND NEW.ended_at IS NOT NULL THEN
        UPDATE place_monitors SET revoked_at=clock_timestamp()
        WHERE sharing_epoch_id=NEW.id AND revoked_at IS NULL;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER close_place_monitors_on_epoch_end AFTER UPDATE OF ended_at ON location_sharing_epochs
FOR EACH ROW EXECUTE FUNCTION close_place_monitors_for_epoch();

CREATE FUNCTION close_place_access_for_membership() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.ended_at IS NULL AND NEW.ended_at IS NOT NULL THEN
        UPDATE place_access SET revoked_at=clock_timestamp() WHERE member_period_id=NEW.id AND revoked_at IS NULL;
        UPDATE place_monitors pm SET revoked_at=clock_timestamp()
        WHERE pm.revoked_at IS NULL AND (pm.owner_period_id=NEW.id OR EXISTS
          (SELECT 1 FROM places p WHERE p.id=pm.place_id AND p.owner_membership_period_id=NEW.id));
        DELETE FROM places WHERE owner_membership_period_id=NEW.id;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER close_place_access_on_membership_end AFTER UPDATE OF ended_at ON circle_membership_periods
FOR EACH ROW EXECUTE FUNCTION close_place_access_for_membership();

-- M4: temporal Circle membership, sharing epochs and explicit audiences.
-- Existing M3 points deliberately retain NULL sharing_epoch_id and are not
-- made visible by this migration.

CREATE TABLE circle_membership_periods (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    membership_id   UUID NOT NULL UNIQUE,
    circle_id       UUID NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    started_at      TIMESTAMPTZ NOT NULL,
    ended_at        TIMESTAMPTZ,
    CHECK (ended_at IS NULL OR ended_at >= started_at),
    UNIQUE (id, circle_id, user_id)
);

CREATE UNIQUE INDEX one_active_membership_period_per_user
    ON circle_membership_periods (user_id) WHERE ended_at IS NULL;
CREATE INDEX idx_membership_period_circle_user_time
    ON circle_membership_periods (circle_id, user_id, started_at, ended_at);

-- Existing joined_at is the earliest trustworthy membership timestamp.
INSERT INTO circle_membership_periods (membership_id, circle_id, user_id, started_at)
SELECT id, circle_id, user_id, joined_at FROM memberships;

CREATE TABLE location_sharing_epochs (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    owner_user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    circle_id               UUID NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
    owner_membership_period_id UUID NOT NULL,
    consent_version         BIGINT NOT NULL CHECK (consent_version >= 0),
    started_at              TIMESTAMPTZ NOT NULL,
    ended_at                TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (ended_at IS NULL OR ended_at >= started_at),
    FOREIGN KEY (owner_membership_period_id, circle_id, owner_user_id)
        REFERENCES circle_membership_periods(id, circle_id, user_id) ON DELETE CASCADE,
    UNIQUE (id, owner_user_id, circle_id, owner_membership_period_id)
);

CREATE UNIQUE INDEX one_active_sharing_epoch_per_owner_circle
    ON location_sharing_epochs (owner_user_id, circle_id) WHERE ended_at IS NULL;
CREATE INDEX idx_sharing_epochs_owner_time
    ON location_sharing_epochs (owner_user_id, started_at DESC, ended_at);

CREATE TABLE location_audience_grants (
    id                          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    owner_user_id               UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    viewer_user_id              UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    circle_id                   UUID NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
    owner_membership_period_id  UUID NOT NULL,
    viewer_membership_period_id UUID NOT NULL,
    sharing_epoch_id            UUID NOT NULL,
    generation                  BIGINT NOT NULL CHECK (generation > 0),
    visible_from                TIMESTAMPTZ NOT NULL,
    visible_until               TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at                  TIMESTAMPTZ,
    CHECK (owner_user_id <> viewer_user_id),
    CHECK (visible_until IS NULL OR visible_until >= visible_from),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at),
    FOREIGN KEY (owner_membership_period_id, circle_id, owner_user_id)
        REFERENCES circle_membership_periods(id, circle_id, user_id) ON DELETE CASCADE,
    FOREIGN KEY (viewer_membership_period_id, circle_id, viewer_user_id)
        REFERENCES circle_membership_periods(id, circle_id, user_id) ON DELETE CASCADE,
    FOREIGN KEY (sharing_epoch_id, owner_user_id, circle_id, owner_membership_period_id)
        REFERENCES location_sharing_epochs(id, owner_user_id, circle_id, owner_membership_period_id)
        ON DELETE CASCADE
);

CREATE UNIQUE INDEX one_active_audience_grant_per_epoch
    ON location_audience_grants (owner_user_id, viewer_user_id, sharing_epoch_id)
    WHERE revoked_at IS NULL;
CREATE INDEX idx_audience_grants_viewer_active
    ON location_audience_grants (viewer_user_id, circle_id, visible_from DESC)
    WHERE revoked_at IS NULL;
CREATE INDEX idx_audience_grants_owner_active
    ON location_audience_grants (owner_user_id, circle_id, viewer_user_id)
    WHERE revoked_at IS NULL;

CREATE FUNCTION validate_location_audience_grant_window() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    owner_start TIMESTAMPTZ;
    owner_end TIMESTAMPTZ;
    viewer_start TIMESTAMPTZ;
    viewer_end TIMESTAMPTZ;
    epoch_start TIMESTAMPTZ;
    epoch_end TIMESTAMPTZ;
BEGIN
    SELECT started_at, ended_at INTO owner_start, owner_end
      FROM circle_membership_periods WHERE id = NEW.owner_membership_period_id;
    SELECT started_at, ended_at INTO viewer_start, viewer_end
      FROM circle_membership_periods WHERE id = NEW.viewer_membership_period_id;
    SELECT started_at, ended_at INTO epoch_start, epoch_end
      FROM location_sharing_epochs WHERE id = NEW.sharing_epoch_id;

    IF owner_start IS NULL OR viewer_start IS NULL OR epoch_start IS NULL THEN
        RAISE EXCEPTION 'audience grant references a missing temporal boundary'
            USING ERRCODE = 'check_violation',
                  CONSTRAINT = 'location_audience_grant_temporal_boundaries';
    END IF;
    IF TG_OP = 'INSERT' AND (owner_end IS NOT NULL OR viewer_end IS NOT NULL OR epoch_end IS NOT NULL) THEN
        RAISE EXCEPTION 'audience grant requires active membership periods and sharing epoch'
            USING ERRCODE = 'check_violation',
                  CONSTRAINT = 'location_audience_grant_active_boundaries';
    END IF;
    IF NEW.visible_from < GREATEST(owner_start, viewer_start, epoch_start) THEN
        RAISE EXCEPTION 'audience grant starts before its temporal authorization window'
            USING ERRCODE = 'check_violation',
                  CONSTRAINT = 'location_audience_grant_starts_within_window';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER location_audience_grants_validate_window
BEFORE INSERT OR UPDATE ON location_audience_grants
FOR EACH ROW EXECUTE FUNCTION validate_location_audience_grant_window();

ALTER TABLE location_points
    ADD COLUMN sharing_epoch_id UUID REFERENCES location_sharing_epochs(id) ON DELETE SET NULL;
CREATE INDEX idx_location_points_epoch_recorded
    ON location_points (sharing_epoch_id, recorded_at DESC, received_at DESC)
    WHERE sharing_epoch_id IS NOT NULL;

CREATE FUNCTION validate_location_point_sharing_epoch() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    epoch_owner UUID;
    epoch_start TIMESTAMPTZ;
BEGIN
    IF NEW.sharing_epoch_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT owner_user_id, started_at INTO epoch_owner, epoch_start
      FROM location_sharing_epochs WHERE id = NEW.sharing_epoch_id;
    IF epoch_owner IS NULL OR epoch_owner <> NEW.user_id OR NEW.recorded_at < epoch_start THEN
        RAISE EXCEPTION 'location point does not belong to its sharing epoch'
            USING ERRCODE = 'check_violation',
                  CONSTRAINT = 'location_point_sharing_epoch_matches_owner_and_time';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER location_points_validate_sharing_epoch
BEFORE INSERT OR UPDATE OF sharing_epoch_id, recorded_at, user_id ON location_points
FOR EACH ROW EXECUTE FUNCTION validate_location_point_sharing_epoch();

-- Existing sharing-on accounts begin a new epoch at migration time. Their
-- earlier points remain unassociated and therefore invisible to M4 readers.
INSERT INTO location_sharing_epochs (
    owner_user_id, circle_id, owner_membership_period_id,
    consent_version, started_at
)
SELECT u.id, m.circle_id, p.id, u.location_sharing_version, now()
FROM users u
JOIN memberships m ON m.user_id = u.id
JOIN circle_membership_periods p ON p.membership_id = m.id
WHERE u.location_sharing_enabled;

CREATE FUNCTION maintain_location_membership_period() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    period_id UUID;
    epoch_row location_sharing_epochs%ROWTYPE;
    actor_id UUID;
    event_row RECORD;
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO circle_membership_periods (membership_id, circle_id, user_id, started_at)
        VALUES (NEW.id, NEW.circle_id, NEW.user_id, NEW.joined_at)
        RETURNING id INTO period_id;

        INSERT INTO location_sharing_epochs (
            owner_user_id, circle_id, owner_membership_period_id,
            consent_version, started_at
        )
        SELECT u.id, NEW.circle_id, period_id, u.location_sharing_version, clock_timestamp()
        FROM users u
        WHERE u.id = NEW.user_id AND u.location_sharing_enabled
        ON CONFLICT (owner_user_id, circle_id) WHERE ended_at IS NULL DO NOTHING
        RETURNING * INTO epoch_row;

        IF epoch_row.id IS NOT NULL THEN
            INSERT INTO audit_log (actor_user_id, action, target_user_id, detail)
            VALUES (NEW.user_id, 'location.sharing_epoch.opened', NEW.user_id,
                    jsonb_build_object('circle_id', NEW.circle_id, 'epoch_id', epoch_row.id));
        END IF;
        RETURN NEW;
    END IF;

    SELECT id INTO period_id
    FROM circle_membership_periods
    WHERE membership_id = OLD.id
    FOR UPDATE;
    IF period_id IS NULL THEN
        RETURN OLD;
    END IF;

    actor_id := COALESCE(
        NULLIF(current_setting('app.actor_user_id', true), '')::UUID,
        OLD.user_id
    );
    UPDATE circle_membership_periods
       SET ended_at = GREATEST(started_at, clock_timestamp())
     WHERE id = period_id AND ended_at IS NULL;

    WITH closed AS (
        UPDATE location_audience_grants g
           SET revoked_at = clock_timestamp(),
               visible_until = GREATEST(g.visible_from, LEAST(COALESCE(g.visible_until, clock_timestamp()), clock_timestamp()))
         WHERE g.revoked_at IS NULL
           AND (g.viewer_membership_period_id = period_id
                OR g.sharing_epoch_id IN (
                    SELECT e.id FROM location_sharing_epochs e
                    WHERE e.owner_membership_period_id = period_id AND e.ended_at IS NULL
                ))
        RETURNING g.id, g.owner_user_id, g.viewer_user_id, g.circle_id
    )
    INSERT INTO audit_log (actor_user_id, action, target_user_id, detail)
    SELECT actor_id, 'location.audience_grant.closed_membership', viewer_user_id,
           jsonb_build_object('grant_id', id, 'circle_id', circle_id, 'owner_user_id', owner_user_id)
    FROM closed;

    WITH closed AS (
        UPDATE location_sharing_epochs
           SET ended_at = GREATEST(started_at, clock_timestamp())
         WHERE owner_membership_period_id = period_id AND ended_at IS NULL
        RETURNING id, owner_user_id, circle_id
    )
    INSERT INTO audit_log (actor_user_id, action, target_user_id, detail)
    SELECT actor_id, 'location.sharing_epoch.closed', owner_user_id,
           jsonb_build_object('epoch_id', id, 'circle_id', circle_id)
    FROM closed;

    RETURN OLD;
END;
$$;

CREATE TRIGGER memberships_location_period_after_insert
AFTER INSERT ON memberships
FOR EACH ROW EXECUTE FUNCTION maintain_location_membership_period();

CREATE TRIGGER memberships_location_period_before_delete
BEFORE DELETE ON memberships
FOR EACH ROW EXECUTE FUNCTION maintain_location_membership_period();

-- Close grants before Circle cascades remove memberships, periods and grants.
-- The dedicated Circle trigger makes audit and visibility closure independent
-- of the order PostgreSQL executes cascading foreign-key actions.
CREATE FUNCTION close_location_circle_audience() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    actor_id UUID;
BEGIN
    actor_id := COALESCE(
        NULLIF(current_setting('app.actor_user_id', true), '')::UUID,
        OLD.created_by
    );

    UPDATE circle_membership_periods
       SET ended_at = GREATEST(started_at, clock_timestamp())
     WHERE circle_id = OLD.id AND ended_at IS NULL;

    WITH closed AS (
        UPDATE location_audience_grants
           SET revoked_at = clock_timestamp(),
               visible_until = GREATEST(visible_from, LEAST(COALESCE(visible_until, clock_timestamp()), clock_timestamp()))
         WHERE circle_id = OLD.id AND revoked_at IS NULL
        RETURNING id, owner_user_id, viewer_user_id, circle_id
    )
    INSERT INTO audit_log (actor_user_id, action, target_user_id, detail)
    SELECT actor_id, 'location.audience_grant.closed_membership', viewer_user_id,
           jsonb_build_object('grant_id', id, 'circle_id', circle_id, 'owner_user_id', owner_user_id,
                              'reason', 'circle_dissolved')
    FROM closed;

    WITH closed AS (
        UPDATE location_sharing_epochs
           SET ended_at = GREATEST(started_at, clock_timestamp())
         WHERE circle_id = OLD.id AND ended_at IS NULL
        RETURNING id, owner_user_id, circle_id
    )
    INSERT INTO audit_log (actor_user_id, action, target_user_id, detail)
    SELECT actor_id, 'location.sharing_epoch.closed', owner_user_id,
           jsonb_build_object('epoch_id', id, 'circle_id', circle_id, 'reason', 'circle_dissolved')
    FROM closed;

    RETURN OLD;
END;
$$;

CREATE TRIGGER circles_location_audience_before_delete
BEFORE DELETE ON circles
FOR EACH ROW EXECUTE FUNCTION close_location_circle_audience();

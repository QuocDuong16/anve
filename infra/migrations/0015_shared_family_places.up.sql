-- Optional creator-controlled flag marking a place definition as shared
-- with the whole active Circle (including members who join later).
-- Sharing a place definition never activates monitoring or location sharing.
ALTER TABLE places
    ADD COLUMN shared_with_circle BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_places_shared_circle
    ON places (circle_id, created_at, id)
    WHERE shared_with_circle = TRUE;

CREATE FUNCTION grant_shared_places_on_membership() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    existing_count INTEGER;
    remaining INTEGER;
BEGIN
    IF NEW.ended_at IS NULL THEN
        SELECT count(*) INTO existing_count
        FROM place_access
        WHERE member_user_id = NEW.user_id AND revoked_at IS NULL;

        remaining := GREATEST(0, 50 - existing_count);
        IF remaining > 0 THEN
            INSERT INTO place_access (place_id, member_user_id, member_period_id)
            SELECT p.id, NEW.user_id, NEW.id
            FROM places p
            JOIN circle_membership_periods op ON op.id = p.owner_membership_period_id AND op.ended_at IS NULL
            JOIN memberships om ON om.id = op.membership_id AND om.user_id = p.owner_user_id AND om.circle_id = p.circle_id
            WHERE p.circle_id = NEW.circle_id
              AND p.shared_with_circle = TRUE
              AND p.owner_user_id <> NEW.user_id
            ORDER BY p.created_at, p.id
            LIMIT remaining
            ON CONFLICT (place_id, member_user_id) WHERE revoked_at IS NULL DO NOTHING;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER grant_shared_places_on_membership_insert
AFTER INSERT ON circle_membership_periods
FOR EACH ROW EXECUTE FUNCTION grant_shared_places_on_membership();

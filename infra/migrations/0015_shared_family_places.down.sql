DROP TRIGGER grant_shared_places_on_membership_insert ON circle_membership_periods;
DROP FUNCTION grant_shared_places_on_membership();
DROP INDEX idx_places_shared_circle;
ALTER TABLE places DROP COLUMN shared_with_circle;

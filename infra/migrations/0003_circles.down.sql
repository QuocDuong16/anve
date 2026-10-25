-- Rollback for 0003_circles.
--
-- Memberships and invites cascade from circles, but drop them explicitly to
-- keep the order readable and to fail loudly if the cascade assumptions
-- ever change.

DROP TABLE IF EXISTS invites;
DROP TABLE IF EXISTS memberships;
DROP TABLE IF EXISTS circles;

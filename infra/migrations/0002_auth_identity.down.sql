-- Rollback for 0002_auth_identity.
--
-- Drops the M1 tables in dependency order. The audit_log table has no
-- foreign keys by design (its rows must survive account deletion), so it
-- could be dropped first, but the order below keeps the rollback readable.

DROP TABLE IF EXISTS audit_log;
DROP TABLE IF EXISTS sessions;
DROP TABLE IF EXISTS devices;
DROP TABLE IF EXISTS users;

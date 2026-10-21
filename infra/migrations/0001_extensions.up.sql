-- AnVề — baseline schema.
--
-- M0 only provisions the database: extensions, the PostGIS geography setup
-- and the schema_migrations bookkeeping table. Feature tables arrive with the
-- milestones that need them.

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Applied migrations are tracked here so the migrator can stay idempotent.
CREATE TABLE IF NOT EXISTS schema_migrations (
    version     TEXT PRIMARY KEY,
    applied_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

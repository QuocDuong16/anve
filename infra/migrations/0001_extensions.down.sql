-- Rollback for 0001_extensions.
--
-- The extensions are deliberately NOT dropped: other migrations and PostGIS
-- objects may depend on them, and dropping an extension invalidates every
-- dependent object. The bookkeeping table is safe to remove.
DROP TABLE IF EXISTS schema_migrations;

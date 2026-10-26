-- Rolling back M3 discards consented location data. The application no
-- longer offers ingestion after this runs, so there is no producer left to
-- recreate the rows; this is the documented consequence of the rollback.
DROP TABLE IF EXISTS location_points;

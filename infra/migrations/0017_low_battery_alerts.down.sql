DROP TRIGGER IF EXISTS retention_changes_purge_low_battery_alerts ON users;
DROP TRIGGER IF EXISTS history_deletions_purge_low_battery_alerts ON location_history_deletions;
DROP FUNCTION IF EXISTS purge_low_battery_alerts_on_history_change();
DROP TABLE IF EXISTS low_battery_notification_targets;
DROP TABLE IF EXISTS low_battery_notification_outbox;
DROP TABLE IF EXISTS low_battery_alert_recipients;
DROP TABLE IF EXISTS low_battery_alerts;
DROP TABLE IF EXISTS low_battery_alert_fences;
ALTER TABLE users
    DROP COLUMN IF EXISTS low_battery_alerts_version,
    DROP COLUMN IF EXISTS low_battery_alerts_enabled;

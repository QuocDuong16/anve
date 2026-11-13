DROP VIEW current_journeys;
-- Owner reminders have no journey-recipient row; old schema cannot represent them.
DELETE FROM journey_notification_outbox WHERE kind='STALE';
ALTER TABLE journey_notification_targets DROP CONSTRAINT fk_journey_target_work;
ALTER TABLE journey_notification_targets DROP CONSTRAINT journey_notification_targets_pkey, DROP COLUMN episode,
    ADD PRIMARY KEY(journey_id,recipient_user_id,kind,device_id);
ALTER TABLE journey_notification_outbox DROP CONSTRAINT journey_notification_outbox_pkey,
    DROP CONSTRAINT fk_journey_notification_journey,
    DROP CONSTRAINT fk_journey_notification_recipient,
    DROP CONSTRAINT journey_notification_outbox_kind_check,
    DROP COLUMN episode,
    ADD PRIMARY KEY(journey_id,recipient_user_id,kind),
    ADD CHECK(kind IN ('ACTIVE','ARRIVED','ENDED','CANCELLED','EXPIRED'));
DELETE FROM journey_notification_outbox o WHERE NOT EXISTS (
    SELECT 1 FROM journey_recipients r WHERE r.journey_id=o.journey_id AND r.user_id=o.recipient_user_id);
DELETE FROM journey_notification_targets t WHERE NOT EXISTS (
    SELECT 1 FROM journey_notification_outbox o WHERE o.journey_id=t.journey_id AND o.recipient_user_id=t.recipient_user_id AND o.kind=t.kind);
ALTER TABLE journey_notification_outbox ADD FOREIGN KEY(journey_id,recipient_user_id)
    REFERENCES journey_recipients(journey_id,user_id) ON DELETE CASCADE;
ALTER TABLE journey_notification_targets ADD FOREIGN KEY(journey_id,recipient_user_id,kind)
    REFERENCES journey_notification_outbox(journey_id,recipient_user_id,kind) ON DELETE CASCADE;
DROP INDEX idx_journeys_automation_due;
ALTER TABLE journeys DROP COLUMN automatic_arrival, DROP COLUMN stale_after_minutes, DROP COLUMN arrival_source,
    DROP COLUMN last_live_update_at, DROP COLUMN stale_since, DROP COLUMN stale_episode, DROP COLUMN evaluated_at,
    DROP COLUMN automation_generation, DROP COLUMN automation_anchor_at;
CREATE VIEW current_journeys AS
SELECT j.*,
    CASE WHEN j.state<>'ACTIVE' THEN j.state
         WHEN j.expires_at<=clock_timestamp() THEN 'EXPIRED'
         WHEN NOT EXISTS (SELECT 1 FROM authorized_journey_recipients r WHERE r.journey_id=j.id) THEN 'CANCELLED'
         ELSE 'ACTIVE' END AS effective_state
FROM journeys j;

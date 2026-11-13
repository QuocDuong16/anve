ALTER TABLE journeys
    ADD COLUMN automatic_arrival BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN stale_after_minutes INTEGER NOT NULL DEFAULT 0 CHECK (stale_after_minutes IN (0,5,10,15,30)),
    ADD COLUMN arrival_source TEXT CHECK (arrival_source IN ('MANUAL','AUTOMATIC')),
    ADD COLUMN last_live_update_at TIMESTAMPTZ,
    ADD COLUMN stale_since TIMESTAMPTZ,
    ADD COLUMN stale_episode INTEGER NOT NULL DEFAULT 0 CHECK (stale_episode>=0),
    ADD COLUMN automation_generation BIGINT,
    ADD COLUMN automation_anchor_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    ADD COLUMN evaluated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp();
UPDATE journeys SET arrival_source='MANUAL' WHERE state='ARRIVED';
CREATE INDEX idx_journeys_automation_due ON journeys(evaluated_at,owner_user_id) WHERE state='ACTIVE';

-- An episode identifies one gap; recovery rearms without resending old work.
DO $$ DECLARE name TEXT; BEGIN
    SELECT conname INTO name FROM pg_constraint WHERE conrelid='journey_notification_targets'::regclass
        AND contype='f' AND confrelid='journey_notification_outbox'::regclass;
    EXECUTE format('ALTER TABLE journey_notification_targets DROP CONSTRAINT %I',name);
END $$;
ALTER TABLE journey_notification_outbox
    DROP CONSTRAINT journey_notification_outbox_journey_id_recipient_user_id_fkey,
    DROP CONSTRAINT journey_notification_outbox_pkey,
    DROP CONSTRAINT journey_notification_outbox_kind_check,
    ADD COLUMN episode INTEGER NOT NULL DEFAULT 0 CHECK (episode>=0),
    ADD PRIMARY KEY(journey_id,recipient_user_id,kind,episode),
    ADD CONSTRAINT fk_journey_notification_journey FOREIGN KEY(journey_id) REFERENCES journeys(id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_journey_notification_recipient FOREIGN KEY(recipient_user_id) REFERENCES users(id) ON DELETE CASCADE,
    ADD CHECK(kind IN ('ACTIVE','ARRIVED','ENDED','CANCELLED','EXPIRED','STALE'));
ALTER TABLE journey_notification_targets
    DROP CONSTRAINT journey_notification_targets_pkey,
    ADD COLUMN episode INTEGER NOT NULL DEFAULT 0 CHECK (episode>=0),
    ADD PRIMARY KEY(journey_id,recipient_user_id,kind,episode,device_id),
    ADD CONSTRAINT fk_journey_target_work FOREIGN KEY(journey_id,recipient_user_id,kind,episode)
        REFERENCES journey_notification_outbox(journey_id,recipient_user_id,kind,episode) ON DELETE CASCADE;

-- Recreate because a view's j.* is expanded when it is originally defined.
DROP VIEW current_journeys;
CREATE VIEW current_journeys AS
SELECT j.*,
    CASE WHEN j.state<>'ACTIVE' THEN j.state
         WHEN j.expires_at<=clock_timestamp() THEN 'EXPIRED'
         WHEN NOT EXISTS (SELECT 1 FROM authorized_journey_recipients r WHERE r.journey_id=j.id) THEN 'CANCELLED'
         ELSE 'ACTIVE' END AS effective_state
FROM journeys j;

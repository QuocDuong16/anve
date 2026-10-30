DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM sos_events) OR EXISTS (SELECT 1 FROM sos_cancelled_requests) OR EXISTS (SELECT 1 FROM sos_retired_requests) THEN
        RAISE EXCEPTION 'cannot remove persisted SOS events';
    END IF;
END $$;
DROP TABLE sos_notification_targets;
DROP TRIGGER place_events_enqueue_notification ON place_events;
DROP FUNCTION enqueue_place_notifications();
DROP TABLE place_notification_targets;
DROP TABLE place_notification_outbox;
DROP TABLE sos_notification_outbox;
DROP TRIGGER sessions_remove_push_token ON sessions;
DROP FUNCTION remove_revoked_push_token();
DROP TABLE device_push_tokens;
DROP TABLE sos_recipients;
DROP TABLE sos_events;
DROP TABLE sos_cancelled_requests;
DROP TABLE sos_retired_requests;

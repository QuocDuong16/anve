DROP TABLE invitation_notification_targets;
DROP TABLE invitation_notification_outbox;
DROP TABLE invitation_notification_budgets;
DROP TABLE invitation_private_hides;
DROP TABLE invitation_creation_fences;
DROP INDEX idx_invites_recipient_hash;
ALTER TABLE invites DROP COLUMN recipient_email_hash;

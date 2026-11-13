ALTER TABLE invites ADD COLUMN recipient_email_hash BYTEA CHECK (octet_length(recipient_email_hash)=32);
CREATE INDEX idx_invites_recipient_hash ON invites(recipient_email_hash) WHERE recipient_email_hash IS NOT NULL;
-- Fences retain no code/email and survive dissolution; a timed-out UUID cannot
-- create another invitation after its original content has been retired.
CREATE TABLE invitation_creation_fences (
    request_id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    circle_id UUID NOT NULL,
    input_hash BYTEA NOT NULL CHECK (octet_length(input_hash)=32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_invitation_creation_budget ON invitation_creation_fences(owner_user_id,created_at);
CREATE TABLE invitation_private_hides (
    invite_id UUID NOT NULL REFERENCES invites(id) ON DELETE CASCADE,
    recipient_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    PRIMARY KEY(invite_id,recipient_user_id)
);
-- The recipient budget changes only internal dispatch eligibility, never the
-- sender response. Digests are pseudonymous personal data, not anonymous data.
CREATE TABLE invitation_notification_budgets (
    email_hash BYTEA NOT NULL CHECK (octet_length(email_hash)=32),
    budget_day DATE NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 1 CHECK (attempts>0),
    PRIMARY KEY(email_hash,budget_day)
);
CREATE TABLE invitation_notification_outbox (
    invite_id UUID PRIMARY KEY REFERENCES invites(id) ON DELETE CASCADE,
    recipient_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    state TEXT NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED','ACCEPTED','SUPPRESSED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts>=0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_invitation_dispatch_due ON invitation_notification_outbox(next_attempt_at) WHERE state='QUEUED';
CREATE TABLE invitation_notification_targets (
    invite_id UUID NOT NULL REFERENCES invitation_notification_outbox(invite_id) ON DELETE CASCADE,
    device_id UUID NOT NULL REFERENCES device_push_tokens(device_id) ON DELETE CASCADE,
    accepted_at TIMESTAMPTZ,
    PRIMARY KEY(invite_id,device_id)
);

-- AnVề — M1: authentication, device identity, privacy foundation.
--
-- Opaque sessions per ADR 0002: the server stores only SHA-256 hashes of the
-- access/refresh tokens and verifies them on every request, so revocation is
-- immediate. Users keep a location-sharing consent switch that every later
-- milestone must respect before ingesting or distributing location data.

CREATE TABLE users (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    -- Google subject identifier; NULL for identities that did not arrive
    -- through Google (the development login, future providers). PostgreSQL
    -- UNIQUE allows multiple NULLs.
    google_sub              TEXT UNIQUE,
    email                   TEXT NOT NULL UNIQUE,
    display_name            TEXT NOT NULL,
    avatar_url              TEXT,
    -- Privacy foundation: location sharing is OFF until the data subject
    -- turns it on. No code path may set this implicitly.
    location_sharing_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE devices (
    id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- Stable app-generated device identifier, so reinstalling or signing in
    -- again on the same physical device reuses the row.
    client_device_id  TEXT NOT NULL,
    name              TEXT NOT NULL DEFAULT '',
    platform          TEXT NOT NULL DEFAULT 'android',
    app_version       TEXT NOT NULL DEFAULT '',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, client_device_id)
);

CREATE INDEX idx_devices_user ON devices(user_id);

CREATE TABLE sessions (
    id                   UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id              UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id            UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    -- SHA-256 of the opaque tokens; never the tokens themselves.
    refresh_token_hash   BYTEA NOT NULL UNIQUE,
    refresh_expires_at   TIMESTAMPTZ NOT NULL,
    access_token_hash    BYTEA NOT NULL UNIQUE,
    access_expires_at    TIMESTAMPTZ NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at           TIMESTAMPTZ,
    revoked_reason       TEXT
);

CREATE INDEX idx_sessions_user ON sessions(user_id);
CREATE INDEX idx_sessions_device ON sessions(device_id);

-- Audit trail for privacy-relevant events (sign-in, consent changes,
-- revocation, deletion...). actor_user_id deliberately has NO foreign key:
-- audit rows must survive account deletion, so the reference is by value.
-- The audit log never contains coordinates — only who did what, to whom,
-- when.
CREATE TABLE audit_log (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_user_id   UUID NOT NULL,
    action          TEXT NOT NULL,
    target_user_id  UUID,
    detail          JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_actor_time ON audit_log(actor_user_id, created_at DESC);
CREATE INDEX idx_audit_action_time ON audit_log(action, created_at DESC);

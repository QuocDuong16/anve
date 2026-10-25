-- AnVề — M2: Family Circle, membership, invites.
--
-- Authorization model (privacy-and-safety.md §3): circle roles govern
-- ADMINISTRATION only (invites, removal, rename). They never grant
-- location access: viewing someone's location always originates from that
-- person's own consent switch. Joining a circle does not enable sharing.

CREATE TABLE circles (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name        TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 80),
    created_by  UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE memberships (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    circle_id   UUID NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role        TEXT NOT NULL DEFAULT 'member' CHECK (role IN ('owner', 'member')),
    joined_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (circle_id, user_id)
);

CREATE INDEX idx_memberships_circle ON memberships(circle_id);
CREATE INDEX idx_memberships_user ON memberships(user_id);

-- MVP: one circle per user. Leaving/removal DELETES the membership row, so
-- a plain unique index is the invariant the whole API relies on; dropping
-- it is a product decision (multi-circle), not a bug fix.
CREATE UNIQUE INDEX one_active_circle_per_user ON memberships(user_id);

CREATE TABLE invites (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    circle_id   UUID NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
    -- Random capability code; uniqueness over the full space of live codes
    -- is enforced by the unique index, making guesses useless.
    code        TEXT NOT NULL UNIQUE CHECK (length(code) BETWEEN 8 AND 32),
    created_by  UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at  TIMESTAMPTZ NOT NULL,
    -- Single use: set atomically by the redeem transaction.
    used_at     TIMESTAMPTZ,
    used_by     UUID REFERENCES users(id) ON DELETE SET NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_invites_circle ON invites(circle_id);

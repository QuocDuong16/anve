CREATE TABLE profile_avatars (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL CHECK (revision > 0),
    operation_id UUID NOT NULL,
    image_id UUID,
    content BYTEA,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((image_id IS NULL) = (content IS NULL)),
    CHECK (content IS NULL OR octet_length(content) BETWEEN 1 AND 131072)
);

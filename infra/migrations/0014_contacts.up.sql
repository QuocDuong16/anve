CREATE TABLE contacts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL CHECK (char_length(btrim(name)) BETWEEN 1 AND 120),
    email TEXT NOT NULL CHECK (char_length(btrim(email)) BETWEEN 3 AND 254),
    phone TEXT NOT NULL DEFAULT '' CHECK (char_length(phone) <= 40),
    message TEXT NOT NULL CHECK (char_length(btrim(message)) BETWEEN 1 AND 2000),
    status TEXT NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW', 'NOTIFIED', 'PARTIAL_NOTIFY', 'READ', 'ARCHIVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_contacts_created_at ON contacts (created_at DESC);

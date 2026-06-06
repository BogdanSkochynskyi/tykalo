CREATE TABLE lists (
    id                    UUID PRIMARY KEY,
    owner_id              UUID         NOT NULL REFERENCES users (id),
    name                  VARCHAR(255) NOT NULL,
    type                  VARCHAR(16)  NOT NULL CHECK (type IN ('CHECKLIST', 'ROUTINE', 'PROJECT', 'INBOX')),
    recurrence_rule       TEXT,
    nudger_default_policy VARCHAR(16)  NOT NULL CHECK (nudger_default_policy IN ('OFF', 'OPT_IN', 'ON_PER_TASK')),
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    archived_at           TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_lists_owner_id ON lists (owner_id);

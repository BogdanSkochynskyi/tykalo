CREATE TABLE tasks (
    id              UUID PRIMARY KEY,
    list_id         UUID        NOT NULL REFERENCES lists (id),
    owner_id        UUID        NOT NULL REFERENCES users (id),
    title           TEXT        NOT NULL,
    description     TEXT,
    due_at          TIMESTAMP WITH TIME ZONE,
    priority        VARCHAR(8)  CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    status          VARCHAR(12) NOT NULL DEFAULT 'TODO' CHECK (status IN ('TODO', 'DONE', 'CANCELLED')),
    recurrence_rule TEXT,
    gcal_event_id   VARCHAR(255),
    tags            TEXT[]      NOT NULL DEFAULT '{}',
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    archived_at     TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_tasks_owner_due_at ON tasks (owner_id, due_at);
CREATE INDEX idx_tasks_list_id ON tasks (list_id);

-- Per-task Nudger assignment (TK-158): which of an owner's Nudgers should be escalated to for a
-- specific task, plus a flag to make a task private (no escalation at all).

-- A task can pin a subset of the owner's Nudgers. Empty (no rows) + nudgers_private = false means the
-- task falls back to the owner's full active set (the TK-156 default). nudger_id cascades on delete so
-- removing a Nudger (a hard delete in NudgerService) does not leave dangling assignments.
CREATE TABLE task_nudgers (
    id        UUID PRIMARY KEY,
    task_id   UUID NOT NULL REFERENCES tasks (id),
    nudger_id UUID NOT NULL REFERENCES nudgers (id) ON DELETE CASCADE,
    UNIQUE (task_id, nudger_id)
);

CREATE INDEX idx_task_nudgers_task_id ON task_nudgers (task_id);
CREATE INDEX idx_task_nudgers_nudger_id ON task_nudgers (nudger_id);

-- When true the task is private: escalation skips it entirely, regardless of any assignment rows. This
-- distinguishes an explicit "off" from a not-yet-chosen task (both have zero task_nudgers rows).
ALTER TABLE tasks
    ADD COLUMN nudgers_private BOOLEAN NOT NULL DEFAULT FALSE;

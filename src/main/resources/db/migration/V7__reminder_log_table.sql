-- Per-task self-reminder ledger for the overdue reminder cron (TK-145). One row per (task, level)
-- actually delivered; the UNIQUE constraint is the backstop that a given reminder level is never
-- sent twice for the same task. level is the reminder tier (1=+2h, 2=+6h, 3=+12h past due_at).
CREATE TABLE reminder_log (
    id       UUID PRIMARY KEY,
    task_id  UUID     NOT NULL REFERENCES tasks (id),
    level    SMALLINT NOT NULL,
    sent_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT reminder_log_task_level_unique UNIQUE (task_id, level)
);

CREATE INDEX idx_reminder_log_task_id ON reminder_log (task_id);

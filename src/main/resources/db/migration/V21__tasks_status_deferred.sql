-- "Save for later" (TK-256) adds a DEFERRED task status: a task moved out of its list into the
-- pending_items bucket (V20). The V3 status CHECK only admitted TODO/DONE/CANCELLED, so widen it to
-- accept DEFERRED. Postgres requires dropping and recreating a named CHECK to change it; the original
-- constraint is anonymous, so this drops it by its generated name and adds a named one in its place.
ALTER TABLE tasks DROP CONSTRAINT tasks_status_check;

ALTER TABLE tasks
    ADD CONSTRAINT tasks_status_check CHECK (status IN ('TODO', 'DONE', 'CANCELLED', 'DEFERRED'));

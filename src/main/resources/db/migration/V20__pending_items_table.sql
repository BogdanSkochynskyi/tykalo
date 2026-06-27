-- "Saved for later" bucket (TK-255): items the user defers out of a list, living independently of the
-- list lifecycle (V19). A pending item outlives its source list and task — original_list_id is SET NULL
-- (not CASCADE) so deleting the list only drops the provenance link, and source_task_id is a plain
-- reference (no FK) since the source task is typically soft-deleted/archived. Foundation for the
-- close-list carry-over (TK-254), "save for later" (TK-256), the pending screen (TK-257) and tag-matched
-- suggestions on new lists (TK-258).
CREATE TABLE pending_items (
    id                 UUID PRIMARY KEY,
    user_id            UUID   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title              TEXT   NOT NULL,
    original_list_id   UUID   REFERENCES lists (id) ON DELETE SET NULL,
    original_list_tags TEXT[] NOT NULL DEFAULT '{}',
    source_task_id     UUID,
    deferred_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    deferred_until     TIMESTAMP WITH TIME ZONE
);

-- "my pending items, newest first" — the pending screen (TK-257).
CREATE INDEX idx_pending_items_user_deferred ON pending_items (user_id, deferred_at DESC);
-- tag-overlap matching via the Postgres && operator when a new list is created (TK-258).
CREATE INDEX idx_pending_items_tags ON pending_items USING GIN (original_list_tags);

-- List lifecycle foundation (TK-251). Adds the columns the lifecycle state machine
-- (ACTIVE -> COMPLETED -> ARCHIVED), tag categorization, and auto-close build on. status uses a
-- CHECK constraint mirroring lists.type (V2) so invalid states are rejected at the DB. closed_at is
-- the new lifecycle timestamp; the legacy archived_at column is retained as the soft-delete authority.
ALTER TABLE lists
    ADD COLUMN status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'COMPLETED', 'ARCHIVED')),
    ADD COLUMN closed_at  TIMESTAMP WITH TIME ZONE,
    ADD COLUMN tags       TEXT[]      NOT NULL DEFAULT '{}',
    ADD COLUMN auto_close BOOLEAN     NOT NULL DEFAULT false;

-- Backfill: pre-existing soft-deleted lists map onto the lifecycle as ARCHIVED, carrying their
-- archived_at over to closed_at. Idempotent in effect (only touches archived rows still at the
-- default ACTIVE status).
UPDATE lists
SET status = 'ARCHIVED', closed_at = archived_at
WHERE archived_at IS NOT NULL;

CREATE INDEX idx_lists_tags         ON lists USING GIN (tags);
CREATE INDEX idx_lists_status_owner ON lists (status, owner_id);

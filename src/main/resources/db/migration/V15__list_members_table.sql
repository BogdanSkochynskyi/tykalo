-- Shared-list membership (TK-191): the foundation for Phase 1.5b. Each row grants one user a role on
-- one list. Until TK-197 backfills OWNER rows for existing lists, lists.owner_id stays the authority;
-- afterwards a list always has exactly one OWNER row here.
CREATE TABLE list_members (
    id        UUID PRIMARY KEY,
    list_id   UUID       NOT NULL REFERENCES lists (id) ON DELETE CASCADE,
    user_id   UUID       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role      VARCHAR(8) NOT NULL CHECK (role IN ('OWNER', 'EDITOR', 'MEMBER')),
    joined_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    UNIQUE (list_id, user_id)
);

-- "all lists where I'm a member" (optionally filtered by role).
CREATE INDEX idx_list_members_user_role ON list_members (user_id, role);
-- "all members of a list" — the UNIQUE(list_id, user_id) index already serves list_id-prefix lookups,
-- but TK-191 keeps a dedicated single-column index for this hot path.
CREATE INDEX idx_list_members_list_id ON list_members (list_id);

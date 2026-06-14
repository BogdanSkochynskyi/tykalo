-- Invite tracking on list_members (TK-193). A membership is now either PENDING (invited, awaiting the
-- invitee's Yes/No) or ACTIVE (accepted) — only ACTIVE rows grant permissions and list visibility, so a
-- pending invitee gets nothing until they accept. invited_by records who sent the invite, so accept /
-- decline can notify them (the inviter may be an EDITOR, not the list owner). Existing rows (and the
-- TK-197 OWNER backfill, which doesn't set status) default to ACTIVE.
ALTER TABLE list_members
    ADD COLUMN status     VARCHAR(8) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('PENDING', 'ACTIVE')),
    ADD COLUMN invited_by UUID REFERENCES users (id) ON DELETE SET NULL;

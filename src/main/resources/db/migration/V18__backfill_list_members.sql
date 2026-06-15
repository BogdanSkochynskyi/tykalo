-- Backfill OWNER memberships for lists created before TK-191 (TK-197). Each existing list gets exactly
-- one OWNER row pointing at its owner_id, so a list always has an explicit OWNER going forward. status
-- is omitted on purpose: it defaults to 'ACTIVE' (V16), which is correct for pre-existing owners (not a
-- pending invite). invited_by stays NULL — a backfilled owner was never invited. Idempotent via the
-- NOT EXISTS guard, so re-running on a later deploy changes nothing. lists.owner_id remains the
-- authority source for services; a separate Phase 2+ ticket may drop it.
INSERT INTO list_members (id, list_id, user_id, role, joined_at)
SELECT gen_random_uuid(), id, owner_id, 'OWNER', created_at
FROM lists
WHERE NOT EXISTS (
    SELECT 1 FROM list_members WHERE list_id = lists.id AND user_id = lists.owner_id
);

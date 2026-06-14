package io.tykalo.list;

/**
 * Lifecycle of a {@link ListMember} row (TK-193). An invite is created {@link #PENDING} and only
 * becomes {@link #ACTIVE} once the invitee accepts; declining deletes the row rather than leaving it
 * around. Only {@code ACTIVE} memberships grant permissions ({@code ListPermissionService}) and list
 * visibility ({@code ListService}), so a pending invitee has no access until they agree.
 */
public enum ListMemberStatus {

    /** Invited, awaiting the invitee's Yes/No. No permissions yet. */
    PENDING,

    /** Accepted (or a direct/backfilled membership). Grants the row's {@link ListMemberRole}. */
    ACTIVE
}

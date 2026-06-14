package io.tykalo.list;

/**
 * A user's role on a shared {@link TaskList}. Drives permission checks in {@code ListPermissionService}
 * (TK-192). A list always has exactly one {@link #OWNER}; ownership is transferred, never simply removed.
 */
public enum ListMemberRole {

    /** Full control: add/toggle items, edit the list, manage members, delete, transfer ownership. */
    OWNER,

    /** Add/toggle items, edit the list, manage members (except the OWNER). Cannot delete the list. */
    EDITOR,

    /** Add and toggle items only. No list editing or member management. */
    MEMBER
}

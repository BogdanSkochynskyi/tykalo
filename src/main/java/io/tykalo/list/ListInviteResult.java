package io.tykalo.list;

import io.tykalo.user.User;

/**
 * Outcome of {@link ListInviteService#inviteByUsername} — a member inviting a {@code @username} to a
 * shared list (TK-193).
 *
 * <ul>
 *   <li>{@link Invited} — the username is registered; a {@link ListMemberStatus#PENDING} membership was
 *       created and the invitee should get the Yes/No prompt.</li>
 *   <li>{@link AlreadyPending} — that user already has an outstanding pending invite to this list.</li>
 *   <li>{@link AlreadyMember} — that user is already an active member.</li>
 *   <li>{@link SelfInvite} — the inviter tried to invite themselves.</li>
 *   <li>{@link NotRegistered} — no user has that username; the caller offers a deep-link instead.</li>
 * </ul>
 */
public sealed interface ListInviteResult {

    record Invited(ListMember member, User invitee, TaskList list) implements ListInviteResult {
    }

    record AlreadyPending(ListMember member, User invitee, TaskList list) implements ListInviteResult {
    }

    record AlreadyMember(User invitee) implements ListInviteResult {
    }

    record SelfInvite() implements ListInviteResult {
    }

    record NotRegistered(String username) implements ListInviteResult {
    }
}

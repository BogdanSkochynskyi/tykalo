package io.tykalo.list;

import io.tykalo.user.User;

/**
 * Outcome of {@link ListInviteService#acceptViaDeepLink} — a user arriving through a list-invite
 * deep-link (TK-193).
 *
 * <ul>
 *   <li>{@link Invited} — a {@link ListMemberStatus#PENDING} membership was created for the clicker;
 *       they should get the Yes/No prompt.</li>
 *   <li>{@link AlreadyPending} — the clicker already has an outstanding pending invite to this list.</li>
 *   <li>{@link AlreadyMember} — the clicker is already an active member of the list.</li>
 *   <li>{@link Expired} — the link's Redis token has expired (or was never issued).</li>
 *   <li>{@link SelfInvite} — the clicker is the user who issued the link.</li>
 *   <li>{@link Unavailable} — the link decoded, but the list or inviter no longer exists.</li>
 * </ul>
 */
public sealed interface DeepLinkInviteResult {

    record Invited(ListMember member, User inviter, TaskList list) implements DeepLinkInviteResult {
    }

    record AlreadyPending(ListMember member, User inviter, TaskList list) implements DeepLinkInviteResult {
    }

    record AlreadyMember(TaskList list) implements DeepLinkInviteResult {
    }

    record Expired() implements DeepLinkInviteResult {
    }

    record SelfInvite() implements DeepLinkInviteResult {
    }

    record Unavailable() implements DeepLinkInviteResult {
    }
}

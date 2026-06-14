package io.tykalo.list;

import io.tykalo.telegram.TelegramBotProperties;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inviting users to a shared list (TK-193), by {@code @username} or share-link, and recording their
 * Yes/No answer. Both entry points create a {@link ListMemberStatus#PENDING} {@link ListMember} row —
 * so nothing grants access until {@link #respond} flips it to ACTIVE — and both are gated by
 * {@link ListPermissionService#requireCanManageMembers} (OWNER/EDITOR).
 *
 * <p>The share-link is a self-contained {@link ListInvite} deep-link payload, but its validity is gated
 * by a Redis token with a {@value #SHARE_LINK_TTL_DAYS}-day TTL: {@link #createShareLink} writes the
 * token and {@link #acceptViaDeepLink} requires it to still be present, so an old link expires rather
 * than working forever. The token's value is irrelevant — only its presence (and TTL) matters.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ListInviteService {

    private static final long SHARE_LINK_TTL_DAYS = 7;
    static final Duration SHARE_LINK_TTL = Duration.ofDays(SHARE_LINK_TTL_DAYS);
    private static final String SHARE_TOKEN_PREFIX = "list_invite:";

    private final ListMemberRepository listMemberRepository;
    private final ListRepository listRepository;
    private final UserRepository userRepository;
    private final ListPermissionService permissionService;
    private final StringRedisTemplate redis;
    private final TelegramBotProperties botProperties;

    /**
     * Invites the user behind {@code rawUsername} to {@code listId} as {@code role}. The acting user
     * must be allowed to manage members. An unregistered username yields {@link ListInviteResult.NotRegistered}
     * so the caller can fall back to a share-link; a registered one gets a PENDING membership (unless
     * they are already a member of any status).
     */
    @Transactional
    public ListInviteResult inviteByUsername(final User actor, final UUID listId, final ListMemberRole role,
            final String rawUsername) {
        permissionService.requireCanManageMembers(actor.getId(), listId);
        final TaskList list = requireList(listId);
        final String username = normalizeUsername(rawUsername);
        final Optional<User> found = userRepository.findByTgUsernameIgnoreCase(username);
        if (found.isEmpty()) {
            return new ListInviteResult.NotRegistered(username);
        }
        final User invitee = found.get();
        if (invitee.getId().equals(actor.getId())) {
            return new ListInviteResult.SelfInvite();
        }
        final Optional<ListMember> existing = listMemberRepository.findByListIdAndUserId(listId, invitee.getId());
        if (existing.isPresent()) {
            final ListMember member = existing.get();
            return member.getStatus() == ListMemberStatus.ACTIVE
                    ? new ListInviteResult.AlreadyMember(invitee)
                    : new ListInviteResult.AlreadyPending(member, invitee, list);
        }
        final ListMember created = listMemberRepository.save(
                ListMember.pendingInvite(listId, invitee.getId(), role, actor.getId()));
        log.info("User {} invited user {} to list {} as {} (PENDING)",
                actor.getId(), invitee.getId(), listId, role);
        return new ListInviteResult.Invited(created, invitee, list);
    }

    /**
     * Issues a share-link for {@code listId} offering {@code role}: a {@code t.me/<bot>?start=...}
     * deep-link whose payload encodes {@code (listId, actor, role)}. Also writes the Redis validity
     * token, so the link works only until {@link #SHARE_LINK_TTL} elapses. The acting user must be
     * allowed to manage members.
     */
    @Transactional(readOnly = true)
    public String createShareLink(final User actor, final UUID listId, final ListMemberRole role) {
        permissionService.requireCanManageMembers(actor.getId(), listId);
        final String payload = ListInvite.payloadFor(listId, Objects.requireNonNull(actor.getId()), role);
        redis.opsForValue().set(shareTokenKey(payload), "1", SHARE_LINK_TTL);
        log.debug("Issued share-link for list {} as {} by user {}", listId, role, actor.getId());
        return "https://t.me/%s?start=%s".formatted(botProperties.getUsername(), payload);
    }

    /**
     * Wires {@code clicker} up as a PENDING member of the list encoded in a share-link, after checking
     * the link's Redis token is still valid. A reused link whose token expired yields
     * {@link DeepLinkInviteResult.Expired}; the encoded list/inviter are re-validated against the DB so
     * a stale link is handled gracefully rather than violating an FK.
     */
    @Transactional
    public DeepLinkInviteResult acceptViaDeepLink(final User clicker, final ListInvite.Payload payload) {
        if (!isShareLinkValid(payload)) {
            log.debug("Expired list-invite deep-link for list {} clicked by user {}",
                    payload.listId(), clicker.getId());
            return new DeepLinkInviteResult.Expired();
        }
        if (payload.invitedBy().equals(clicker.getId())) {
            return new DeepLinkInviteResult.SelfInvite();
        }
        final Optional<TaskList> list = activeList(payload.listId());
        final Optional<User> inviter = userRepository.findById(payload.invitedBy());
        if (list.isEmpty() || inviter.isEmpty()) {
            log.warn("List-invite deep-link decoded to missing list {} or inviter {} — ignoring",
                    payload.listId(), payload.invitedBy());
            return new DeepLinkInviteResult.Unavailable();
        }
        final Optional<ListMember> existing =
                listMemberRepository.findByListIdAndUserId(payload.listId(), clicker.getId());
        if (existing.isPresent()) {
            final ListMember member = existing.get();
            return member.getStatus() == ListMemberStatus.ACTIVE
                    ? new DeepLinkInviteResult.AlreadyMember(list.get())
                    : new DeepLinkInviteResult.AlreadyPending(member, inviter.get(), list.get());
        }
        final ListMember created = listMemberRepository.save(ListMember.pendingInvite(
                payload.listId(), clicker.getId(), payload.role(), payload.invitedBy()));
        log.info("User {} joined list {} via deep-link as {} (PENDING)",
                clicker.getId(), payload.listId(), payload.role());
        return new DeepLinkInviteResult.Invited(created, inviter.get(), list.get());
    }

    /**
     * Records the invitee's Yes/No answer to a pending invite. Accept flips the membership to ACTIVE;
     * decline deletes it (nothing is added). The transition happens exactly once: a replayed accept on
     * an already-ACTIVE row yields {@link InviteResponseResult.AlreadyActive}, and a row that no longer
     * exists (already declined/removed) yields {@link InviteResponseResult.NotFound}.
     */
    @Transactional
    public InviteResponseResult respond(final UUID memberId, final boolean accept) {
        final Optional<ListMember> found = listMemberRepository.findById(memberId);
        if (found.isEmpty()) {
            return new InviteResponseResult.NotFound();
        }
        final ListMember member = found.get();
        if (member.getStatus() == ListMemberStatus.ACTIVE) {
            return new InviteResponseResult.AlreadyActive(member);
        }
        final TaskList list = requireList(member.getListId());
        final User inviter = member.getInvitedBy() == null ? null
                : userRepository.findById(member.getInvitedBy()).orElse(null);
        if (accept) {
            member.setStatus(ListMemberStatus.ACTIVE);
            listMemberRepository.save(member);
            log.info("User {} accepted invite {} to list {}", member.getUserId(), memberId, member.getListId());
            return new InviteResponseResult.Accepted(member, list, inviter);
        }
        listMemberRepository.delete(member);
        log.info("User {} declined invite {} to list {}", member.getUserId(), memberId, member.getListId());
        return new InviteResponseResult.Declined(member, list, inviter);
    }

    private boolean isShareLinkValid(final ListInvite.Payload payload) {
        final String payloadStr = ListInvite.payloadFor(payload.listId(), payload.invitedBy(), payload.role());
        return Boolean.TRUE.equals(redis.hasKey(shareTokenKey(payloadStr)));
    }

    private Optional<TaskList> activeList(final UUID listId) {
        return listRepository.findById(listId).filter(list -> list.getArchivedAt() == null);
    }

    private TaskList requireList(final UUID listId) {
        return listRepository.findById(listId)
                .orElseThrow(() -> new IllegalArgumentException("List not found: " + listId));
    }

    private String shareTokenKey(final String payload) {
        return SHARE_TOKEN_PREFIX + payload;
    }

    private String normalizeUsername(final @Nullable String raw) {
        final String trimmed = raw == null ? "" : raw.strip();
        return trimmed.startsWith("@") ? trimmed.substring(1) : trimmed;
    }
}

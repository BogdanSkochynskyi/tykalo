package io.tykalo.menu;

import io.tykalo.list.ListInviteService;
import io.tykalo.list.ListMember;
import io.tykalo.list.ListMemberRepository;
import io.tykalo.list.ListMemberRole;
import io.tykalo.list.ListMemberService;
import io.tykalo.list.ListMemberStatus;
import io.tykalo.list.ListPermissionService;
import io.tykalo.list.ListRenderer;
import io.tykalo.list.ListService;
import io.tykalo.list.TaskList;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.ConversationStateService;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * Renders the Members screen of a shared list (TK-194, Screen 5) — reached via {@code 👥 Members} from
 * the list view (TK-183). The body lists members (OWNER first, then EDITOR, then MEMBER); the keyboard
 * adapts to the viewer's role: an OWNER/EDITOR sees a {@code 🗑 Remove} button per non-OWNER member plus
 * {@code ➕ Invite} / {@code 🔗 Share link}, an OWNER additionally sees {@code 👑 Transfer ownership},
 * and a MEMBER sees a read-only list. Showing it edits the navigable menu message in place and sets the
 * user's {@link ConversationState} to {@link ConversationState.MembersScreen}.
 *
 * <p>A list created through the menu has no {@link ListMember} OWNER row until someone is invited, so the
 * owner is synthesized from {@code lists.owner_id} when no OWNER row is present. The synthetic owner can
 * never be removed and is never a transfer target, so it needs no member id. The remove/transfer
 * confirmations and the share-link view all edit the same message and return to the members screen via
 * {@link #OPEN_PREFIX}. Mutations themselves live in {@link ListMemberService} and are driven by
 * {@link io.tykalo.menu.handler.MembersCallbackHandler}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MembersService {

    /** (Re)render the members screen; also the cancel target of every sub-screen. Carries the list id. */
    public static final String OPEN_PREFIX = "ms:open:";
    public static final String BACK_PREFIX = "ms:back:";
    public static final String INVITE_PREFIX = "ms:inv:";
    public static final String LINK_PREFIX = "ms:link:";
    public static final String TRANSFER_PREFIX = "ms:xfer:";
    public static final String REMOVE_PREFIX = "ms:rm:";
    public static final String REMOVE_CONFIRM_PREFIX = "ms:rmok:";
    public static final String TRANSFER_TO_PREFIX = "ms:xto:";
    public static final String TRANSFER_CONFIRM_PREFIX = "ms:xok:";

    private static final ListMemberRole SHARE_LINK_ROLE = ListMemberRole.MEMBER;

    private final ListService listService;
    private final ListMemberService listMemberService;
    private final ListMemberRepository listMemberRepository;
    private final ListPermissionService permissionService;
    private final ListInviteService inviteService;
    private final UserRepository userRepository;
    private final ConversationStateService conversationState;
    private final TelegramMessageGateway gateway;

    /**
     * Renders the members screen for {@code listId} into {@code messageId} in place and sets the user's
     * state to {@link ConversationState.MembersScreen}. Returns the list name when shown, or empty if the
     * list is gone or the user has no access to it.
     */
    public Optional<String> open(final User user, final int messageId, final UUID listId) {
        final Optional<TaskList> list = listService.getActiveById(listId);
        if (list.isEmpty() || permissionService.resolveRole(user.getId(), listId).isEmpty()) {
            return Optional.empty();
        }
        conversationState.setState(user.getId(), new ConversationState.MembersScreen(listId));
        final List<Entry> entries = entries(list.get());
        final boolean canManage = permissionService.canManageMembers(user.getId(), listId);
        final boolean canTransfer = permissionService.canTransferOwnership(user.getId(), listId);
        final Map<UUID, User> users = usersFor(entries);
        gateway.editMarkdown(user.getTgChatId(), messageId,
                membersBody(list.get(), entries, users, canManage),
                membersKeyboard(listId, entries, users, canManage, canTransfer));
        log.debug("Showed members screen list={} to user id={}", listId, user.getId());
        return Optional.of(list.get().getName());
    }

    /** Renders the remove-confirmation for a member, in place. Empty if gone or the user may not manage. */
    public Optional<String> showRemoveConfirm(final User user, final int messageId, final UUID memberId) {
        final Optional<ListMember> member = listMemberRepository.findById(memberId);
        if (member.isEmpty() || member.get().getRole() == ListMemberRole.OWNER) {
            return Optional.empty();
        }
        final UUID listId = member.get().getListId();
        if (!permissionService.canManageMembers(user.getId(), listId)) {
            return Optional.empty();
        }
        final Optional<TaskList> list = listService.getActiveById(listId);
        if (list.isEmpty()) {
            return Optional.empty();
        }
        final String name = displayName(userRepository.findById(member.get().getUserId()).orElse(null));
        final String text = ListRenderer.escape(
                "Remove %s from \"%s\"?".formatted(name, list.get().getName()));
        gateway.editMarkdown(user.getTgChatId(), messageId, text, confirmKeyboard(
                "✅ Yes, remove", REMOVE_CONFIRM_PREFIX + memberId, listId));
        return Optional.of(list.get().getName());
    }

    /** Renders the list of members ownership can be transferred to, in place. OWNER only. */
    public Optional<String> showTransferPicker(final User user, final int messageId, final UUID listId) {
        if (!permissionService.canTransferOwnership(user.getId(), listId)) {
            return Optional.empty();
        }
        final Optional<TaskList> list = listService.getActiveById(listId);
        if (list.isEmpty()) {
            return Optional.empty();
        }
        final List<ListMember> candidates = listMemberService.activeMembers(listId).stream()
                .filter(member -> member.getRole() != ListMemberRole.OWNER)
                .toList();
        final Map<UUID, User> users = usersByIds(candidates.stream().map(ListMember::getUserId).toList());
        gateway.editMarkdown(user.getTgChatId(), messageId,
                transferPickerBody(list.get(), candidates), transferPickerKeyboard(listId, candidates, users));
        return Optional.of(list.get().getName());
    }

    /** Renders the transfer-confirmation for a chosen new owner, in place. OWNER only. */
    public Optional<String> showTransferConfirm(final User user, final int messageId, final UUID memberId) {
        final Optional<ListMember> member = listMemberRepository.findById(memberId);
        if (member.isEmpty() || member.get().getStatus() != ListMemberStatus.ACTIVE
                || member.get().getRole() == ListMemberRole.OWNER) {
            return Optional.empty();
        }
        final UUID listId = member.get().getListId();
        if (!permissionService.canTransferOwnership(user.getId(), listId)) {
            return Optional.empty();
        }
        final Optional<TaskList> list = listService.getActiveById(listId);
        if (list.isEmpty()) {
            return Optional.empty();
        }
        final String name = displayName(userRepository.findById(member.get().getUserId()).orElse(null));
        final String text = ListRenderer.escape(("Transfer ownership of \"%s\" to %s?"
                + " You'll become an editor.").formatted(list.get().getName(), name));
        gateway.editMarkdown(user.getTgChatId(), messageId, text, confirmKeyboard(
                "👑 Yes, transfer", TRANSFER_CONFIRM_PREFIX + memberId, listId));
        return Optional.of(list.get().getName());
    }

    /** Renders a share-link view, in place. OWNER/EDITOR only (link creation is permission-gated). */
    public Optional<String> showShareLink(final User user, final int messageId, final UUID listId) {
        if (!permissionService.canManageMembers(user.getId(), listId)) {
            return Optional.empty();
        }
        final Optional<TaskList> list = listService.getActiveById(listId);
        if (list.isEmpty()) {
            return Optional.empty();
        }
        final String link = inviteService.createShareLink(user, listId, SHARE_LINK_ROLE);
        final String text = ListRenderer.escape(("🔗 Share link for \"%s\"\n\nAnyone who opens this link can"
                + " join as a member (valid 7 days):\n%s").formatted(list.get().getName(), link));
        final InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(row(button("⬅️ Back to members", OPEN_PREFIX + listId))).build();
        gateway.editMarkdown(user.getTgChatId(), messageId, text, keyboard);
        return Optional.of(list.get().getName());
    }

    private List<Entry> entries(final TaskList list) {
        final List<Entry> entries = new ArrayList<>();
        boolean hasOwnerRow = false;
        for (final ListMember member : listMemberService.activeMembers(Objects.requireNonNull(list.getId()))) {
            entries.add(new Entry(member.getId(), member.getUserId(), member.getRole()));
            hasOwnerRow |= member.getRole() == ListMemberRole.OWNER;
        }
        if (!hasOwnerRow) {
            entries.add(new Entry(null, list.getOwnerId(), ListMemberRole.OWNER));
        }
        entries.sort(Comparator.comparingInt(entry -> entry.role().ordinal()));
        return entries;
    }

    private String membersBody(final TaskList list, final List<Entry> entries,
            final Map<UUID, User> users, final boolean canManage) {
        final StringBuilder out = new StringBuilder("👥 Members of \"%s\"\n".formatted(list.getName()));
        for (final Entry entry : entries) {
            out.append("\n%s %s — %s".formatted(roleIcon(entry.role()),
                    displayName(users.get(entry.userId())), roleLabel(entry.role())));
        }
        if (!canManage) {
            out.append("\n\nYou have view-only access to this list's members.");
        }
        return ListRenderer.escape(out.toString());
    }

    private InlineKeyboardMarkup membersKeyboard(final UUID listId, final List<Entry> entries,
            final Map<UUID, User> users, final boolean canManage, final boolean canTransfer) {
        final List<InlineKeyboardRow> rows = new ArrayList<>();
        if (canManage) {
            for (final Entry entry : entries) {
                if (entry.role() != ListMemberRole.OWNER && entry.memberId() != null) {
                    rows.add(row(button("🗑 Remove " + displayName(users.get(entry.userId())),
                            REMOVE_PREFIX + entry.memberId())));
                }
            }
            rows.add(row(button("➕ Invite by username", INVITE_PREFIX + listId),
                    button("🔗 Get share link", LINK_PREFIX + listId)));
        }
        if (canTransfer) {
            rows.add(row(button("👑 Transfer ownership", TRANSFER_PREFIX + listId)));
        }
        rows.add(row(button("⬅️ Back to list", BACK_PREFIX + listId)));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private String transferPickerBody(final TaskList list, final List<ListMember> candidates) {
        if (candidates.isEmpty()) {
            return ListRenderer.escape("No other members yet — invite someone before transferring \"%s\"."
                    .formatted(list.getName()));
        }
        return ListRenderer.escape("👑 Transfer ownership of \"%s\" to:".formatted(list.getName()));
    }

    private InlineKeyboardMarkup transferPickerKeyboard(final UUID listId, final List<ListMember> candidates,
            final Map<UUID, User> users) {
        final List<InlineKeyboardRow> rows = new ArrayList<>();
        for (final ListMember candidate : candidates) {
            rows.add(row(button(displayName(users.get(candidate.getUserId())),
                    TRANSFER_TO_PREFIX + candidate.getId())));
        }
        rows.add(row(button("⬅️ Back to members", OPEN_PREFIX + listId)));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private InlineKeyboardMarkup confirmKeyboard(final String yesLabel, final String yesData, final UUID listId) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(row(button(yesLabel, yesData), button("❌ No", OPEN_PREFIX + listId)))
                .build();
    }

    private Map<UUID, User> usersFor(final List<Entry> entries) {
        return usersByIds(entries.stream().map(Entry::userId).toList());
    }

    private Map<UUID, User> usersByIds(final List<UUID> ids) {
        final Map<UUID, User> users = new HashMap<>();
        userRepository.findAllById(ids).forEach(user -> users.put(user.getId(), user));
        return users;
    }

    private String displayName(final @Nullable User user) {
        if (user == null || user.getTgUsername() == null) {
            return "a member";
        }
        return "@" + user.getTgUsername();
    }

    private String roleIcon(final ListMemberRole role) {
        return switch (role) {
            case OWNER -> "👑";
            case EDITOR -> "✏️";
            case MEMBER -> "👤";
        };
    }

    private String roleLabel(final ListMemberRole role) {
        return switch (role) {
            case OWNER -> "Owner";
            case EDITOR -> "Editor";
            case MEMBER -> "Member";
        };
    }

    private InlineKeyboardRow row(final InlineKeyboardButton... buttons) {
        final InlineKeyboardRow row = new InlineKeyboardRow();
        for (final InlineKeyboardButton button : buttons) {
            row.add(button);
        }
        return row;
    }

    private InlineKeyboardButton button(final String text, final String callbackData) {
        return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
    }

    private record Entry(@Nullable UUID memberId, UUID userId, ListMemberRole role) {
    }
}

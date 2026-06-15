package io.tykalo.menu.handler;

import io.tykalo.list.ListMemberService;
import io.tykalo.list.ListRenderer;
import io.tykalo.list.RemoveMemberResult;
import io.tykalo.list.TaskList;
import io.tykalo.list.TransferOwnershipResult;
import io.tykalo.menu.InviteMemberService;
import io.tykalo.menu.ListViewService;
import io.tykalo.menu.MembersService;
import io.tykalo.telegram.CallbackHandler;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;

/**
 * Handles the Members-screen buttons (TK-194), claiming the {@code ms:} {@code callback_data} prefix.
 * Navigation ({@code ms:open}, {@code ms:back}), the invite sub-flow ({@code ms:inv} hands off to the
 * TK-193 {@link InviteMemberService}; its cancel comes back as {@code ms:invx}), the share-link view
 * ({@code ms:link}), the remove flow ({@code ms:rm} → {@code ms:rmok}) and the transfer flow
 * ({@code ms:xfer} → {@code ms:xto} → {@code ms:xok}). Screen rendering lives in {@link MembersService};
 * mutations in {@link ListMemberService}. After a successful remove/transfer the affected user is
 * notified and the members screen is re-rendered in place. Non-{@code ms:} callbacks are left unclaimed.
 */
@Component
@RequiredArgsConstructor
public class MembersCallbackHandler implements CallbackHandler {

    private static final String EXPIRED = "This button has expired.";
    private static final String GONE = "That list is no longer available.";

    private final UserRepository userRepository;
    private final MembersService membersService;
    private final ListMemberService listMemberService;
    private final InviteMemberService inviteMemberService;
    private final ListViewService listViewService;
    private final TelegramMessageGateway gateway;

    @Override
    public Optional<String> handle(final CallbackQuery callback) {
        final String data = callback.getData();
        if (data == null || !data.startsWith("ms:")) {
            return Optional.empty();
        }
        final Long chatId = chatIdOf(callback);
        final Integer messageId = messageIdOf(callback);
        if (chatId == null || messageId == null) {
            return Optional.of(EXPIRED);
        }
        final Optional<User> user = userRepository.findByTgChatId(chatId);
        if (user.isEmpty()) {
            return Optional.of(EXPIRED);
        }
        return route(data, user.get(), messageId);
    }

    private Optional<String> route(final String data, final User user, final int messageId) {
        // Every ms: callback is "ms:<verb>:<uuid>" — the id is whatever follows the last colon.
        final UUID id = parseUuid(data.substring(data.lastIndexOf(':') + 1));
        if (id == null) {
            return Optional.of(EXPIRED);
        }
        if (data.startsWith(MembersService.OPEN_PREFIX) || data.startsWith(InviteMemberService.CANCEL_PREFIX)) {
            return shown(membersService.open(user, messageId, id), "👥 Members").or(() -> Optional.of(GONE));
        }
        if (data.startsWith(MembersService.BACK_PREFIX)) {
            return shown(listViewService.show(user, messageId, id, 0), "📋 List").or(() -> Optional.of(GONE));
        }
        if (data.startsWith(MembersService.INVITE_PREFIX)) {
            return shown(inviteMemberService.start(user, messageId, id), "➕ Invite a member")
                    .or(() -> Optional.of("Only owners and editors can invite members."));
        }
        if (data.startsWith(MembersService.LINK_PREFIX)) {
            return shown(membersService.showShareLink(user, messageId, id), "🔗 Share link")
                    .or(() -> Optional.of("Only owners and editors can share this list."));
        }
        if (data.startsWith(MembersService.TRANSFER_PREFIX)) {
            return shown(membersService.showTransferPicker(user, messageId, id), "👑 Transfer ownership")
                    .or(() -> Optional.of("Only the owner can transfer ownership."));
        }
        if (data.startsWith(MembersService.REMOVE_CONFIRM_PREFIX)) {
            return doRemove(user, messageId, id);
        }
        if (data.startsWith(MembersService.REMOVE_PREFIX)) {
            return shown(membersService.showRemoveConfirm(user, messageId, id), "Confirm removal")
                    .or(() -> Optional.of("That member is no longer on the list."));
        }
        if (data.startsWith(MembersService.TRANSFER_CONFIRM_PREFIX)) {
            return doTransfer(user, messageId, id);
        }
        if (data.startsWith(MembersService.TRANSFER_TO_PREFIX)) {
            return shown(membersService.showTransferConfirm(user, messageId, id), "Confirm transfer")
                    .or(() -> Optional.of("That member can no longer become the owner."));
        }
        return Optional.empty();
    }

    private Optional<String> doRemove(final User user, final int messageId, final UUID memberId) {
        final RemoveMemberResult result = listMemberService.remove(user.getId(), memberId);
        return switch (result) {
            case RemoveMemberResult.Removed removed -> {
                removed.removedUserOpt().ifPresent(removedUser ->
                        notify(removedUser, "🚪 You were removed from the list \"%s\"."
                                .formatted(removed.list().getName())));
                membersService.open(user, messageId, Objects.requireNonNull(removed.list().getId()));
                yield Optional.of("🗑 Member removed.");
            }
            case RemoveMemberResult.CannotRemoveOwner ignored ->
                    Optional.of("The owner can't be removed — transfer ownership first.");
            case RemoveMemberResult.Denied ignored -> Optional.of("Only owners and editors can remove members.");
            case RemoveMemberResult.NotFound ignored -> Optional.of("That member is no longer on the list.");
        };
    }

    private Optional<String> doTransfer(final User user, final int messageId, final UUID memberId) {
        final TransferOwnershipResult result = listMemberService.transferOwnership(user.getId(), memberId);
        return switch (result) {
            case TransferOwnershipResult.Transferred transferred -> {
                notify(transferred.newOwner(), "👑 You are now the owner of the list \"%s\"."
                        .formatted(transferred.list().getName()));
                membersService.open(user, messageId, Objects.requireNonNull(transferred.list().getId()));
                yield Optional.of("👑 Ownership transferred.");
            }
            case TransferOwnershipResult.Denied ignored -> Optional.of("Only the owner can transfer ownership.");
            case TransferOwnershipResult.InvalidTarget ignored ->
                    Optional.of("That member can no longer become the owner.");
        };
    }

    private void notify(final User recipient, final String text) {
        gateway.sendMarkdown(recipient.getTgChatId(), ListRenderer.escape(text), null);
    }

    private Optional<String> shown(final Optional<String> rendered, final String toast) {
        return rendered.isPresent() ? Optional.of(toast) : Optional.empty();
    }

    private @Nullable UUID parseUuid(final String raw) {
        try {
            return UUID.fromString(raw);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    private @Nullable Long chatIdOf(final CallbackQuery callback) {
        final MaybeInaccessibleMessage message = callback.getMessage();
        return message == null ? null : message.getChatId();
    }

    private @Nullable Integer messageIdOf(final CallbackQuery callback) {
        final MaybeInaccessibleMessage message = callback.getMessage();
        return message == null ? null : message.getMessageId();
    }
}

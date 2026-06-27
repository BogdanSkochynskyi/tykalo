package io.tykalo.menu.handler;

import io.tykalo.menu.CloseListService;
import io.tykalo.telegram.CallbackHandler;
import io.tykalo.telegram.CompactUuid;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;

/**
 * Handles the close-list flow buttons (TK-254), claiming the {@code cl:} {@code callback_data} prefix:
 * {@code cl:start} opens the close screen, {@code cl:cancel} backs out to the list view, {@code cl:confirm}
 * closes an all-done list, {@code cl:save}/{@code cl:move}/{@code cl:drop} pick a carry-over for unfinished
 * items ({@code cl:moveto:{src}:{tgt}} executes a move, {@code cl:dropok} confirms a drop), and
 * {@code cl:reopen} takes a COMPLETED list back to ACTIVE. Screen rendering and orchestration live in
 * {@link CloseListService}; mutations in the {@code list} package. The clicking user is re-resolved from
 * the chat and the message id taken from the callback. Non-{@code cl:} callbacks are left unclaimed.
 */
@Component
@RequiredArgsConstructor
public class CloseListCallbackHandler implements CallbackHandler {

    private static final String EXPIRED = "This button has expired.";
    private static final String GONE = "That list is no longer available.";

    private final UserRepository userRepository;
    private final CloseListService closeListService;

    @Override
    public Optional<String> handle(final CallbackQuery callback) {
        final String data = callback.getData();
        if (data == null || !data.startsWith("cl:")) {
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
        // The move executor is the only callback carrying two ids; everything else is "cl:<verb>:<uuid>".
        if (data.startsWith(CloseListService.MOVE_TO_PREFIX)) {
            return moveTo(data.substring(CloseListService.MOVE_TO_PREFIX.length()), user, messageId);
        }
        final UUID listId = parseUuid(data.substring(data.lastIndexOf(':') + 1));
        if (listId == null) {
            return Optional.of(EXPIRED);
        }
        if (data.startsWith(CloseListService.START_PREFIX)) {
            return shown(closeListService.start(user, messageId, listId), "🏁 Close list");
        }
        if (data.startsWith(CloseListService.CANCEL_PREFIX)) {
            return execute(closeListService.cancel(user, messageId, listId));
        }
        if (data.startsWith(CloseListService.CONFIRM_PREFIX)) {
            return execute(closeListService.confirmClose(user, messageId, listId));
        }
        if (data.startsWith(CloseListService.SAVE_PREFIX)) {
            return execute(closeListService.saveForLater(user, messageId, listId));
        }
        if (data.startsWith(CloseListService.MOVE_PREFIX)) {
            return shown(closeListService.showMovePicker(user, messageId, listId), "➡️ Move items");
        }
        if (data.startsWith(CloseListService.DROP_CONFIRM_PREFIX)) {
            return execute(closeListService.drop(user, messageId, listId));
        }
        if (data.startsWith(CloseListService.DROP_PREFIX)) {
            return shown(closeListService.showDropConfirm(user, messageId, listId), "🗑 Drop unfinished?");
        }
        if (data.startsWith(CloseListService.REOPEN_PREFIX)) {
            return execute(closeListService.reopen(user, messageId, listId));
        }
        return Optional.empty();
    }

    private Optional<String> moveTo(final String rest, final User user, final int messageId) {
        final String[] parts = rest.split(":", 2);
        if (parts.length < 2) {
            return Optional.of(EXPIRED);
        }
        final Optional<UUID> source = CompactUuid.decode(parts[0]);
        final Optional<UUID> target = CompactUuid.decode(parts[1]);
        if (source.isEmpty() || target.isEmpty()) {
            return Optional.of(EXPIRED);
        }
        return execute(closeListService.moveTo(user, messageId, source.get(), target.get()));
    }

    /** Render-screen result: present = shown (return {@code toast}); empty = the list is gone. */
    private Optional<String> shown(final Optional<String> rendered, final String toast) {
        return rendered.isPresent() ? Optional.of(toast) : Optional.of(GONE);
    }

    /** Execute result already carries its own toast; an empty result means the list is gone. */
    private Optional<String> execute(final Optional<String> result) {
        return Optional.of(result.orElse(GONE));
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

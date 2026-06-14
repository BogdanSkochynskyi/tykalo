package io.tykalo.menu.handler;

import io.tykalo.list.ListType;
import io.tykalo.menu.CreateListService;
import io.tykalo.telegram.CallbackHandler;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.ConversationStateService;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;

/**
 * Handles the new-list flow buttons (TK-185), claiming the {@code create:} {@code callback_data} prefix:
 * {@code create:type:{TYPE}} advances the type picker to the name prompt via
 * {@link CreateListService#chooseType}, and {@code create:cancel} ends the flow via
 * {@link CreateListService#cancel}. Both edit the screen in place, so the clicking user is re-resolved
 * from the chat and the message id taken from the callback. A {@code create:type:*} tap is guarded by
 * the current state — if the flow already moved on (state is no longer
 * {@link ConversationState.CreatingListType}) a stale type button is a harmless no-op. Non-{@code create:}
 * callbacks are left unclaimed.
 */
@Component
@RequiredArgsConstructor
public class CreateListCallbackHandler implements CallbackHandler {

    private static final String PREFIX = "create:";
    private static final String EXPIRED = "This button has expired.";

    private final UserRepository userRepository;
    private final ConversationStateService conversationState;
    private final CreateListService createListService;

    @Override
    public Optional<String> handle(final CallbackQuery callback) {
        final String data = callback.getData();
        if (data == null || !data.startsWith(PREFIX)) {
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
        if (data.equals(CreateListService.CANCEL)) {
            return createListService.cancel(user.get(), messageId);
        }
        if (data.startsWith(CreateListService.TYPE_PREFIX)) {
            return chooseType(user.get(), messageId, data.substring(CreateListService.TYPE_PREFIX.length()));
        }
        return Optional.empty();
    }

    private Optional<String> chooseType(final User user, final int messageId, final String raw) {
        final ListType type = parseType(raw);
        if (type == null) {
            return Optional.of(EXPIRED);
        }
        if (!(conversationState.getState(user.getId()) instanceof ConversationState.CreatingListType)) {
            return Optional.of("This create session has ended.");
        }
        createListService.chooseType(user, messageId, type);
        return Optional.of("✏️ Name your list");
    }

    private @Nullable ListType parseType(final String raw) {
        try {
            final ListType type = ListType.valueOf(raw);
            // Only the three user-pickable types are valid here; INBOX is auto-provisioned, never created.
            return type == ListType.INBOX ? null : type;
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

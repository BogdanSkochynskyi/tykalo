package io.tykalo.menu.handler;

import io.tykalo.menu.HelpService;
import io.tykalo.menu.HelpTopic;
import io.tykalo.menu.MenuService;
import io.tykalo.telegram.CallbackHandler;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;

/**
 * Handles the help-screen buttons (TK-189), claiming the {@code help:} {@code callback_data} prefix:
 * {@code help:cat:{TOPIC}} drills into a category, {@code help:back} returns to the top-level help, and
 * {@code help:menu} returns to the main menu (TK-181). Every action edits the screen in place, so the
 * clicking user is re-resolved from the chat and the message id taken from the callback.
 * Non-{@code help:} callbacks are left unclaimed.
 */
@Component
@RequiredArgsConstructor
public class HelpCallbackHandler implements CallbackHandler {

    private static final String PREFIX = "help:";
    private static final String EXPIRED = "This button has expired.";

    private final UserRepository userRepository;
    private final HelpService helpService;
    private final MenuService menuService;

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
        final Optional<User> found = userRepository.findByTgChatId(chatId);
        if (found.isEmpty()) {
            return Optional.of(EXPIRED);
        }
        final User user = found.get();
        if (data.startsWith(HelpService.CATEGORY_PREFIX)) {
            final Optional<HelpTopic> topic = HelpTopic.parse(data.substring(HelpService.CATEGORY_PREFIX.length()));
            if (topic.isEmpty()) {
                return Optional.of(EXPIRED);
            }
            helpService.showCategory(user, messageId, topic.get());
            return Optional.of(topic.get().label());
        }
        if (data.equals(HelpService.BACK)) {
            helpService.navigate(user, messageId);
            return Optional.of("❓ Help");
        }
        if (data.equals(HelpService.MENU)) {
            menuService.editToMainMenu(user, messageId);
            return Optional.of("🏠 Main menu");
        }
        return Optional.empty();
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

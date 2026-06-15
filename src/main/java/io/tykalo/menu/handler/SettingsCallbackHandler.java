package io.tykalo.menu.handler;

import io.tykalo.menu.MenuService;
import io.tykalo.menu.SettingsService;
import io.tykalo.telegram.CallbackHandler;
import io.tykalo.user.ListChangeNotificationPreference;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;

/**
 * Handles the settings-screen buttons (TK-196), claiming the {@code set:} {@code callback_data} prefix:
 * {@code set:notif:{PREF}} persists a notification preference and re-renders the screen in place, and
 * {@code set:menu} returns to the main menu (TK-181). Every action edits the screen in place, so the
 * clicking user is re-resolved from the chat and the message id taken from the callback. An unknown
 * preference token (a stale build) is treated as an expired button. Non-{@code set:} callbacks are left
 * unclaimed.
 */
@Component
@RequiredArgsConstructor
public class SettingsCallbackHandler implements CallbackHandler {

    private static final String PREFIX = "set:";
    private static final String EXPIRED = "This button has expired.";

    private final UserRepository userRepository;
    private final SettingsService settingsService;
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
        if (data.startsWith(SettingsService.NOTIF_PREFIX)) {
            return select(user, messageId, data.substring(SettingsService.NOTIF_PREFIX.length()));
        }
        if (data.equals(SettingsService.MENU)) {
            menuService.editToMainMenu(user, messageId);
            return Optional.of("🏠 Main menu");
        }
        return Optional.empty();
    }

    private Optional<String> select(final User user, final int messageId, final String token) {
        final Optional<ListChangeNotificationPreference> preference = parse(token);
        if (preference.isEmpty()) {
            return Optional.of(EXPIRED);
        }
        settingsService.select(user, messageId, preference.get());
        return Optional.of("✅ Saved");
    }

    private Optional<ListChangeNotificationPreference> parse(final String token) {
        try {
            return Optional.of(ListChangeNotificationPreference.valueOf(token));
        } catch (final IllegalArgumentException e) {
            return Optional.empty();
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

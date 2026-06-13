package io.tykalo.menu.handler;

import io.tykalo.menu.MenuService;
import io.tykalo.menu.MyListsService;
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
 * Handles the My Lists screen buttons (TK-182), claiming the {@code lists:} {@code callback_data}
 * prefix: {@code lists:page:{n}} pages the screen, {@code lists:menu} returns to the main menu, and
 * {@code lists:open:{id}} / {@code lists:new} are placeholders until the list view (TK-183) and the
 * create flow (TK-185) land. Paging and back-navigation edit the screen in place, so the clicking user
 * is re-resolved from the chat and the message id taken from the callback. Non-{@code lists:} callbacks
 * are left unclaimed.
 */
@Component
@RequiredArgsConstructor
public class MyListsCallbackHandler implements CallbackHandler {

    private static final String PREFIX = "lists:";

    private final UserRepository userRepository;
    private final MyListsService myListsService;
    private final MenuService menuService;

    @Override
    public Optional<String> handle(final CallbackQuery callback) {
        final String data = callback.getData();
        if (data == null || !data.startsWith(PREFIX)) {
            return Optional.empty();
        }
        if (data.equals(MyListsService.NEW)) {
            return Optional.of("➕ Use /list create <name> [type] for now.");
        }
        if (data.startsWith(MyListsService.OPEN_PREFIX)) {
            return Optional.of("📂 The list view is coming soon (TK-183).");
        }
        // Paging and back-navigation edit the screen in place, so they need the user and message id.
        final Long chatId = chatIdOf(callback);
        final Integer messageId = messageIdOf(callback);
        if (chatId == null || messageId == null) {
            return Optional.of("This button has expired.");
        }
        final Optional<User> user = userRepository.findByTgChatId(chatId);
        if (user.isEmpty()) {
            return Optional.of("This button has expired.");
        }
        if (data.equals(MyListsService.BACK)) {
            menuService.editToMainMenu(user.get(), messageId);
            return Optional.of("🏠 Main menu");
        }
        if (data.startsWith(MyListsService.PAGE_PREFIX)) {
            final Optional<Integer> page = parsePage(data.substring(MyListsService.PAGE_PREFIX.length()));
            if (page.isEmpty()) {
                return Optional.of("This button has expired.");
            }
            myListsService.navigate(user.get(), messageId, page.get());
            return Optional.of("Page " + (page.get() + 1));
        }
        return Optional.empty();
    }

    private Optional<Integer> parsePage(final String raw) {
        try {
            return Optional.of(Math.max(0, Integer.parseInt(raw)));
        } catch (final NumberFormatException e) {
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

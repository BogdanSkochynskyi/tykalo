package io.tykalo.menu.handler;

import io.tykalo.menu.MenuService;
import io.tykalo.menu.MyListsService;
import io.tykalo.telegram.CallbackHandler;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;

/**
 * Handles the main-menu buttons (TK-181), claiming the {@code menu:} {@code callback_data} prefix.
 * {@code menu:my_lists} transitions to the My Lists screen (TK-182) by editing the menu message in
 * place; the remaining options' screens land in later tickets (Create → TK-185, Settings → TK-186;
 * Shared → TK-191, Stats → TBD), so for now they answer with a short placeholder toast pointing at
 * the equivalent command, and {@code menu:help} sends the user to {@code /help}. As each screen is
 * built, its case here becomes a real transition. Callbacks that are not a {@code menu:} action are
 * left unclaimed.
 */
@Component
@RequiredArgsConstructor
public class MenuCallbackHandler implements CallbackHandler {

    private final UserRepository userRepository;
    private final MyListsService myListsService;

    @Override
    public Optional<String> handle(final CallbackQuery callback) {
        final String data = callback.getData();
        if (data == null || !data.startsWith("menu:")) {
            return Optional.empty();
        }
        if (data.equals(MenuService.MY_LISTS)) {
            return openMyLists(callback);
        }
        return switch (data) {
            case MenuService.SHARED -> Optional.of("👥 Shared lists are coming soon.");
            case MenuService.CREATE -> Optional.of("➕ Use /list create <name> [type] for now.");
            case MenuService.STATS -> Optional.of("📊 Stats are coming soon.");
            case MenuService.SETTINGS -> Optional.of("⚙️ Use /tz, /quiet and /morning for now.");
            case MenuService.HELP -> Optional.of("❓ Send /help to see everything I can do.");
            default -> Optional.empty();
        };
    }

    private Optional<String> openMyLists(final CallbackQuery callback) {
        final MaybeInaccessibleMessage message = callback.getMessage();
        final Long chatId = message == null ? null : message.getChatId();
        final Integer messageId = message == null ? null : message.getMessageId();
        if (chatId == null || messageId == null) {
            return Optional.of("This button has expired.");
        }
        final Optional<User> user = userRepository.findByTgChatId(chatId);
        if (user.isEmpty()) {
            return Optional.of("This button has expired.");
        }
        myListsService.navigate(user.get(), messageId, 0);
        return Optional.of("📋 My Lists");
    }
}

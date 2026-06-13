package io.tykalo.menu.handler;

import io.tykalo.menu.MenuService;
import io.tykalo.telegram.CallbackHandler;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

/**
 * Handles the main-menu buttons (TK-181), claiming the {@code menu:} {@code callback_data} prefix.
 * Each option's screen lands in a later ticket (My Lists → TK-182, Create → TK-185, Settings →
 * TK-186; Shared → TK-191, Stats → TBD), so for now the buttons answer with a short placeholder toast
 * pointing at the equivalent command. {@code menu:help} sends the user to {@code /help}. As each screen
 * is built, its case here is replaced by a real transition (resolving the user and editing the menu
 * message in place). Callbacks that are not a {@code menu:} action are left unclaimed.
 */
@Component
public class MenuCallbackHandler implements CallbackHandler {

    @Override
    public Optional<String> handle(final CallbackQuery callback) {
        final String data = callback.getData();
        if (data == null || !data.startsWith("menu:")) {
            return Optional.empty();
        }
        return switch (data) {
            case MenuService.MY_LISTS -> Optional.of("📋 My Lists is coming soon — use /lists for now.");
            case MenuService.SHARED -> Optional.of("👥 Shared lists are coming soon.");
            case MenuService.CREATE -> Optional.of("➕ Use /list create <name> [type] for now.");
            case MenuService.STATS -> Optional.of("📊 Stats are coming soon.");
            case MenuService.SETTINGS -> Optional.of("⚙️ Use /tz, /quiet and /morning for now.");
            case MenuService.HELP -> Optional.of("❓ Send /help to see everything I can do.");
            default -> Optional.empty();
        };
    }
}

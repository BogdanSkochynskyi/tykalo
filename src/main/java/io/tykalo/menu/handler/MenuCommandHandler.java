package io.tykalo.menu.handler;

import io.tykalo.menu.MenuService;
import io.tykalo.telegram.TelegramCommand;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * The {@code /menu} command: opens the main menu (TK-181). The menu is an inline-keyboard message, so
 * it goes out through {@link MenuService} (the gateway) rather than the plain-string reply path —
 * hence the handler stays silent ({@code null}).
 */
@Component
@RequiredArgsConstructor
public class MenuCommandHandler {

    private final UserService userService;
    private final MenuService menuService;

    @TelegramCommand("/menu")
    public @Nullable String menu(final Update update) {
        final User user = userService.findOrCreate(update);
        menuService.showMainMenu(user);
        return null;
    }
}

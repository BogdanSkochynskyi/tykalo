package io.tykalo.user.handler;

import io.tykalo.menu.HelpService;
import io.tykalo.telegram.TelegramCommand;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * The {@code /help} command (TK-189): opens the navigable help screen, replacing the old static text
 * catalogue (TK-171) with inline category drilldown and Back navigation. The screen is an inline-keyboard
 * message sent via {@link HelpService} (the gateway), so the handler stays silent ({@code null}).
 */
@Component
@RequiredArgsConstructor
public class HelpCommandHandler {

    private final UserService userService;
    private final HelpService helpService;

    @TelegramCommand("/help")
    public @Nullable String help(final Update update) {
        final User user = userService.findOrCreate(update);
        helpService.open(user);
        return null;
    }
}

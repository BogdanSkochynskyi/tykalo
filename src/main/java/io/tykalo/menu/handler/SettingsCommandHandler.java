package io.tykalo.menu.handler;

import io.tykalo.menu.SettingsService;
import io.tykalo.telegram.TelegramCommand;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * The {@code /settings} command: opens the settings screen (TK-196). The screen is an inline-keyboard
 * message sent through {@link SettingsService} (the gateway), so this handler stays silent ({@code null}).
 */
@Component
@RequiredArgsConstructor
public class SettingsCommandHandler {

    private final UserService userService;
    private final SettingsService settingsService;

    @TelegramCommand("/settings")
    public @Nullable String settings(final Update update) {
        final User user = userService.findOrCreate(update);
        settingsService.open(user);
        return null;
    }
}

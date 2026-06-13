package io.tykalo.menu.handler;

import io.tykalo.menu.MyListsService;
import io.tykalo.telegram.TelegramCommand;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * The {@code /lists} command (TK-182): opens the navigable My Lists screen, replacing the old plain
 * text listing. The screen is an inline-keyboard message sent via {@link MyListsService} (the
 * gateway), so the handler stays silent ({@code null}).
 */
@Component
@RequiredArgsConstructor
public class MyListsCommandHandler {

    private final UserService userService;
    private final MyListsService myListsService;

    @TelegramCommand("/lists")
    public @Nullable String lists(final Update update) {
        final User user = userService.findOrCreate(update);
        myListsService.open(user);
        return null;
    }
}

package io.tykalo.user.handler;

import io.tykalo.telegram.TelegramCommand;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Handles {@code /start}: registers the user on first contact and greets them.
 */
@Component
@RequiredArgsConstructor
public class StartCommandHandler {

    private final UserService userService;

    @TelegramCommand("/start")
    public String start(final Update update) {
        final User user = userService.findOrCreate(update);
        return greeting(user);
    }

    private String greeting(final User user) {
        final String name = user.getTgUsername() == null ? "there" : "@" + user.getTgUsername();
        return """
                👋 Hi, %s! Welcome to Tykalo — your personal task & list bot.

                I help you capture tasks and, when you ask, nudge you (and your trusted contacts) \
                until they get done.

                Your timezone is set to %s — change it any time with /tz.
                Send /help to see everything I can do.""".formatted(name, user.getTimezone());
    }
}

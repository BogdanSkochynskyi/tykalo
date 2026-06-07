package io.tykalo.user.handler;

import io.tykalo.telegram.TelegramCommand;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * The {@code /tz} command: shows or overrides the caller's timezone.
 *
 * <p>{@code /tz} with no argument reports the current zone (UTC when unset). {@code /tz <IANA>}
 * validates the id via {@link ZoneId#of(String)} — which throws {@link DateTimeException} for both
 * malformed input and unknown regions — and, on success, persists it. Times are stored in UTC and
 * only ever displayed in this zone.
 */
@Component
@RequiredArgsConstructor
public class TzCommandHandler {

    private final UserService userService;

    @TelegramCommand("/tz")
    public String tz(final Update update) {
        final User user = userService.findOrCreate(update);
        final String arg = argsOf(update);
        if (arg.isBlank()) {
            return showCurrent(user);
        }
        final ZoneId zone;
        try {
            zone = ZoneId.of(arg);
        } catch (final DateTimeException e) {
            return "⚠️ \"%s\" isn't a valid IANA timezone. Try e.g. Europe/Kyiv or Europe/Warsaw."
                    .formatted(arg);
        }
        userService.updateTimezone(user, zone);
        return "✅ Timezone set to %s.".formatted(zone);
    }

    private String showCurrent(final User user) {
        final ZoneId zone = Optional.ofNullable(user.getTimezone()).orElse(ZoneId.of("UTC"));
        return "🕒 Your timezone is %s.%nChange it with /tz <IANA>, e.g. /tz Europe/Warsaw."
                .formatted(zone);
    }

    private String argsOf(final Update update) {
        final Message message = update.getMessage();
        final String text = message == null ? null : message.getText();
        if (text == null) {
            return "";
        }
        final String[] parts = text.strip().split("\\s+", 2);
        return parts.length > 1 ? parts[1].strip() : "";
    }
}

package io.tykalo.user.handler;

import io.tykalo.telegram.TelegramCommand;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * The {@code /quiet} command: shows, sets, or disables the caller's quiet-hours window — the period
 * during which the bot won't message them.
 *
 * <p>{@code /quiet} with no argument reports the current window (or that it's off). {@code /quiet off}
 * clears it. {@code /quiet HH:MM-HH:MM} (e.g. {@code 22:00-07:00}) sets it; the window may cross
 * midnight. Bounds are parsed leniently ({@code 9:00} and {@code 09:00} both work) and an empty
 * window (equal bounds) is rejected. The actual quiet check lives in
 * {@link io.tykalo.user.QuietHoursService}.
 */
@Component
@RequiredArgsConstructor
public class QuietCommandHandler {

    private static final DateTimeFormatter PARSE = DateTimeFormatter.ofPattern("H:mm");
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("HH:mm");
    private static final String USAGE =
            "⚠️ Use HH:MM-HH:MM, e.g. /quiet 22:00-07:00, or /quiet off to disable.";

    private final UserService userService;

    @TelegramCommand("/quiet")
    public String quiet(final Update update) {
        final User user = userService.findOrCreate(update);
        final String arg = argsOf(update);
        if (arg.isBlank()) {
            return showCurrent(user);
        }
        if (arg.equalsIgnoreCase("off")) {
            userService.disableQuietHours(user);
            return "✅ Quiet hours are off.";
        }
        final String[] parts = arg.split("\\s*[-–—]\\s*", 2);
        if (parts.length != 2) {
            return USAGE;
        }
        final LocalTime start;
        final LocalTime end;
        try {
            start = LocalTime.parse(parts[0].strip(), PARSE);
            end = LocalTime.parse(parts[1].strip(), PARSE);
        } catch (final DateTimeParseException e) {
            return USAGE;
        }
        if (start.equals(end)) {
            return "⚠️ Start and end can't be the same time. Try e.g. /quiet 22:00-07:00.";
        }
        userService.updateQuietHours(user, start, end);
        return "✅ Quiet hours set to %s–%s.".formatted(start.format(DISPLAY), end.format(DISPLAY));
    }

    private String showCurrent(final User user) {
        final LocalTime start = user.getQuietHoursStart();
        final LocalTime end = user.getQuietHoursEnd();
        if (start == null || end == null) {
            return "🔕 Quiet hours are off.%nSet them with /quiet 22:00-07:00.".formatted();
        }
        return "🔕 Quiet hours: %s–%s.%nChange with /quiet 22:00-07:00 or turn off with /quiet off."
                .formatted(start.format(DISPLAY), end.format(DISPLAY));
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

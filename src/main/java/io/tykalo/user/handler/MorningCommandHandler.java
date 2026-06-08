package io.tykalo.user.handler;

import io.tykalo.telegram.TelegramCommand;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * The {@code /morning} command: shows, sets, or disables the caller's morning-digest hour — the local
 * hour at which the bot sends that day's project tasks.
 *
 * <p>{@code /morning} with no argument reports the current hour (or that it's off). {@code /morning off}
 * disables the digest. {@code /morning 8} or {@code /morning 8:00} sets the hour. Because the digest
 * cron fires on the hour, a non-zero minute is rejected rather than silently dropped. The actual send
 * lives in {@link io.tykalo.scheduling.MorningDigestService}.
 */
@Component
@RequiredArgsConstructor
public class MorningCommandHandler {

    private static final Pattern HOUR = Pattern.compile("^(\\d{1,2})(?::(\\d{2}))?$");
    private static final String USAGE =
            "⚠️ Use a whole hour, e.g. /morning 8 or /morning 8:00, or /morning off to disable.";

    private final UserService userService;

    @TelegramCommand("/morning")
    public String morning(final Update update) {
        final User user = userService.findOrCreate(update);
        final String arg = argsOf(update);
        if (arg.isBlank()) {
            return showCurrent(user);
        }
        if (arg.equalsIgnoreCase("off")) {
            userService.disableDigest(user);
            return "✅ Morning digest is off.";
        }
        final Matcher matcher = HOUR.matcher(arg);
        if (!matcher.matches()) {
            return USAGE;
        }
        if (matcher.group(2) != null && !matcher.group(2).equals("00")) {
            return "⚠️ The digest fires on the hour, so minutes must be 00 — try e.g. /morning 8:00.";
        }
        final int hour = Integer.parseInt(matcher.group(1));
        if (hour > 23) {
            return "⚠️ The hour must be between 0 and 23.";
        }
        userService.updateDigestHour(user, hour);
        return "✅ Morning digest set to %02d:00.".formatted(hour);
    }

    private String showCurrent(final User user) {
        final Integer hour = user.getDigestHour();
        if (hour == null) {
            return "🌙 Morning digest is off.%nTurn it on with /morning 8:00.".formatted();
        }
        return "☀️ Morning digest at %02d:00.%nChange it with /morning 9:00 or turn off with /morning off."
                .formatted(hour);
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

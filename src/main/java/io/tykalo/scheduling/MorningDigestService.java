package io.tykalo.scheduling;

import io.tykalo.list.ListRenderer;
import io.tykalo.list.Task;
import io.tykalo.list.TaskService;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.user.QuietHoursService;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

/**
 * Composes and sends the morning digest. Driven hourly by {@link MorningDigestJob}: for the given
 * instant it walks every digest-enabled user and sends them their day's {@code PROJECT} tasks when
 * <em>their</em> local digest hour is striking now. Splitting this out of the job keeps the logic
 * unit-testable without Quartz/ShedLock — the job only supplies {@code Instant.now()}.
 *
 * <p>A user is skipped when it isn't their digest hour, they are inside quiet hours, or they have no
 * project tasks due today (an empty digest is noise). Each send is isolated: a failure delivering to
 * one user is logged and the sweep continues for the rest. The digest reuses the list's
 * {@code task:done:{id}} inline buttons (handled by {@code TaskCallbackHandler}), so taps work
 * without any new callback wiring.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MorningDigestService {

    private final UserRepository userRepository;
    private final TaskService taskService;
    private final QuietHoursService quietHoursService;
    private final MorningDigestRenderer renderer;
    private final ListRenderer listRenderer;
    private final TelegramMessageGateway gateway;

    /** Sends the digest to every user whose local digest hour equals the hour of {@code now}. */
    public void sendDigests(final Instant now) {
        int sent = 0;
        for (final User user : userRepository.findByDigestHourIsNotNull()) {
            if (sendIfDue(user, now)) {
                sent++;
            }
        }
        log.info("Morning digest sweep at {} sent {} digest(s)", now, sent);
    }

    private boolean sendIfDue(final User user, final Instant now) {
        final ZoneId zone = zoneOf(user);
        if (now.atZone(zone).getHour() != Objects.requireNonNull(user.getDigestHour())) {
            return false;
        }
        if (quietHoursService.isQuiet(user, now)) {
            log.debug("Skipping digest for user id={} — within quiet hours", user.getId());
            return false;
        }
        final List<Task> tasks = taskService.findProjectTasksDueToday(Objects.requireNonNull(user.getId()), zone);
        if (tasks.isEmpty()) {
            return false;
        }
        return send(user, tasks, zone);
    }

    private boolean send(final User user, final List<Task> tasks, final ZoneId zone) {
        try {
            final String body = renderer.render(tasks, zone);
            final InlineKeyboardMarkup keyboard = listRenderer.keyboard(tasks);
            gateway.sendMarkdown(user.getTgChatId(), body, keyboard);
            log.info("Sent morning digest to user id={} with {} task(s)", user.getId(), tasks.size());
            return true;
        } catch (final RuntimeException e) {
            log.warn("Failed to send morning digest to user id={}", user.getId(), e);
            return false;
        }
    }

    private ZoneId zoneOf(final User user) {
        return user.getTimezone() == null ? ZoneOffset.UTC : user.getTimezone();
    }
}

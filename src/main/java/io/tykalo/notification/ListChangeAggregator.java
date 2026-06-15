package io.tykalo.notification;

import io.tykalo.list.ListActivityEvent;
import io.tykalo.list.ListMember;
import io.tykalo.list.ListMemberRole;
import io.tykalo.list.ListMemberService;
import io.tykalo.list.ListService;
import io.tykalo.list.TaskList;
import io.tykalo.notification.NotificationBuffer.BufferedChange;
import io.tykalo.notification.NotificationBuffer.ListGroup;
import io.tykalo.notification.NotificationBuffer.WindowFlush;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.user.ListChangeNotificationPreference;
import io.tykalo.user.QuietHoursService;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Routes attributable shared-list changes ({@link ListActivityEvent}) into per-recipient notification
 * buffers and, when a buffer's window closes, sends the rollup (TK-196). It owns the four delivery
 * modes:
 *
 * <ul>
 *   <li><b>OFF</b> — nothing (only the live message updates).</li>
 *   <li><b>INSTANT</b> — buffered with a 30-second window so a burst of edits is one message.</li>
 *   <li><b>BATCHED</b> (default) — buffered with a 10-minute window per (recipient, list).</li>
 *   <li><b>DAILY_DIGEST</b> — buffered per recipient and flushed once a day at their morning hour.</li>
 * </ul>
 *
 * <p>Two invariants hold for every mode: the user who made the change is never notified, and nothing is
 * sent while a recipient is in quiet hours ({@link QuietHoursService}). The recipient set is the list's
 * active members plus the synthesized owner (a menu-created list has no OWNER member row), minus the
 * actor — so a private single-user list produces no notifications at all.
 *
 * <p>Recording runs {@code AFTER_COMMIT} on the event so a rolled-back change is never announced; the
 * flush methods are driven by {@code scheduling} jobs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ListChangeAggregator {

    static final Duration INSTANT_WINDOW = Duration.ofSeconds(30);
    static final Duration BATCHED_WINDOW = Duration.ofMinutes(10);

    private final NotificationBuffer buffer;
    private final NotificationMessageFormatter formatter;
    private final ListService listService;
    private final ListMemberService listMemberService;
    private final UserRepository userRepository;
    private final QuietHoursService quietHoursService;
    private final TelegramMessageGateway gateway;

    /** Buffers a committed list change for every eligible recipient according to their preference. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onListActivity(final ListActivityEvent event) {
        record(event, Instant.now());
    }

    void record(final ListActivityEvent event, final Instant now) {
        final Optional<TaskList> list = listService.getActiveById(event.listId());
        if (list.isEmpty()) {
            return;
        }
        final Set<UUID> recipients = recipients(list.get(), event.actorId());
        if (recipients.isEmpty()) {
            return;
        }
        for (final User recipient : userRepository.findAllById(recipients)) {
            route(recipient, event, now);
        }
    }

    private void route(final User recipient, final ListActivityEvent event, final Instant now) {
        final UUID recipientId = recipient.getId();
        switch (recipient.getListChangeNotifications()) {
            case OFF -> { }
            case INSTANT -> buffer.addToWindow(recipientId, event.listId(), ListChangeNotificationPreference.INSTANT,
                    event.actorId(), event.kind(), event.count(), now.plus(INSTANT_WINDOW));
            case BATCHED -> buffer.addToWindow(recipientId, event.listId(), ListChangeNotificationPreference.BATCHED,
                    event.actorId(), event.kind(), event.count(), now.plus(BATCHED_WINDOW));
            case DAILY_DIGEST -> buffer.addToDaily(recipientId, event.listId(),
                    event.actorId(), event.kind(), event.count());
        }
    }

    /** Sends every windowed (INSTANT/BATCHED) bucket whose window has closed by {@code now}. */
    public void flushDueWindows(final Instant now) {
        for (final WindowFlush flush : buffer.dueWindows(now)) {
            flushWindow(flush, now);
        }
    }

    private void flushWindow(final WindowFlush flush, final Instant now) {
        final Optional<User> recipient = userRepository.findById(flush.recipientId());
        if (recipient.isEmpty()) {
            buffer.removeWindow(flush.recipientId(), flush.listId(), flush.mode());
            return;
        }
        if (quietHoursService.isQuiet(recipient.get(), now)) {
            buffer.rescheduleWindow(flush.recipientId(), flush.listId(), flush.mode(),
                    quietHoursService.nextActiveAt(recipient.get(), now));
            return;
        }
        final Optional<TaskList> list = listService.getActiveById(flush.listId());
        if (list.isEmpty()) {
            buffer.removeWindow(flush.recipientId(), flush.listId(), flush.mode());
            return;
        }
        final String message = formatter.windowMessage(flush.mode(), list.get().getName(),
                flush.changes(), actorNames(flush.changes()));
        gateway.sendMarkdown(recipient.get().getTgChatId(), message, null);
        buffer.removeWindow(flush.recipientId(), flush.listId(), flush.mode());
        log.debug("Flushed {} notification to user id={} for list {}", flush.mode(),
                flush.recipientId(), flush.listId());
    }

    /** Sends the daily digest to every DAILY_DIGEST user whose local morning hour equals {@code now}. */
    public void flushDailyDigests(final Instant now) {
        for (final User user : userRepository.findByListChangeNotifications(
                ListChangeNotificationPreference.DAILY_DIGEST)) {
            flushDailyDigest(user, now);
        }
    }

    private void flushDailyDigest(final User user, final Instant now) {
        if (user.getDigestHour() == null || now.atZone(zoneOf(user)).getHour() != user.getDigestHour()) {
            return;
        }
        final List<ListGroup> groups = buffer.dailyFor(user.getId());
        if (groups.isEmpty()) {
            return;
        }
        if (quietHoursService.isQuiet(user, now)) {
            return;
        }
        final String message = formatter.dailyMessage(groups, listNames(groups), actorNames(allChanges(groups)));
        gateway.sendMarkdown(user.getTgChatId(), message, null);
        buffer.removeDaily(user.getId());
        log.debug("Flushed daily list digest to user id={} covering {} list(s)", user.getId(), groups.size());
    }

    private Set<UUID> recipients(final TaskList list, final UUID actorId) {
        final Set<UUID> recipients = new HashSet<>();
        boolean hasOwnerRow = false;
        for (final ListMember member : listMemberService.activeMembers(list.getId())) {
            recipients.add(member.getUserId());
            hasOwnerRow |= member.getRole() == ListMemberRole.OWNER;
        }
        if (!hasOwnerRow) {
            recipients.add(list.getOwnerId());
        }
        recipients.remove(actorId);
        return recipients;
    }

    private Map<UUID, String> actorNames(final List<BufferedChange> changes) {
        final Set<UUID> actorIds = new HashSet<>();
        changes.forEach(change -> actorIds.add(change.actorId()));
        final Map<UUID, String> names = new HashMap<>();
        userRepository.findAllById(actorIds).forEach(user -> names.put(user.getId(), displayName(user)));
        return names;
    }

    private Map<UUID, String> listNames(final List<ListGroup> groups) {
        final Map<UUID, String> names = new HashMap<>();
        for (final ListGroup group : groups) {
            listService.getActiveById(group.listId()).ifPresent(list -> names.put(group.listId(), list.getName()));
        }
        return names;
    }

    private List<BufferedChange> allChanges(final List<ListGroup> groups) {
        final List<BufferedChange> all = new ArrayList<>();
        groups.forEach(group -> all.addAll(group.changes()));
        return all;
    }

    private String displayName(final User user) {
        return user.getTgUsername() == null ? "someone" : "@" + user.getTgUsername();
    }

    private ZoneId zoneOf(final User user) {
        return user.getTimezone() == null ? ZoneOffset.UTC : user.getTimezone();
    }
}

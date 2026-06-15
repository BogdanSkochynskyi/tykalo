package io.tykalo.notification;

import io.tykalo.list.ListRenderer;
import io.tykalo.notification.NotificationBuffer.BufferedChange;
import io.tykalo.notification.NotificationBuffer.ListGroup;
import io.tykalo.user.ListChangeNotificationPreference;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Builds the user-facing text of a shared-list change notification (TK-196), returning ready-to-send
 * MarkdownV2 (escaped via {@link ListRenderer#escape}). Pure and stateless: it takes pre-resolved actor
 * display names and list names so it has no repository or gateway dependency and is trivially testable.
 *
 * <p>Each change becomes a phrase like {@code "@anna added 3 items"}; phrases for one list are joined
 * with commas. The wrapper differs by preference — INSTANT is a single punchy line, BATCHED notes the
 * 10-minute window, and the daily digest groups several lists under one heading.
 */
@Component
public class NotificationMessageFormatter {

    private static final String UNKNOWN_ACTOR = "someone";

    /** A windowed (INSTANT/BATCHED) message for one list. */
    public String windowMessage(final ListChangeNotificationPreference mode, final String listName,
                                final List<BufferedChange> changes, final Map<UUID, String> actorNames) {
        final String phrases = joinPhrases(changes, actorNames);
        final String raw = switch (mode) {
            case INSTANT -> "🔔 %s to \"%s\"".formatted(phrases, listName);
            case BATCHED -> "Changes in \"%s\" (last 10 min): %s".formatted(listName, phrases);
            case DAILY_DIGEST, OFF -> phrases;
        };
        return ListRenderer.escape(raw);
    }

    /** The once-a-day rollup spanning all of a recipient's shared lists. */
    public String dailyMessage(final List<ListGroup> groups, final Map<UUID, String> listNames,
                               final Map<UUID, String> actorNames) {
        final String body = groups.stream()
                .map(group -> "• \"%s\": %s".formatted(
                        listNames.getOrDefault(group.listId(), "a list"),
                        joinPhrases(group.changes(), actorNames)))
                .collect(Collectors.joining("\n"));
        return ListRenderer.escape("Daily list summary:\n\n" + body);
    }

    private String joinPhrases(final List<BufferedChange> changes, final Map<UUID, String> actorNames) {
        return changes.stream()
                .sorted(Comparator.comparing((BufferedChange c) -> actorName(c.actorId(), actorNames))
                        .thenComparing(c -> c.kind().name()))
                .map(change -> phrase(change, actorNames))
                .collect(Collectors.joining(", "));
    }

    private String phrase(final BufferedChange change, final Map<UUID, String> actorNames) {
        final String verb = switch (change.kind()) {
            case ADDED -> "added";
            case COMPLETED -> "completed";
        };
        final String noun = change.count() == 1 ? "item" : "items";
        return "%s %s %d %s".formatted(actorName(change.actorId(), actorNames), verb, change.count(), noun);
    }

    private String actorName(final UUID actorId, final Map<UUID, String> actorNames) {
        return actorNames.getOrDefault(actorId, UNKNOWN_ACTOR);
    }
}

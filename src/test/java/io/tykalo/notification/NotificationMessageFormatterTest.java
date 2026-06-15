package io.tykalo.notification;

import static org.assertj.core.api.Assertions.assertThat;

import io.tykalo.list.ListActivityEvent;
import io.tykalo.notification.NotificationBuffer.BufferedChange;
import io.tykalo.notification.NotificationBuffer.ListGroup;
import io.tykalo.user.ListChangeNotificationPreference;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationMessageFormatterTest {

    private final NotificationMessageFormatter formatter = new NotificationMessageFormatter();

    private final UUID anna = UUID.randomUUID();
    private final UUID petro = UUID.randomUUID();
    private final UUID listId = UUID.randomUUID();
    private final Map<UUID, String> names = Map.of(anna, "@anna", petro, "@petro");

    @Test
    void windowMessage_instant_singleActor_readsNaturally() {
        final String message = formatter.windowMessage(ListChangeNotificationPreference.INSTANT, "Groceries",
                List.of(new BufferedChange(anna, ListActivityEvent.Kind.ADDED, 3)), names);

        // MarkdownV2 escapes the quotes; assert on the meaningful tokens.
        assertThat(message).contains("@anna added 3 items").contains("Groceries");
    }

    @Test
    void windowMessage_batched_listsEveryActorPhrase() {
        final String message = formatter.windowMessage(ListChangeNotificationPreference.BATCHED, "Groceries",
                List.of(new BufferedChange(anna, ListActivityEvent.Kind.ADDED, 4),
                        new BufferedChange(petro, ListActivityEvent.Kind.COMPLETED, 2)), names);

        assertThat(message).contains("Changes in").contains("Groceries")
                .contains("@anna added 4 items").contains("@petro completed 2 items");
    }

    @Test
    void windowMessage_singularNoun_whenCountIsOne() {
        final String message = formatter.windowMessage(ListChangeNotificationPreference.BATCHED, "Groceries",
                List.of(new BufferedChange(anna, ListActivityEvent.Kind.ADDED, 1)), names);

        assertThat(message).contains("@anna added 1 item").doesNotContain("1 items");
    }

    @Test
    void windowMessage_fallsBackToSomeone_whenActorNameUnknown() {
        final String message = formatter.windowMessage(ListChangeNotificationPreference.INSTANT, "Groceries",
                List.of(new BufferedChange(UUID.randomUUID(), ListActivityEvent.Kind.ADDED, 1)), Map.of());

        assertThat(message).contains("someone added 1 item");
    }

    @Test
    void dailyMessage_groupsByList_underOneHeading() {
        final UUID other = UUID.randomUUID();
        final String message = formatter.dailyMessage(
                List.of(new ListGroup(listId, List.of(new BufferedChange(anna, ListActivityEvent.Kind.ADDED, 4))),
                        new ListGroup(other, List.of(new BufferedChange(petro, ListActivityEvent.Kind.COMPLETED, 2)))),
                Map.of(listId, "Groceries", other, "Chores"), names);

        assertThat(message).contains("Daily list summary")
                .contains("Groceries").contains("@anna added 4 items")
                .contains("Chores").contains("@petro completed 2 items");
    }
}

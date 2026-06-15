package io.tykalo.menu;

import io.tykalo.list.ListRenderer;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.ConversationStateService;
import io.tykalo.user.ListChangeNotificationPreference;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * Renders the Settings screen (TK-196): a "Notifications" section that lets the user pick how they are
 * pushed about changes to lists they share. The four {@link ListChangeNotificationPreference} options
 * are radio buttons — the current one is marked, and selecting another persists it and re-renders the
 * screen in place so the mark moves.
 *
 * <p>Two entry points share one renderer (mirroring {@link HelpService}): {@link #open(User)} sends a
 * new message (the {@code /settings} command), while {@link #navigate(User, int)} and
 * {@link #select(User, int, ListChangeNotificationPreference)} edit an existing message in place
 * (arriving from the main-menu button or after a pick). Both set {@link ConversationState.Settings},
 * which is navigation-only (no text input).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettingsService {

    public static final String NOTIF_PREFIX = "set:notif:";
    public static final String MENU = "set:menu";

    private static final String BODY = """
            ⚙️ Settings

            🔔 List change notifications
            How should I tell you when someone changes a list you share with others?

            ⚡ Instant — a message per change (a burst within 30s is grouped)
            📦 Batched — one roundup every 10 minutes
            🌅 Daily digest — one summary each morning
            🔕 Off — no pings (the list still updates live)""";

    private final UserService userService;
    private final ConversationStateService conversationState;
    private final TelegramMessageGateway gateway;

    /** Opens the settings screen as a new message — the {@code /settings} command. */
    public void open(final User user) {
        conversationState.setState(user.getId(), new ConversationState.Settings());
        gateway.sendMarkdown(user.getTgChatId(), ListRenderer.escape(BODY), keyboard(current(user)));
        log.debug("Opened settings screen for user id={}", user.getId());
    }

    /** Renders the settings screen into an existing message — the main-menu button. */
    public void navigate(final User user, final int messageId) {
        conversationState.setState(user.getId(), new ConversationState.Settings());
        gateway.editMarkdown(user.getTgChatId(), messageId, ListRenderer.escape(BODY), keyboard(current(user)));
        log.debug("Navigated user id={} to the settings screen", user.getId());
    }

    /** Persists the chosen preference and re-renders the screen in place so the radio mark moves. */
    public void select(final User user, final int messageId, final ListChangeNotificationPreference preference) {
        final User updated = userService.setListChangeNotifications(user, preference);
        conversationState.setState(updated.getId(), new ConversationState.Settings());
        gateway.editMarkdown(updated.getTgChatId(), messageId, ListRenderer.escape(BODY), keyboard(preference));
        log.debug("Set list-change notifications of user id={} to {}", updated.getId(), preference);
    }

    private ListChangeNotificationPreference current(final User user) {
        return user.getListChangeNotifications();
    }

    private InlineKeyboardMarkup keyboard(final ListChangeNotificationPreference current) {
        final List<InlineKeyboardRow> rows = new ArrayList<>();
        for (final ListChangeNotificationPreference preference : ListChangeNotificationPreference.values()) {
            final String marker = preference == current ? "🔘" : "⚪";
            rows.add(row(button(marker + " " + label(preference), NOTIF_PREFIX + preference.name())));
        }
        rows.add(row(button("⬅️ Back to menu", MENU)));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private String label(final ListChangeNotificationPreference preference) {
        return switch (preference) {
            case INSTANT -> "Instant";
            case BATCHED -> "Batched";
            case DAILY_DIGEST -> "Daily digest";
            case OFF -> "Off";
        };
    }

    private InlineKeyboardRow row(final InlineKeyboardButton button) {
        final InlineKeyboardRow row = new InlineKeyboardRow();
        row.add(button);
        return row;
    }

    private InlineKeyboardButton button(final String text, final String callbackData) {
        return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
    }
}

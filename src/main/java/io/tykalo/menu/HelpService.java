package io.tykalo.menu;

import io.tykalo.list.ListRenderer;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.ConversationStateService;
import io.tykalo.user.User;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * Renders the help screen (TK-189): a short intro with one button per {@link HelpTopic} that drills
 * down into that category's command list, replacing the static text-only {@code /help} (TK-171) with
 * inline navigation. The top-level screen offers "⬅️ Back to menu" (to the main menu); a category
 * screen shows that topic's commands as plain markdown text plus "⬅️ Back" (to the top-level help).
 *
 * <p>Two entry points share one renderer: {@link #open(User)} sends a new message (the {@code /help}
 * command), while {@link #navigate(User, int)} and {@link #showCategory(User, int, HelpTopic)} edit an
 * existing message in place (arriving from the main-menu button or paging between help screens).
 * Showing the top-level sets {@link ConversationState.Help}; a category sets
 * {@link ConversationState.HelpCategory}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HelpService {

    public static final String CATEGORY_PREFIX = "help:cat:";
    public static final String BACK = "help:back";
    public static final String MENU = "help:menu";

    private static final String INTRO = """
            ❓ Help

            I'm Tykalo — I help you manage tasks and lists, and gently nudge you when things slip.

            Pick a topic:""";

    private final ConversationStateService conversationState;
    private final TelegramMessageGateway gateway;

    /** Opens the help screen as a new message — the {@code /help} command. */
    public void open(final User user) {
        conversationState.setState(user.getId(), new ConversationState.Help());
        gateway.sendMarkdown(user.getTgChatId(), ListRenderer.escape(INTRO), topLevelKeyboard());
        log.debug("Opened help screen for user id={}", user.getId());
    }

    /** Renders the top-level help into an existing message — the main-menu button or a category Back. */
    public void navigate(final User user, final int messageId) {
        conversationState.setState(user.getId(), new ConversationState.Help());
        gateway.editMarkdown(user.getTgChatId(), messageId, ListRenderer.escape(INTRO), topLevelKeyboard());
        log.debug("Navigated user id={} to the help screen", user.getId());
    }

    /** Renders one category's commands into an existing message, with a Back button to the top-level. */
    public void showCategory(final User user, final int messageId, final HelpTopic topic) {
        conversationState.setState(user.getId(), new ConversationState.HelpCategory(topic));
        gateway.editMarkdown(user.getTgChatId(), messageId, ListRenderer.escape(topic.body()), categoryKeyboard());
        log.debug("Showed help category {} to user id={}", topic, user.getId());
    }

    private InlineKeyboardMarkup topLevelKeyboard() {
        final List<InlineKeyboardRow> rows = new ArrayList<>();
        for (final HelpTopic topic : HelpTopic.values()) {
            rows.add(row(button(topic.label(), CATEGORY_PREFIX + topic.name())));
        }
        rows.add(row(button("⬅️ Back to menu", MENU)));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private InlineKeyboardMarkup categoryKeyboard() {
        return InlineKeyboardMarkup.builder().keyboard(List.of(row(button("⬅️ Back", BACK)))).build();
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

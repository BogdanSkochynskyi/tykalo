package io.tykalo.menu;

import io.tykalo.list.ListRenderer;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.ConversationStateService;
import io.tykalo.user.User;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * Renders the main menu — the entry point for menu-driven navigation (TK-181). Showing it sends a
 * fresh inline-keyboard message through {@link TelegramMessageGateway} and records the user's
 * {@link ConversationState} as {@link ConversationState.MainMenu} so later screens know where they
 * came from. The text-command surface keeps working alongside the menu; {@code MainMenu} is not an
 * input-expecting state, so the dispatcher routes commands and plain messages exactly as before.
 *
 * <p>Each button carries a {@code menu:*} {@code callback_data} routed back to
 * {@link io.tykalo.menu.handler.MenuCallbackHandler}. The destination screens land in later tickets
 * (My Lists → TK-182, Create → TK-185, Settings → TK-186; Shared → TK-191, Stats → TBD), so those
 * buttons are placeholders for now.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MenuService {

    public static final String MY_LISTS = "menu:my_lists";
    public static final String SHARED = "menu:shared";
    public static final String CREATE = "menu:create";
    public static final String STATS = "menu:stats";
    public static final String SETTINGS = "menu:settings";
    public static final String HELP = "menu:help";

    private static final String MENU_TEXT = """
            🏠 Main menu

            What would you like to do?""";

    private final TelegramMessageGateway gateway;
    private final ConversationStateService conversationState;

    /** Sends the main menu to the user and sets their conversation state to {@code MainMenu}. */
    public void showMainMenu(final User user) {
        conversationState.setState(user.getId(), new ConversationState.MainMenu());
        gateway.sendMarkdown(user.getTgChatId(), ListRenderer.escape(MENU_TEXT), mainMenuKeyboard());
        log.debug("Showed main menu to user id={}", user.getId());
    }

    private InlineKeyboardMarkup mainMenuKeyboard() {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        row(button("📋 My Lists", MY_LISTS), button("👥 Shared with me", SHARED)),
                        row(button("➕ Create new list", CREATE), button("📊 Stats", STATS)),
                        row(button("⚙️ Settings", SETTINGS), button("❓ Help", HELP))))
                .build();
    }

    private InlineKeyboardRow row(final InlineKeyboardButton... buttons) {
        final InlineKeyboardRow row = new InlineKeyboardRow();
        for (final InlineKeyboardButton button : buttons) {
            row.add(button);
        }
        return row;
    }

    private InlineKeyboardButton button(final String text, final String callbackData) {
        return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
    }
}

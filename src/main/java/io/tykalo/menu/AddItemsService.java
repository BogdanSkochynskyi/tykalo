package io.tykalo.menu;

import io.tykalo.list.ListRenderer;
import io.tykalo.list.ListService;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskService;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.ConversationStateService;
import io.tykalo.user.User;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * The item-by-item add flow (TK-184), the user-friendly replacement for multi-line bulk-add (TK-123).
 * Tapping {@code ➕ Add items} on the list view (TK-183) {@link #start}s it: the user's
 * {@link ConversationState} becomes {@link ConversationState.AddingItems} and a separate prompt message
 * with {@code ✅ Done} / {@code ❌ Cancel} is sent below the list view. Each plain-text message in that
 * state then {@link #addItems creates one task per non-blank line} and re-renders the list view in place
 * above the prompt, so the new item appears immediately. {@link #finish} (Done or Cancel) deletes the
 * prompt and returns to the list view; items added during the session stay (they are already saved).
 *
 * <p>The prompt's buttons carry the {@code add:} {@code callback_data} prefix (see
 * {@link io.tykalo.menu.handler.AddItemsCallbackHandler}); the state-routed text input is consumed by
 * {@link io.tykalo.menu.handler.AddItemsStateHandler}. The old bulk handler keeps working as a shortcut
 * outside this state — entering the flow is opt-in.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AddItemsService {

    public static final String DONE = "add:done";
    public static final String CANCEL = "add:cancel";

    static final String PROMPT =
            "➕ Send items one by one — each message becomes an item.\nTap ✅ Done when you're finished.";
    private static final String LIST_GONE = "That list is no longer available.";

    private final ListService listService;
    private final TaskService taskService;
    private final ConversationStateService conversationState;
    private final ListViewService listViewService;
    private final TelegramMessageGateway gateway;

    /**
     * Begins the add flow for {@code listId}, opened from the list-view message {@code listViewMessageId}.
     * Sets the {@code AddingItems} state and sends the prompt. Returns the list name (for the opener's
     * toast) or empty when the list no longer exists.
     */
    public Optional<String> start(final User user, final int listViewMessageId, final UUID listId) {
        final Optional<TaskList> list = listService.getActiveById(listId);
        if (list.isEmpty()) {
            return Optional.empty();
        }
        conversationState.setState(user.getId(), new ConversationState.AddingItems(listId, listViewMessageId));
        gateway.sendMarkdown(user.getTgChatId(), ListRenderer.escape(PROMPT), promptKeyboard());
        log.debug("Started add-items flow list={} for user id={}", listId, user.getId());
        return Optional.of(list.get().getName());
    }

    /**
     * Consumes one message while adding: every non-blank line becomes a task and the list view is
     * re-rendered showing the last page (where the new items land). Stays silent — the live list view is
     * the feedback. A blank message is ignored; a list deleted mid-flow ends the session with a notice.
     */
    public Optional<String> addItems(final User user, final ConversationState.AddingItems state, final String text) {
        if (listService.getActiveById(state.listId()).isEmpty()) {
            conversationState.setState(user.getId(), new ConversationState.Idle());
            return Optional.of(LIST_GONE);
        }
        final List<String> titles = nonBlankLines(text);
        if (titles.isEmpty()) {
            return Optional.empty();
        }
        taskService.createTasks(user.getId(), state.listId(), titles);
        listViewService.showLastPage(user, state.listViewMessageId(), state.listId());
        log.debug("Added {} item(s) to list={} for user id={}", titles.size(), state.listId(), user.getId());
        return Optional.empty();
    }

    /**
     * Ends the add flow (Done or Cancel): deletes the {@code promptMessageId} prompt and returns to the
     * list view in place. {@code done} only changes the toast — both keep the items already added.
     */
    public Optional<String> finish(final User user, final ConversationState.AddingItems state,
                                   final int promptMessageId, final boolean done) {
        gateway.deleteMessage(user.getTgChatId(), promptMessageId);
        final Optional<String> shown = listViewService.show(user, state.listViewMessageId(), state.listId(), 0);
        if (shown.isEmpty()) {
            conversationState.setState(user.getId(), new ConversationState.Idle());
            return Optional.of(LIST_GONE);
        }
        return Optional.of(done ? "✅ Items added" : "Stopped adding");
    }

    private List<String> nonBlankLines(final String text) {
        return text.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
    }

    private InlineKeyboardMarkup promptKeyboard() {
        final InlineKeyboardRow row = new InlineKeyboardRow();
        row.add(InlineKeyboardButton.builder().text("✅ Done").callbackData(DONE).build());
        row.add(InlineKeyboardButton.builder().text("❌ Cancel").callbackData(CANCEL).build());
        return InlineKeyboardMarkup.builder().keyboardRow(row).build();
    }
}

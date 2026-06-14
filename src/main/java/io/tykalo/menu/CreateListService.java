package io.tykalo.menu;

import io.tykalo.list.ListRenderer;
import io.tykalo.list.ListService;
import io.tykalo.list.ListType;
import io.tykalo.list.TaskList;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.ConversationStateService;
import io.tykalo.user.User;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * The conversational new-list flow (TK-185), the friendly replacement for {@code /list create}. It is
 * opened from the main menu ({@code menu:create}) or the My Lists screen ({@code lists:new}) and runs as
 * a sequence of in-place edits to that one message — the same navigation pattern as the other menu
 * screens (TK-182/183/184):
 *
 * <ol>
 *   <li>{@link #start} edits the message into a <b>type picker</b> (Checklist / Project / Routine) and
 *       sets {@link ConversationState.CreatingListType}.</li>
 *   <li>{@link #chooseType} edits it into a <b>"Name your list:"</b> prompt and sets
 *       {@link ConversationState.CreatingListName}, carrying the prompt's message id.</li>
 *   <li>{@link #submitName} validates the typed name and, on success, creates the list and transitions
 *       the same message into the <b>list view</b> (TK-183) of the new list.</li>
 * </ol>
 *
 * <p>The type-picker buttons carry the {@code create:} {@code callback_data} prefix (see
 * {@link io.tykalo.menu.handler.CreateListCallbackHandler}); the name input is consumed by
 * {@link io.tykalo.menu.handler.CreateListStateHandler}. A blank or duplicate name re-prompts in place
 * and stays in the naming state. {@code ❌ Cancel} at either step returns to the main menu. The ROUTINE
 * "set up schedule now?" follow-up is deferred to TK-203 (Phase 2).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreateListService {

    public static final String TYPE_PREFIX = "create:type:";
    public static final String CANCEL = "create:cancel";

    static final String TYPE_PICKER_TEXT = """
            ➕ Create a new list

            What kind of list is it?
            🛒 Checklist — simple items, no deadlines (shopping, packing)
            📋 Project — tasks with deadlines and Nudgers (work, study)
            🔄 Routine — a recurring task group (gym, morning routine)""";
    static final String NAME_PROMPT = "✏️ Name your list:";

    private final ListService listService;
    private final ConversationStateService conversationState;
    private final ListViewService listViewService;
    private final MenuService menuService;
    private final TelegramMessageGateway gateway;

    /** Step 1: edits {@code messageId} into the type picker and sets the {@code CreatingListType} state. */
    public void start(final User user, final int messageId) {
        conversationState.setState(user.getId(), new ConversationState.CreatingListType());
        gateway.editMarkdown(user.getTgChatId(), messageId, ListRenderer.escape(TYPE_PICKER_TEXT), typePicker());
        log.debug("Started create-list flow for user id={}", user.getId());
    }

    /**
     * Step 2: records the chosen {@code type}, edits {@code messageId} into the name prompt and sets the
     * {@code CreatingListName} state (carrying that message id so the name input can re-render it).
     */
    public void chooseType(final User user, final int messageId, final ListType type) {
        conversationState.setState(user.getId(), new ConversationState.CreatingListName(type, messageId));
        gateway.editMarkdown(user.getTgChatId(), messageId, ListRenderer.escape(NAME_PROMPT), cancelOnly());
        log.debug("Create-list flow: user id={} picked type={}", user.getId(), type);
    }

    /**
     * Step 3: validates the typed {@code name}. A blank name or a duplicate of an existing active list
     * re-prompts in place and keeps the naming state; a valid name creates the list and transitions the
     * prompt message into the new list's view. Always silent (the edited message is the feedback).
     */
    public Optional<String> submitName(final User user, final ConversationState.CreatingListName state,
                                       final String name) {
        final String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty()) {
            reprompt(user, state, "⚠️ A list name can't be blank.");
            return Optional.empty();
        }
        if (listService.findActiveByName(user.getId(), trimmed).isPresent()) {
            reprompt(user, state, "⚠️ You already have a list named \"%s\".".formatted(trimmed));
            return Optional.empty();
        }
        final TaskList created = listService.createList(user, trimmed, state.type());
        listViewService.show(user, state.promptMessageId(), created.getId(), 0);
        log.debug("Create-list flow: user id={} created list id={}", user.getId(), created.getId());
        return Optional.empty();
    }

    /**
     * {@code ❌ Cancel}: drops the flow and returns the message to the main menu — the neutral home,
     * since we don't track which screen opened the flow.
     */
    public Optional<String> cancel(final User user, final int messageId) {
        menuService.editToMainMenu(user, messageId);
        log.debug("Create-list flow cancelled by user id={}", user.getId());
        return Optional.of("Cancelled");
    }

    private void reprompt(final User user, final ConversationState.CreatingListName state, final String notice) {
        final String text = ListRenderer.escape(notice + "\n" + NAME_PROMPT);
        gateway.editMarkdown(user.getTgChatId(), state.promptMessageId(), text, cancelOnly());
    }

    private InlineKeyboardMarkup typePicker() {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        row(button("🛒 Checklist", TYPE_PREFIX + ListType.CHECKLIST.name())),
                        row(button("📋 Project", TYPE_PREFIX + ListType.PROJECT.name())),
                        row(button("🔄 Routine", TYPE_PREFIX + ListType.ROUTINE.name())),
                        row(button("❌ Cancel", CANCEL))))
                .build();
    }

    private InlineKeyboardMarkup cancelOnly() {
        return InlineKeyboardMarkup.builder().keyboardRow(row(button("❌ Cancel", CANCEL))).build();
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

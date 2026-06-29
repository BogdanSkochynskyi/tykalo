package io.tykalo.menu;

import io.tykalo.list.ListRenderer;
import io.tykalo.list.ListService;
import io.tykalo.list.ListTags;
import io.tykalo.list.ListType;
import io.tykalo.list.PendingItem;
import io.tykalo.list.PendingItemService;
import io.tykalo.list.TaskList;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.ConversationStateService;
import io.tykalo.user.User;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 *   <li>{@link #submitName} validates the typed name and, on success, transitions the same message into a
 *       <b>tags prompt</b> (TK-258, optional) and sets {@link ConversationState.CreatingListTags}.</li>
 *   <li>{@link #submitTags} (or {@link #skipTags}) creates the list — only now, so a cancel before this
 *       point leaves no orphan list — and either opens its <b>list view</b> (TK-183) or, when the typed
 *       tags match items saved for later, the <b>pending-match screen</b> ({@link NewListPendingService}).</li>
 * </ol>
 *
 * <p>The type-picker, cancel and skip-tags buttons carry the {@code create:} {@code callback_data} prefix
 * (see {@link io.tykalo.menu.handler.CreateListCallbackHandler}); the name and tags inputs are consumed by
 * {@link io.tykalo.menu.handler.CreateListStateHandler} and
 * {@link io.tykalo.menu.handler.CreateListTagsStateHandler}. A blank or duplicate name, or an invalid tag,
 * re-prompts in place and stays in the same state. {@code ❌ Cancel} at any step returns to the main menu.
 * The ROUTINE "set up schedule now?" follow-up is deferred to TK-203 (Phase 2).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreateListService {

    public static final String TYPE_PREFIX = "create:type:";
    public static final String CANCEL = "create:cancel";
    public static final String SKIP_TAGS = "create:skiptags";

    static final String TYPE_PICKER_TEXT = """
            ➕ Create a new list

            What kind of list is it?
            🛒 Checklist — simple items, no deadlines (shopping, packing)
            📋 Project — tasks with deadlines and Nudgers (work, study)
            🔄 Routine — a recurring task group (gym, morning routine)""";
    static final String NAME_PROMPT = "✏️ Name your list:";
    static final String TAGS_PROMPT = "🏷️ Add tags? Send them separated by spaces or commas "
            + "(letters and digits only), or tap Skip.";

    private final ListService listService;
    private final PendingItemService pendingItemService;
    private final ConversationStateService conversationState;
    private final ListViewService listViewService;
    private final NewListPendingService newListPendingService;
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
     * re-prompts in place and keeps the naming state; a valid name transitions the prompt message into the
     * optional tags step (TK-258) without creating the list yet. Always silent (the edited message is the
     * feedback).
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
        conversationState.setState(user.getId(),
                new ConversationState.CreatingListTags(state.type(), trimmed, state.promptMessageId()));
        gateway.editMarkdown(user.getTgChatId(), state.promptMessageId(), ListRenderer.escape(TAGS_PROMPT),
                tagsButtons());
        log.debug("Create-list flow: user id={} named list, awaiting tags", user.getId());
        return Optional.empty();
    }

    /**
     * Step 4 (skip path): {@code ⏭️ Skip} on the tags prompt — creates the list with no tags and opens its
     * view. With no tags there is nothing to match, so the pending check is skipped entirely.
     */
    public Optional<String> skipTags(final User user, final ConversationState.CreatingListTags state) {
        final TaskList created = listService.createList(user, state.name(), state.type());
        listViewService.show(user, state.promptMessageId(), created.getId(), 0);
        log.debug("Create-list flow: user id={} created list id={} (no tags)", user.getId(), created.getId());
        return Optional.empty();
    }

    /**
     * Step 4 (tags path): parses the typed {@code text} into tags (split on spaces/commas, validated and
     * normalized via {@link ListTags}). Any invalid token re-prompts in place and keeps the state. On
     * success the list is created with those tags and, if the user has saved-for-later items whose tags
     * overlap (TK-258), the pending-match screen opens; otherwise the new list's view. Always silent.
     */
    public Optional<String> submitTags(final User user, final ConversationState.CreatingListTags state,
                                       final String text) {
        final Optional<List<String>> parsed = parseTags(text);
        if (parsed.isEmpty()) {
            repromptTags(user, state, "⚠️ Use letters and digits only, up to %d characters per tag."
                    .formatted(ListTags.MAX_LENGTH));
            return Optional.empty();
        }
        final List<String> tags = parsed.get();
        if (tags.isEmpty()) {
            repromptTags(user, state, "⚠️ Send at least one tag, or tap Skip.");
            return Optional.empty();
        }
        final TaskList created = listService.createList(user, state.name(), state.type());
        tags.forEach(tag -> listService.addTag(user.getId(), created.getId(), tag));
        log.debug("Create-list flow: user id={} created list id={} with {} tag(s)",
                user.getId(), created.getId(), tags.size());
        final List<PendingItem> matches = pendingItemService.findByTags(user.getId(), tags);
        if (matches.isEmpty()) {
            listViewService.show(user, state.promptMessageId(), created.getId(), 0);
        } else {
            newListPendingService.present(user, state.promptMessageId(), created, matches);
        }
        return Optional.empty();
    }

    /**
     * {@code ❌ Cancel}: drops the flow and returns the message to the main menu — the neutral home,
     * since we don't track which screen opened the flow. No list exists yet at any cancellable step.
     */
    public Optional<String> cancel(final User user, final int messageId) {
        menuService.editToMainMenu(user, messageId);
        log.debug("Create-list flow cancelled by user id={}", user.getId());
        return Optional.of("Cancelled");
    }

    /**
     * Splits {@code text} on spaces/commas and validates each token via {@link ListTags}, returning the
     * deduped, normalized tags (empty list when the input is only separators) or empty when any token is
     * invalid.
     */
    private Optional<List<String>> parseTags(final String text) {
        if (text == null) {
            return Optional.of(List.of());
        }
        final Set<String> tags = new LinkedHashSet<>();
        for (final String token : text.strip().split("[\\s,]+")) {
            if (token.isEmpty()) {
                continue;
            }
            if (!(ListTags.validate(token) instanceof ListTags.Validation.Valid(final String tag))) {
                return Optional.empty();
            }
            tags.add(tag);
        }
        return Optional.of(new ArrayList<>(tags));
    }

    private void reprompt(final User user, final ConversationState.CreatingListName state, final String notice) {
        final String text = ListRenderer.escape(notice + "\n" + NAME_PROMPT);
        gateway.editMarkdown(user.getTgChatId(), state.promptMessageId(), text, cancelOnly());
    }

    private void repromptTags(final User user, final ConversationState.CreatingListTags state,
                              final String notice) {
        final String text = ListRenderer.escape(notice + "\n\n" + TAGS_PROMPT);
        gateway.editMarkdown(user.getTgChatId(), state.promptMessageId(), text, tagsButtons());
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

    private InlineKeyboardMarkup tagsButtons() {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        row(button("⏭️ Skip", SKIP_TAGS)),
                        row(button("❌ Cancel", CANCEL))))
                .build();
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

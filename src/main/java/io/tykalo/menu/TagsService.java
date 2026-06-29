package io.tykalo.menu;

import io.tykalo.list.ListPermissionService;
import io.tykalo.list.ListRenderer;
import io.tykalo.list.ListService;
import io.tykalo.list.ListStatus;
import io.tykalo.list.ListTags;
import io.tykalo.list.TaskList;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.ConversationStateService;
import io.tykalo.user.User;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * The per-list tags screen (TK-259), reached from the {@code 🏷️ Tags} button on the settings screen
 * ({@link ListSettingsService}). It shows the current tags as {@code #chips}, a {@code ❌} remove button
 * per tag (each guarded by a confirmation step), one-tap {@code ➕} quick-adds for the common
 * {@link ListTags#SUGGESTIONS}, and an {@code ✏️ Add custom tag} prompt for free-form input.
 *
 * <p>Everything edits the originating list-view message in place (mirroring {@link ListSettingsService}),
 * so {@code callback_data} carries indices — into the current tags or the fixed suggestion list — rather
 * than the tag text, keeping it well under Telegram's 64-byte limit; each tap re-renders, so the indices
 * stay fresh. The screen is an OWNER/EDITOR action: a non-editor or a missing/closed list yields empty,
 * surfaced by the caller as a toast.
 *
 * <p>State: {@code 🏷️ Tags} sets {@link ConversationState.EditingListTags} (navigation); {@code ✏️ Add
 * custom tag} switches to {@link ConversationState.AddingTag} (text input) whose next message is consumed
 * by {@link io.tykalo.menu.handler.AddTagStateHandler} → {@link #submit}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TagsService {

    public static final String OPEN_PREFIX = "tg:open:";
    public static final String ADD_PREFIX = "tg:add:";
    public static final String QUICK_PREFIX = "tg:q:";
    public static final String REMOVE_PREFIX = "tg:rm:";
    public static final String REMOVE_CONFIRM_PREFIX = "tg:rmok:";
    public static final String BACK_PREFIX = "tg:back:";

    private static final String GONE = "That list is no longer available or you can't edit it.";

    private final ListService listService;
    private final ListPermissionService permissionService;
    private final ListSettingsService listSettingsService;
    private final ConversationStateService conversationState;
    private final TelegramMessageGateway gateway;

    /** Renders the tags screen in place and enters {@code EditingListTags}. Empty when gone or not editable. */
    public Optional<String> open(final User user, final int messageId, final UUID listId) {
        return withEditableList(user, listId, list -> {
            conversationState.setState(user.getId(), new ConversationState.EditingListTags(listId));
            renderTags(user, messageId, list);
            return list.getName();
        });
    }

    /** Switches to the add-tag prompt (and {@code AddingTag} state). Empty when gone or not editable. */
    public Optional<String> promptAdd(final User user, final int messageId, final UUID listId) {
        return withEditableList(user, listId, list -> {
            conversationState.setState(user.getId(), new ConversationState.AddingTag(listId, messageId));
            renderPrompt(user, messageId, list, null);
            return list.getName();
        });
    }

    /** Adds the {@code suggestionIndex}-th common suggestion in one tap, then re-renders. Returns a toast. */
    public Optional<String> quickAdd(final User user, final int messageId, final UUID listId,
                                     final int suggestionIndex) {
        if (suggestionIndex < 0 || suggestionIndex >= ListTags.SUGGESTIONS.size()) {
            return Optional.empty();
        }
        final String tag = ListTags.SUGGESTIONS.get(suggestionIndex);
        return withEditableList(user, listId, list -> {
            conversationState.setState(user.getId(), new ConversationState.EditingListTags(listId));
            if (list.getTags().contains(tag)) {
                renderTags(user, messageId, list);
                return "Already tagged #" + tag;
            }
            final TaskList updated = listService.addTag(user.getId(), listId, tag);
            renderTags(user, messageId, updated);
            return "🏷️ Added #" + tag;
        });
    }

    /** Asks to confirm removing the {@code tagIndex}-th tag. Empty when gone, not editable or index stale. */
    public Optional<String> confirmRemove(final User user, final int messageId, final UUID listId,
                                          final int tagIndex) {
        return withEditableList(user, listId, list -> {
            conversationState.setState(user.getId(), new ConversationState.EditingListTags(listId));
            if (tagIndex < 0 || tagIndex >= list.getTags().size()) {
                renderTags(user, messageId, list);
                return list.getName();
            }
            renderRemoveConfirm(user, messageId, list, list.getTags().get(tagIndex), tagIndex);
            return list.getName();
        });
    }

    /** Removes the {@code tagIndex}-th tag (after confirmation), then re-renders. Returns a toast. */
    public Optional<String> remove(final User user, final int messageId, final UUID listId, final int tagIndex) {
        return withEditableList(user, listId, list -> {
            conversationState.setState(user.getId(), new ConversationState.EditingListTags(listId));
            if (tagIndex < 0 || tagIndex >= list.getTags().size()) {
                renderTags(user, messageId, list);
                return "That tag is no longer there.";
            }
            final String tag = list.getTags().get(tagIndex);
            final TaskList updated = listService.removeTag(user.getId(), listId, tag);
            renderTags(user, messageId, updated);
            return "🗑️ Removed #" + tag;
        });
    }

    /** Returns to the per-list settings screen in place. Empty when gone or not editable. */
    public Optional<String> back(final User user, final int messageId, final UUID listId) {
        return listSettingsService.open(user, messageId, listId);
    }

    /**
     * Consumes a typed tag while in {@code AddingTag}: validates and normalizes via {@link ListTags}, then
     * adds it and returns to the tags screen. On a duplicate or invalid input the prompt is re-rendered
     * with a notice and the state is kept, so the user can try again. Stays silent — the screen is the
     * feedback — except when the list vanished mid-flow.
     */
    public Optional<String> submit(final User user, final ConversationState.AddingTag state, final String text) {
        final Optional<TaskList> editable = editableActiveList(user, state.listId());
        if (editable.isEmpty()) {
            conversationState.setState(user.getId(), new ConversationState.Idle());
            return Optional.of(GONE);
        }
        final TaskList list = editable.get();
        switch (ListTags.validate(text)) {
            case ListTags.Validation.Valid(final String tag) -> {
                if (list.getTags().contains(tag)) {
                    renderPrompt(user, state.messageId(), list, "#" + tag + " is already on this list.");
                } else {
                    final TaskList updated = listService.addTag(user.getId(), state.listId(), tag);
                    conversationState.setState(user.getId(), new ConversationState.EditingListTags(state.listId()));
                    renderTags(user, state.messageId(), updated);
                }
            }
            case ListTags.Validation.Invalid(final ListTags.Error error) ->
                    renderPrompt(user, state.messageId(), list, errorMessage(error));
        }
        return Optional.empty();
    }

    private Optional<String> withEditableList(final User user, final UUID listId,
                                              final Function<TaskList, String> action) {
        return editableActiveList(user, listId).map(action);
    }

    private void renderTags(final User user, final int messageId, final TaskList list) {
        final String chips = list.getTags().isEmpty()
                ? "No tags yet."
                : String.join("  ", list.getTags().stream().map("#"::concat).toList());
        final String text = ListRenderer.escape(("""
                🏷️ Tags — %s

                %s

                Tap a tag to remove it, pick a suggestion, or ✏️ add your own.""")
                .formatted(list.getName(), chips));
        gateway.editMarkdown(user.getTgChatId(), messageId, text, tagsKeyboard(list));
    }

    private void renderRemoveConfirm(final User user, final int messageId, final TaskList list,
                                     final String tag, final int tagIndex) {
        final UUID listId = Objects.requireNonNull(list.getId());
        final String text = ListRenderer.escape("🗑️ Remove tag #%s from \"%s\"?".formatted(tag, list.getName()));
        final List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(row(button("✅ Remove", REMOVE_CONFIRM_PREFIX + listId + ":" + tagIndex)));
        rows.add(row(button("⬅️ Cancel", OPEN_PREFIX + listId)));
        gateway.editMarkdown(user.getTgChatId(), messageId, text,
                InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private void renderPrompt(final User user, final int messageId, final TaskList list,
                              final @Nullable String notice) {
        final UUID listId = Objects.requireNonNull(list.getId());
        final String header = notice == null ? "" : "⚠️ " + notice + "\n\n";
        final String text = ListRenderer.escape((header
                + "✏️ Send a tag to add to \"%s\" — letters and digits only, up to %d characters. "
                + "I'll lowercase it.").formatted(list.getName(), ListTags.MAX_LENGTH));
        final List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(row(button("❌ Cancel", OPEN_PREFIX + listId)));
        gateway.editMarkdown(user.getTgChatId(), messageId, text,
                InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private InlineKeyboardMarkup tagsKeyboard(final TaskList list) {
        final UUID listId = Objects.requireNonNull(list.getId());
        final List<InlineKeyboardRow> rows = new ArrayList<>();
        final List<String> tags = list.getTags();
        for (int i = 0; i < tags.size(); i += 2) {
            final InlineKeyboardRow row = new InlineKeyboardRow();
            row.add(button("❌ " + tags.get(i), REMOVE_PREFIX + listId + ":" + i));
            if (i + 1 < tags.size()) {
                row.add(button("❌ " + tags.get(i + 1), REMOVE_PREFIX + listId + ":" + (i + 1)));
            }
            rows.add(row);
        }
        InlineKeyboardRow suggestions = new InlineKeyboardRow();
        for (int i = 0; i < ListTags.SUGGESTIONS.size(); i++) {
            final String suggestion = ListTags.SUGGESTIONS.get(i);
            if (tags.contains(suggestion)) {
                continue;
            }
            suggestions.add(button("➕ " + suggestion, QUICK_PREFIX + listId + ":" + i));
            if (suggestions.size() == 3) {
                rows.add(suggestions);
                suggestions = new InlineKeyboardRow();
            }
        }
        if (!suggestions.isEmpty()) {
            rows.add(suggestions);
        }
        rows.add(row(button("✏️ Add custom tag", ADD_PREFIX + listId)));
        rows.add(row(button("⬅️ Back", BACK_PREFIX + listId)));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private String errorMessage(final ListTags.Error error) {
        return switch (error) {
            case EMPTY -> "A tag can't be empty.";
            case TOO_LONG -> "Keep it under " + ListTags.MAX_LENGTH + " characters.";
            case INVALID_CHARS -> "Use letters and digits only — no spaces or symbols.";
        };
    }

    private Optional<TaskList> editableActiveList(final User user, final UUID listId) {
        return listService.getActiveById(listId)
                .filter(list -> list.getStatus() == ListStatus.ACTIVE)
                .filter(list -> permissionService.canEditList(user.getId(), listId));
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

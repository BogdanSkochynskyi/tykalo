package io.tykalo.menu;

import io.tykalo.list.ListRenderer;
import io.tykalo.list.ListService;
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
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * The "add matching pending items?" screen (TK-258), shown right after a tagged list is created when the
 * user has items "saved for later" ({@link PendingItem}) whose captured tags overlap the new list's tags.
 * It lists each match as a tap-to-toggle checkbox row (all selected by default), with {@code ✅ Add
 * selected} / {@code 🗑️ Drop selected} / {@code ⏭️ Skip} actions:
 *
 * <ul>
 *   <li><b>Add</b> restores each selected item into the new list as a {@link io.tykalo.list.Task} and
 *       removes it from the pending bucket.</li>
 *   <li><b>Drop</b> removes the selected items from the bucket without restoring them.</li>
 *   <li><b>Skip</b> leaves everything in the bucket and just opens the new list.</li>
 * </ul>
 *
 * <p>The candidate items are re-derived on every render from the list's current tags
 * ({@link PendingItemService#findByTags}), so the {@link ConversationState.CreatingListPendingCheck} state
 * only carries the selection (by id). {@code callback_data} carries the pending-item id for a toggle (a
 * UUID fits comfortably under Telegram's 64-byte limit). The selection is always intersected with the
 * live candidates before acting, so an item deleted elsewhere mid-flow is silently ignored.
 *
 * <p>Reached from {@link CreateListService#submitTags}; the buttons are handled by
 * {@link io.tykalo.menu.handler.NewListPendingCallbackHandler}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NewListPendingService {

    public static final String TOGGLE_PREFIX = "clp:t:";
    public static final String ADD = "clp:add";
    public static final String DROP = "clp:drop";
    public static final String SKIP = "clp:skip";

    private static final int BUTTON_TITLE_MAX = 24;

    private final ListService listService;
    private final PendingItemService pendingItemService;
    private final ConversationStateService conversationState;
    private final ListViewService listViewService;
    private final TelegramMessageGateway gateway;

    /**
     * Renders the match screen into {@code messageId} with every {@code candidate} selected, and enters
     * {@link ConversationState.CreatingListPendingCheck}. Callers only invoke this when there is at least
     * one candidate; an empty list would mean there is nothing to offer.
     */
    public void present(final User user, final int messageId, final TaskList list,
                        final List<PendingItem> candidates) {
        final List<UUID> selected = candidates.stream().map(PendingItem::getId).toList();
        conversationState.setState(user.getId(),
                new ConversationState.CreatingListPendingCheck(list.getId(), new ArrayList<>(selected)));
        render(user, messageId, list, candidates, new LinkedHashSet<>(selected));
    }

    /** Flips the {@code itemId} checkbox and re-renders. Stale id or screen → silently re-renders/no-op. */
    public Optional<String> toggle(final User user, final int messageId,
                                   final ConversationState.CreatingListPendingCheck state, final UUID itemId) {
        final Optional<TaskList> list = listService.getActiveById(state.newListId());
        if (list.isEmpty()) {
            return openList(user, messageId, state.newListId());
        }
        final List<PendingItem> candidates = pendingItemService.findByTags(user.getId(), list.get().getTags());
        final Set<UUID> selected = new LinkedHashSet<>(state.selectedIds());
        if (candidates.stream().anyMatch(c -> c.getId().equals(itemId)) && !selected.remove(itemId)) {
            selected.add(itemId);
        }
        conversationState.setState(user.getId(),
                new ConversationState.CreatingListPendingCheck(state.newListId(), new ArrayList<>(selected)));
        render(user, messageId, list.get(), candidates, selected);
        return Optional.empty();
    }

    /** Restores the selected items into the new list, removes them from pending, then opens the list. */
    public Optional<String> addSelected(final User user, final int messageId,
                                        final ConversationState.CreatingListPendingCheck state) {
        final Optional<TaskList> list = listService.getActiveById(state.newListId());
        if (list.isEmpty()) {
            return openList(user, messageId, state.newListId());
        }
        final List<UUID> toMigrate = liveSelection(user, list.get(), state);
        toMigrate.forEach(id -> pendingItemService.restore(id, state.newListId()));
        log.info("Added {} pending item(s) into new list {}", toMigrate.size(), state.newListId());
        final Optional<String> opened = openList(user, messageId, state.newListId());
        return toMigrate.isEmpty() ? opened : Optional.of("✅ Added " + toMigrate.size() + " item(s)");
    }

    /** Drops the selected items from the pending bucket without restoring them, then opens the list. */
    public Optional<String> dropSelected(final User user, final int messageId,
                                         final ConversationState.CreatingListPendingCheck state) {
        final Optional<TaskList> list = listService.getActiveById(state.newListId());
        if (list.isEmpty()) {
            return openList(user, messageId, state.newListId());
        }
        final List<UUID> toDrop = liveSelection(user, list.get(), state);
        toDrop.forEach(pendingItemService::delete);
        log.info("Dropped {} pending item(s) on new list {}", toDrop.size(), state.newListId());
        final Optional<String> opened = openList(user, messageId, state.newListId());
        return toDrop.isEmpty() ? opened : Optional.of("🗑️ Dropped " + toDrop.size() + " item(s)");
    }

    /** Leaves every match in the pending bucket and opens the new list. */
    public Optional<String> skip(final User user, final int messageId,
                                 final ConversationState.CreatingListPendingCheck state) {
        return openList(user, messageId, state.newListId());
    }

    private List<UUID> liveSelection(final User user, final TaskList list,
                                     final ConversationState.CreatingListPendingCheck state) {
        final Set<UUID> selected = new LinkedHashSet<>(state.selectedIds());
        return pendingItemService.findByTags(user.getId(), list.getTags()).stream()
                .map(PendingItem::getId)
                .filter(selected::contains)
                .toList();
    }

    private Optional<String> openList(final User user, final int messageId, final UUID listId) {
        final Optional<String> shown = listViewService.show(user, messageId, listId, 0);
        return shown.isEmpty() ? Optional.of("That list is no longer available.") : Optional.empty();
    }

    private void render(final User user, final int messageId, final TaskList list,
                        final List<PendingItem> candidates, final Set<UUID> selected) {
        final String text = ListRenderer.escape(("""
                🗂️ "%s" created.

                You have %d saved-for-later item(s) that match its tags. Add them to this list?""")
                .formatted(list.getName(), candidates.size()));
        gateway.editMarkdown(user.getTgChatId(), messageId, text, keyboard(candidates, selected));
    }

    private InlineKeyboardMarkup keyboard(final List<PendingItem> candidates, final Set<UUID> selected) {
        final List<InlineKeyboardRow> rows = new ArrayList<>();
        for (final PendingItem item : candidates) {
            final String mark = selected.contains(item.getId()) ? "✅ " : "☐ ";
            rows.add(row(button(mark + truncate(item.getTitle()), TOGGLE_PREFIX + item.getId())));
        }
        final InlineKeyboardRow actions = new InlineKeyboardRow();
        actions.add(button("✅ Add selected", ADD));
        actions.add(button("🗑️ Drop selected", DROP));
        rows.add(actions);
        rows.add(row(button("⏭️ Skip", SKIP)));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private String truncate(final String title) {
        return title.length() <= BUTTON_TITLE_MAX ? title : title.substring(0, BUTTON_TITLE_MAX - 1) + "…";
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

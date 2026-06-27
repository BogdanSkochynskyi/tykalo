package io.tykalo.menu;

import io.tykalo.list.ListClosingService;
import io.tykalo.list.ListLifecycleService;
import io.tykalo.list.ListPermissionService;
import io.tykalo.list.ListRenderer;
import io.tykalo.list.ListService;
import io.tykalo.list.ListStatus;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskService;
import io.tykalo.telegram.CompactUuid;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.ConversationStateService;
import io.tykalo.user.User;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * Drives the close-list flow (TK-254), reached from the {@code 🏁 Close list} button in the list view
 * (TK-183). The flow is a small state machine over one edited message:
 *
 * <ul>
 *   <li>{@link #start} renders the close screen — a one-tap confirmation when every item is done, or the
 *       three-way carry-over screen ({@code 📌 Save for later} / {@code ➡️ Move to another list} /
 *       {@code 🗑 Drop & close}) when items remain — and sets {@link ConversationState.ClosingList}.</li>
 *   <li>{@link #showMovePicker} lists the user's other active lists to move unfinished items into and sets
 *       {@link ConversationState.ClosingListTarget}; {@link #showDropConfirm} guards the lossy drop.</li>
 *   <li>The execute methods ({@link #confirmClose}, {@link #saveForLater}, {@link #moveTo}, {@link #drop})
 *       perform the carry-over via {@link ListClosingService}/{@link ListLifecycleService}, then re-render
 *       the now-COMPLETED list view in place (read-only, with {@code 🔄 Reopen}). {@link #reopen} is its
 *       inverse, taking a COMPLETED list back to ACTIVE.</li>
 * </ul>
 *
 * <p>Every entry re-checks that the list is still an editable ACTIVE list the user may edit (OWNER/EDITOR,
 * TK-192), so a replayed or stale button degrades to a friendly toast rather than an exception. Mutations
 * and their permission/lifecycle invariants live in the {@code list} package; this service only renders
 * and orchestrates.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CloseListService {

    public static final String START_PREFIX = "cl:start:";
    public static final String CANCEL_PREFIX = "cl:cancel:";
    public static final String CONFIRM_PREFIX = "cl:confirm:";
    public static final String SAVE_PREFIX = "cl:save:";
    public static final String MOVE_PREFIX = "cl:move:";
    public static final String MOVE_TO_PREFIX = "cl:moveto:";
    public static final String DROP_PREFIX = "cl:drop:";
    public static final String DROP_CONFIRM_PREFIX = "cl:dropok:";
    public static final String REOPEN_PREFIX = "cl:reopen:";

    private static final String DENIED = "Only owners and editors can close a list.";
    private static final String ALREADY_CLOSED = "This list is already closed.";

    private final ListService listService;
    private final TaskService taskService;
    private final ListClosingService closingService;
    private final ListLifecycleService lifecycleService;
    private final ListPermissionService permissionService;
    private final ConversationStateService conversationState;
    private final ListViewService listViewService;
    private final TelegramMessageGateway gateway;

    /**
     * Renders the close screen for an ACTIVE list into {@code messageId} and sets the user's state to
     * {@link ConversationState.ClosingList}. Returns the list name when shown; empty when the list is gone,
     * not currently ACTIVE, or the user may not edit it (the handler turns that into a toast).
     */
    public Optional<String> start(final User user, final int messageId, final UUID listId) {
        final Optional<TaskList> list = editableActiveList(user, listId);
        if (list.isEmpty()) {
            return Optional.empty();
        }
        conversationState.setState(user.getId(), new ConversationState.ClosingList(listId));
        final TaskService.Counts counts = taskService.counts(listId);
        final long unfinished = counts.total() - counts.done();
        if (unfinished == 0) {
            renderConfirmAllDone(user, messageId, list.get(), counts.total());
        } else {
            renderOptions(user, messageId, list.get(), unfinished);
        }
        return Optional.of(list.get().getName());
    }

    /**
     * Renders the "move unfinished items to:" picker — the user's other active lists — and sets the state
     * to {@link ConversationState.ClosingListTarget}. Empty when the list is gone or not editable.
     */
    public Optional<String> showMovePicker(final User user, final int messageId, final UUID listId) {
        final Optional<TaskList> list = editableActiveList(user, listId);
        if (list.isEmpty()) {
            return Optional.empty();
        }
        conversationState.setState(user.getId(), new ConversationState.ClosingListTarget(listId));
        final List<TaskList> targets = lifecycleService.findActiveByOwner(Objects.requireNonNull(user.getId())).stream()
                .filter(candidate -> !candidate.getId().equals(listId))
                .toList();
        gateway.editMarkdown(user.getTgChatId(), messageId, movePickerBody(list.get(), targets),
                movePickerKeyboard(listId, targets));
        return Optional.of(list.get().getName());
    }

    /** Renders the "drop unfinished items?" confirmation, in place. Empty when gone or not editable. */
    public Optional<String> showDropConfirm(final User user, final int messageId, final UUID listId) {
        final Optional<TaskList> list = editableActiveList(user, listId);
        if (list.isEmpty()) {
            return Optional.empty();
        }
        conversationState.setState(user.getId(), new ConversationState.ClosingList(listId));
        final long unfinished = closingService.unfinishedTasks(listId).size();
        final String text = ListRenderer.escape(("🗑 Drop %d unfinished item%s and close \"%s\"?\n\n"
                + "They stay on the closed list but won't be carried over.")
                .formatted(unfinished, unfinished == 1 ? "" : "s", list.get().getName()));
        gateway.editMarkdown(user.getTgChatId(), messageId, text, confirmKeyboard(
                "🗑 Yes, drop & close", DROP_CONFIRM_PREFIX + listId, listId));
        return Optional.of(list.get().getName());
    }

    /** Completes a list whose items are all done. Returns the toast to show; empty when the list is gone. */
    public Optional<String> confirmClose(final User user, final int messageId, final UUID listId) {
        return execute(user, messageId, listId, () -> lifecycleService.markCompleted(user.getId(), listId),
                "✅ List closed");
    }

    /** Carries unfinished items into the pending bucket, then completes the list. */
    public Optional<String> saveForLater(final User user, final int messageId, final UUID listId) {
        final int count = closingService.unfinishedTasks(listId).size();
        return execute(user, messageId, listId, () -> closingService.closeSavingForLater(user.getId(), listId),
                "📌 Saved %d item%s for later. View in 📥 Pending".formatted(count, count == 1 ? "" : "s"));
    }

    /** Moves unfinished items into {@code targetListId}, then completes the source list. */
    public Optional<String> moveTo(final User user, final int messageId, final UUID listId, final UUID targetListId) {
        final Optional<TaskList> target = listService.getActiveById(targetListId)
                .filter(candidate -> candidate.getStatus() == ListStatus.ACTIVE);
        if (target.isEmpty()) {
            return Optional.of("That list is no longer available.");
        }
        final int count = closingService.unfinishedTasks(listId).size();
        return execute(user, messageId, listId,
                () -> closingService.closeMovingTo(user.getId(), listId, targetListId),
                "➡️ Moved %d item%s to \"%s\"".formatted(count, count == 1 ? "" : "s", target.get().getName()));
    }

    /** Drops unfinished items (leaves them on the closed list) and completes it. */
    public Optional<String> drop(final User user, final int messageId, final UUID listId) {
        return execute(user, messageId, listId, () -> lifecycleService.markCompleted(user.getId(), listId),
                "✅ List closed");
    }

    /** Cancels the flow, returning to the list view in place. */
    public Optional<String> cancel(final User user, final int messageId, final UUID listId) {
        return listViewService.show(user, messageId, listId, 0).map(name -> "Closing cancelled");
    }

    /**
     * Reopens a COMPLETED list back to ACTIVE and re-renders the (now editable) list view in place.
     * Returns the toast; empty when the list is gone, and a soft message when it is not closed or the user
     * may not edit it.
     */
    public Optional<String> reopen(final User user, final int messageId, final UUID listId) {
        final Optional<TaskList> list = listService.getActiveById(listId);
        if (list.isEmpty()) {
            return Optional.empty();
        }
        if (!permissionService.canEditList(user.getId(), listId)) {
            return Optional.of("Only owners and editors can reopen a list.");
        }
        if (list.get().getStatus() != ListStatus.COMPLETED) {
            return Optional.of("This list is already open.");
        }
        lifecycleService.reopen(user.getId(), listId);
        return listViewService.show(user, messageId, listId, 0)
                .map(name -> "🔄 List reopened")
                .or(() -> Optional.empty());
    }

    private Optional<String> execute(final User user, final int messageId, final UUID listId,
            final Runnable mutation, final String successToast) {
        final Optional<TaskList> list = listService.getActiveById(listId);
        if (list.isEmpty()) {
            return Optional.empty();
        }
        if (!permissionService.canEditList(user.getId(), listId)) {
            return Optional.of(DENIED);
        }
        if (list.get().getStatus() != ListStatus.ACTIVE) {
            return Optional.of(ALREADY_CLOSED);
        }
        mutation.run();
        return listViewService.show(user, messageId, listId, 0)
                .map(name -> successToast)
                .or(() -> Optional.empty());
    }

    private Optional<TaskList> editableActiveList(final User user, final UUID listId) {
        return listService.getActiveById(listId)
                .filter(list -> list.getStatus() == ListStatus.ACTIVE)
                .filter(list -> permissionService.canEditList(user.getId(), listId));
    }

    private void renderConfirmAllDone(final User user, final int messageId, final TaskList list, final long total) {
        final String text = ListRenderer.escape(("🏁 Close \"%s\"?\n\nAll %d item%s are done.")
                .formatted(list.getName(), total, total == 1 ? "" : "s"));
        gateway.editMarkdown(user.getTgChatId(), messageId, text, confirmKeyboard(
                "✅ Close list", CONFIRM_PREFIX + list.getId(), Objects.requireNonNull(list.getId())));
    }

    private void renderOptions(final User user, final int messageId, final TaskList list, final long unfinished) {
        final UUID listId = Objects.requireNonNull(list.getId());
        final String text = ListRenderer.escape(("🏁 Close \"%s\"?\n\n%d item%s not done — what should happen"
                + " to them?").formatted(list.getName(), unfinished, unfinished == 1 ? " is" : "s are"));
        final List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(row(button("📌 Save for later", SAVE_PREFIX + listId)));
        rows.add(row(button("➡️ Move to another list", MOVE_PREFIX + listId)));
        rows.add(row(button("🗑 Drop & close", DROP_PREFIX + listId)));
        rows.add(row(button("❌ Cancel", CANCEL_PREFIX + listId)));
        gateway.editMarkdown(user.getTgChatId(), messageId, text, InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private String movePickerBody(final TaskList list, final List<TaskList> targets) {
        if (targets.isEmpty()) {
            return ListRenderer.escape(("No other active list to move items into — create one first, or go"
                    + " back to choose another option for \"%s\".").formatted(list.getName()));
        }
        return ListRenderer.escape("➡️ Move unfinished items from \"%s\" to:".formatted(list.getName()));
    }

    private InlineKeyboardMarkup movePickerKeyboard(final UUID listId, final List<TaskList> targets) {
        final List<InlineKeyboardRow> rows = new ArrayList<>();
        for (final TaskList target : targets) {
            rows.add(row(button("%s %s".formatted(ListIcons.of(target.getType()), target.getName()),
                    MOVE_TO_PREFIX + CompactUuid.encode(listId) + ":"
                            + CompactUuid.encode(Objects.requireNonNull(target.getId())))));
        }
        rows.add(row(button("⬅️ Back", START_PREFIX + listId)));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private InlineKeyboardMarkup confirmKeyboard(final String yesLabel, final String yesData, final UUID listId) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(row(button(yesLabel, yesData), button("❌ Cancel", CANCEL_PREFIX + listId)))
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

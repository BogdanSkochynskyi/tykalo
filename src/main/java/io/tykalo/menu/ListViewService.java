package io.tykalo.menu;

import io.tykalo.list.ListPermissionService;
import io.tykalo.list.ListRenderer;
import io.tykalo.list.ListService;
import io.tykalo.list.ListStatus;
import io.tykalo.list.Task;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskService;
import io.tykalo.list.TaskStatus;
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
 * Renders the list view (TK-183) — the primary working screen for a single list. The header is the
 * list's type icon and name; the body lists its live items with a ✅/☐ indicator, paged at
 * {@link #PAGE_SIZE}; the keyboard is one row per item — its toggle button plus, for a still-actionable
 * item, a 📌 Save-for-later button (TK-256) — followed by action, settings and navigation rows. Showing
 * it edits the navigable menu message in place and sets the user's {@link ConversationState} to
 * {@link ConversationState.ListView}.
 *
 * <p>Toggling carries the current page in its {@code callback_data} ({@code lv:done|undo:{task}:{page}})
 * so the view re-renders the same page in place — see {@link io.tykalo.menu.handler.ListViewCallbackHandler}.
 * The underlying {@code markDone}/{@code reopen} still fire {@code ListChangedEvent}, so any TK-B001
 * auto live message for the list stays in sync; this screen is the new primary surface, not a second
 * source of truth.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ListViewService {

    public static final String DONE_PREFIX = "lv:done:";
    public static final String UNDO_PREFIX = "lv:undo:";
    public static final String SAVE_PREFIX = "lv:save:";
    public static final String PAGE_PREFIX = "lv:page:";
    public static final String ADD_PREFIX = "lv:add:";
    public static final String MEMBERS_PREFIX = "lv:members:";
    public static final String MORE_PREFIX = "lv:more:";
    public static final String BACK = "lv:lists";

    static final int PAGE_SIZE = 20;
    private static final int BUTTON_TITLE_MAX = 24;

    private final ListService listService;
    private final TaskService taskService;
    private final ListPermissionService permissionService;
    private final ConversationStateService conversationState;
    private final TelegramMessageGateway gateway;

    /** Opens the list view at the first page. See {@link #show(User, int, UUID, int)}. */
    public Optional<String> open(final User user, final int messageId, final UUID listId) {
        return show(user, messageId, listId, 0);
    }

    /**
     * Re-renders the list view showing its last page, where freshly appended items land — used by the
     * add-items flow (TK-184) so the just-added item is visible. {@link #show} clamps the page to the
     * last one.
     */
    public Optional<String> showLastPage(final User user, final int messageId, final UUID listId) {
        return show(user, messageId, listId, Integer.MAX_VALUE);
    }

    /**
     * Renders the list view for {@code listId} at {@code page} into {@code messageId}, in place, and
     * sets the user's state to {@code ListView}. Returns the list name when shown, or empty if the
     * list no longer exists (archived/deleted) — the caller turns that into a toast.
     */
    public Optional<String> show(final User user, final int messageId, final UUID listId, final int page) {
        final Optional<TaskList> list = listService.getActiveById(listId);
        if (list.isEmpty()) {
            return Optional.empty();
        }
        conversationState.setState(user.getId(), new ConversationState.ListView(listId));
        final boolean canEdit = permissionService.canEditList(user.getId(), listId);
        final Screen screen = render(list.get(), page, canEdit);
        gateway.editMarkdown(user.getTgChatId(), messageId, screen.text(), screen.keyboard());
        log.debug("Showed list view list={} page={} to user id={}", listId, page, user.getId());
        return Optional.of(list.get().getName());
    }

    private Screen render(final TaskList list, final int requestedPage, final boolean canEdit) {
        final List<Task> tasks = taskService.activeTasks(Objects.requireNonNull(list.getId()));
        final int pageCount = Math.max(1, (tasks.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        final int page = Math.clamp(requestedPage, 0, pageCount - 1);
        final int from = page * PAGE_SIZE;
        final List<Task> pageTasks = tasks.subList(from, Math.min(from + PAGE_SIZE, tasks.size()));
        return new Screen(body(list, pageTasks, page, pageCount),
                keyboard(list, pageTasks, page, pageCount, canEdit));
    }

    private String body(final TaskList list, final List<Task> tasks, final int page, final int pageCount) {
        final StringBuilder out = new StringBuilder("%s %s\n".formatted(ListIcons.of(list.getType()), list.getName()));
        if (list.getStatus() == ListStatus.COMPLETED) {
            out.append("✅ Completed — read-only. Reopen to make changes.\n");
        }
        if (tasks.isEmpty()) {
            out.append("\nNo items yet. Tap ➕ Add items to start.");
        } else {
            for (final Task task : tasks) {
                out.append("\n%s %s".formatted(indicator(task), task.getTitle()));
            }
        }
        if (pageCount > 1) {
            out.append("\n\nPage %d/%d".formatted(page + 1, pageCount));
        }
        return ListRenderer.escape(out.toString());
    }

    private InlineKeyboardMarkup keyboard(final TaskList list, final List<Task> tasks,
                                          final int page, final int pageCount, final boolean canEdit) {
        final UUID listId = Objects.requireNonNull(list.getId());
        if (list.getStatus() == ListStatus.COMPLETED) {
            return completedKeyboard(listId, page, pageCount, canEdit);
        }
        final List<InlineKeyboardRow> rows = new ArrayList<>();
        for (final Task task : tasks) {
            rows.add(taskRow(task, page));
        }
        if (pageCount > 1) {
            rows.add(paginationRow(listId, page, pageCount));
        }
        rows.add(row(button("➕ Add items", ADD_PREFIX + listId)));
        rows.add(row(button("👥 Members", MEMBERS_PREFIX + listId), button("⋯ More", MORE_PREFIX + listId)));
        if (canEdit) {
            rows.add(row(button("🏁 Close list", CloseListService.START_PREFIX + listId)));
        }
        rows.add(row(button("⬅️ Back to lists", BACK)));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /**
     * The keyboard for a COMPLETED list (TK-254): read-only — no per-item toggles, no add/members — with
     * just paging, a {@code 🔄 Reopen} for editors, and Back. Items are still shown in the body.
     */
    private InlineKeyboardMarkup completedKeyboard(final UUID listId, final int page, final int pageCount,
                                                   final boolean canEdit) {
        final List<InlineKeyboardRow> rows = new ArrayList<>();
        if (pageCount > 1) {
            rows.add(paginationRow(listId, page, pageCount));
        }
        if (canEdit) {
            rows.add(row(button("🔄 Reopen", CloseListService.REOPEN_PREFIX + listId)));
        }
        rows.add(row(button("⬅️ Back to lists", BACK)));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /**
     * One row per task: the toggle button spanning the row, and — for a still-actionable (TODO) item —
     * a {@code 📌} Save-for-later button (TK-256). A DONE item gets no 📌: deferring a completed task is
     * meaningless, so its row is just the toggle.
     */
    private InlineKeyboardRow taskRow(final Task task, final int page) {
        final UUID id = Objects.requireNonNull(task.getId(), "task must be persisted before rendering");
        final InlineKeyboardRow row = row(toggleButton(task, id, page));
        if (task.getStatus() != TaskStatus.DONE) {
            row.add(button("📌", SAVE_PREFIX + id + ":" + page));
        }
        return row;
    }

    private InlineKeyboardButton toggleButton(final Task task, final UUID id, final int page) {
        final String label = "%s %s".formatted(indicator(task), truncate(task.getTitle()));
        final String action = task.getStatus() == TaskStatus.DONE ? UNDO_PREFIX : DONE_PREFIX;
        return button(label, action + id + ":" + page);
    }

    private InlineKeyboardRow paginationRow(final UUID listId, final int page, final int pageCount) {
        final InlineKeyboardRow row = new InlineKeyboardRow();
        if (page > 0) {
            row.add(button("⬅️ Prev", PAGE_PREFIX + listId + ":" + (page - 1)));
        }
        if (page < pageCount - 1) {
            row.add(button("Next ➡️", PAGE_PREFIX + listId + ":" + (page + 1)));
        }
        return row;
    }

    private String indicator(final Task task) {
        return task.getStatus() == TaskStatus.DONE ? "✅" : "☐";
    }

    private String truncate(final String title) {
        return title.length() <= BUTTON_TITLE_MAX ? title : title.substring(0, BUTTON_TITLE_MAX - 1) + "…";
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

    private record Screen(String text, InlineKeyboardMarkup keyboard) {
    }
}

package io.tykalo.menu;

import io.tykalo.list.ListPermissionService;
import io.tykalo.list.ListRenderer;
import io.tykalo.list.ListService;
import io.tykalo.list.ListStatus;
import io.tykalo.list.TaskList;
import io.tykalo.telegram.TelegramMessageGateway;
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
 * A per-list settings screen, reached from the list view's {@code ⋯ More} button (TK-183). It hosts the
 * {@code 🤖 Auto-close when done} toggle (TK-253) and a {@code 🏷️ Tags} button into the tags screen
 * ({@link TagsService}, TK-259), plus a Back button; the remaining controls (rename, type, members,
 * delete) land with TK-186, which will expand this screen.
 *
 * <p>Settings are an OWNER/EDITOR action: the screen is only rendered for editors of an ACTIVE list, so the
 * underlying {@link ListService#setAutoClose} permission check (TK-192) never has to reject a tap that came
 * from this screen. Everything edits the list-view message in place, mirroring {@link CloseListService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ListSettingsService {

    public static final String TOGGLE_AUTO_CLOSE_PREFIX = "ls:ac:";
    public static final String BACK_PREFIX = "ls:back:";

    private final ListService listService;
    private final ListPermissionService permissionService;
    private final ListViewService listViewService;
    private final TelegramMessageGateway gateway;

    /** Renders the settings screen for an editable ACTIVE list in place. Empty when gone or not editable. */
    public Optional<String> open(final User user, final int messageId, final UUID listId) {
        final Optional<TaskList> list = editableActiveList(user, listId);
        if (list.isEmpty()) {
            return Optional.empty();
        }
        render(user, messageId, list.get());
        return Optional.of(list.get().getName());
    }

    /** Flips the list's auto-close setting and re-renders the screen so the toggle's label updates. */
    public Optional<String> toggleAutoClose(final User user, final int messageId, final UUID listId) {
        final Optional<TaskList> list = editableActiveList(user, listId);
        if (list.isEmpty()) {
            return Optional.empty();
        }
        final TaskList updated = listService.setAutoClose(user.getId(), listId, !list.get().isAutoClose());
        render(user, messageId, updated);
        return Optional.of(updated.isAutoClose() ? "🤖 Auto-close on" : "🤖 Auto-close off");
    }

    /** Returns to the list view from the settings screen, in place. */
    public Optional<String> back(final User user, final int messageId, final UUID listId) {
        return listViewService.show(user, messageId, listId, 0);
    }

    private void render(final User user, final int messageId, final TaskList list) {
        final boolean on = list.isAutoClose();
        final String text = ListRenderer.escape(("""
                ⚙️ Settings — %s

                🤖 Auto-close when done: %s
                When on, I close this list automatically once every item is done. When off, I ask first.""")
                .formatted(list.getName(), on ? "ON" : "OFF"));
        gateway.editMarkdown(user.getTgChatId(), messageId, text, keyboard(list));
    }

    private InlineKeyboardMarkup keyboard(final TaskList list) {
        final UUID listId = Objects.requireNonNull(list.getId());
        final String label = list.isAutoClose() ? "🤖 Auto-close: ON → turn off" : "🤖 Auto-close: OFF → turn on";
        final List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(row(button(label, TOGGLE_AUTO_CLOSE_PREFIX + listId)));
        rows.add(row(button("🏷️ Tags", TagsService.OPEN_PREFIX + listId)));
        rows.add(row(button("⬅️ Back", BACK_PREFIX + listId)));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
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

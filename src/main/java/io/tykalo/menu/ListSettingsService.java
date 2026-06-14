package io.tykalo.menu;

import io.tykalo.list.ListRenderer;
import io.tykalo.list.ListService;
import io.tykalo.list.ListType;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskService;
import io.tykalo.nudger.TaskNudgerRepository;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.ConversationStateService;
import io.tykalo.user.User;
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
 * The list-settings submenu (TK-186), reached from {@code ⋯ More} on the list view (TK-183). It is the
 * per-list management screen — rename, change type, archive, delete — and like the other menu screens
 * runs as in-place edits to one message, keeping the user's {@link ConversationState} on
 * {@link ConversationState.ListSettings}.
 *
 * <ul>
 *   <li><b>Rename</b> ({@code set:rename}) edits the message into a prompt and sets
 *       {@link ConversationState.RenamingList} (carrying the prompt's id); the next plain-text message is
 *       consumed by {@link io.tykalo.menu.handler.RenameListStateHandler} → {@link #submitRename}.</li>
 *   <li><b>Change type</b> ({@code set:type} → {@code set:settype:{list}:{TYPE}}) re-shows a type picker
 *       and updates the list's type, guarding the case of leaving PROJECT while its tasks still have
 *       Nudgers.</li>
 *   <li><b>Archive</b> ({@code set:archive}) and <b>Delete</b> ({@code set:delete} → confirm →
 *       {@code set:delok}) both soft-delete (set {@code archived_at}) per the project's soft-delete
 *       invariant — Archive immediately, Delete behind a 2-step confirm — and return to the My Lists
 *       screen.</li>
 * </ul>
 *
 * <p>Permission checks are a placeholder until TK-192: the viewer is the list owner in the current
 * single-user model, so {@link #denyIfNotOwner} enforces ownership and a later {@code ListPermissionService}
 * will generalise it to OWNER/EDITOR/MEMBER. Buttons carry the {@code set:} {@code callback_data} prefix
 * (see {@link io.tykalo.menu.handler.ListSettingsCallbackHandler}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ListSettingsService {

    public static final String RENAME_PREFIX = "set:rename:";
    public static final String TYPE_PREFIX = "set:type:";
    public static final String SET_TYPE_PREFIX = "set:settype:";
    public static final String ARCHIVE_PREFIX = "set:archive:";
    public static final String DELETE_PREFIX = "set:delete:";
    public static final String DELETE_OK_PREFIX = "set:delok:";
    public static final String MENU_PREFIX = "set:menu:";
    public static final String BACK_PREFIX = "set:back:";

    static final String LIST_GONE = "That list is no longer available.";
    private static final String NO_PERMISSION = "You don't have permission to change this list.";

    private final ListService listService;
    private final TaskService taskService;
    private final TaskNudgerRepository taskNudgerRepository;
    private final ConversationStateService conversationState;
    private final MyListsService myListsService;
    private final TelegramMessageGateway gateway;

    /** Opens (or re-renders) the settings screen in place and sets the {@code ListSettings} state. */
    public Optional<String> open(final User user, final int messageId, final UUID listId) {
        final Optional<TaskList> list = listService.getActiveById(listId);
        if (list.isEmpty()) {
            return Optional.empty();
        }
        conversationState.setState(user.getId(), new ConversationState.ListSettings(listId));
        final Screen screen = settingsScreen(list.get());
        gateway.editMarkdown(user.getTgChatId(), messageId, screen.text(), screen.keyboard());
        log.debug("Showed list settings list={} to user id={}", listId, user.getId());
        return Optional.of(list.get().getName());
    }

    /** Rename step 1: shows the name prompt and sets {@link ConversationState.RenamingList}. */
    public Optional<String> startRename(final User user, final int messageId, final UUID listId) {
        final Optional<TaskList> list = listService.getActiveById(listId);
        if (list.isEmpty()) {
            return Optional.of(LIST_GONE);
        }
        final Optional<String> denied = denyIfNotOwner(user, list.get());
        if (denied.isPresent()) {
            return denied;
        }
        conversationState.setState(user.getId(), new ConversationState.RenamingList(listId, messageId));
        final String text = ListRenderer.escape("✏️ Send the new name for \"%s\":".formatted(list.get().getName()));
        gateway.editMarkdown(user.getTgChatId(), messageId, text, oneButton("❌ Cancel", MENU_PREFIX + listId));
        return Optional.of("✏️ Rename");
    }

    /**
     * Rename step 2: validates the typed name and, on success, renames and re-renders the settings screen.
     * A blank or duplicate name re-prompts the same message in place and keeps the renaming state. Silent
     * (the edited message is the feedback).
     */
    public Optional<String> submitRename(final User user, final ConversationState.RenamingList state,
                                         final String name) {
        final Optional<TaskList> list = listService.getActiveById(state.listId());
        if (list.isEmpty()) {
            conversationState.setState(user.getId(), new ConversationState.Idle());
            return Optional.of(LIST_GONE);
        }
        final String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty()) {
            reprompt(user, state, "⚠️ A list name can't be blank.", list.get().getName());
            return Optional.empty();
        }
        if (trimmed.equalsIgnoreCase(list.get().getName())) {
            open(user, state.promptMessageId(), state.listId());
            return Optional.empty();
        }
        if (listService.findActiveByName(user.getId(), trimmed).isPresent()) {
            reprompt(user, state, "⚠️ You already have a list named \"%s\".".formatted(trimmed), list.get().getName());
            return Optional.empty();
        }
        listService.rename(state.listId(), trimmed);
        open(user, state.promptMessageId(), state.listId());
        log.debug("Renamed list={} for user id={}", state.listId(), user.getId());
        return Optional.empty();
    }

    /** Change-type step 1: shows the type picker (stays in the {@code ListSettings} state). */
    public Optional<String> showTypePicker(final User user, final int messageId, final UUID listId) {
        final Optional<TaskList> list = listService.getActiveById(listId);
        if (list.isEmpty()) {
            return Optional.of(LIST_GONE);
        }
        final Optional<String> denied = denyIfNotOwner(user, list.get());
        if (denied.isPresent()) {
            return denied;
        }
        final String text = ListRenderer.escape("🔄 Choose a new type for \"%s\":".formatted(list.get().getName()));
        gateway.editMarkdown(user.getTgChatId(), messageId, text, typePicker(listId));
        return Optional.of("🔄 Change type");
    }

    /**
     * Change-type step 2: applies the new type and re-renders the settings screen. Blocked (with a toast,
     * no change) when leaving PROJECT while the list's tasks still have Nudgers assigned.
     */
    public Optional<String> changeType(final User user, final int messageId, final UUID listId,
                                       final ListType newType) {
        final Optional<TaskList> list = listService.getActiveById(listId);
        if (list.isEmpty()) {
            return Optional.of(LIST_GONE);
        }
        final Optional<String> denied = denyIfNotOwner(user, list.get());
        if (denied.isPresent()) {
            return denied;
        }
        if (list.get().getType() == newType) {
            open(user, messageId, listId);
            return Optional.of("Already a " + newType + " list");
        }
        if (list.get().getType() == ListType.PROJECT && hasTaskNudgers(listId)) {
            return Optional.of("Remove Nudgers from this list's tasks before changing its type.");
        }
        listService.changeType(listId, newType);
        open(user, messageId, listId);
        log.debug("Changed type of list={} to {} for user id={}", listId, newType, user.getId());
        return Optional.of("🔄 Now a " + newType + " list");
    }

    /** Archive: soft-deletes immediately and returns to the My Lists screen. */
    public Optional<String> archive(final User user, final int messageId, final UUID listId) {
        return softDelete(user, messageId, listId, "📦 Archived");
    }

    /** Delete step 1: shows the 2-step confirmation screen. */
    public Optional<String> confirmDelete(final User user, final int messageId, final UUID listId) {
        final Optional<TaskList> list = listService.getActiveById(listId);
        if (list.isEmpty()) {
            return Optional.of(LIST_GONE);
        }
        final Optional<String> denied = denyIfNotOwner(user, list.get());
        if (denied.isPresent()) {
            return denied;
        }
        final long count = taskService.countActiveTasks(listId);
        final String text = ListRenderer.escape(
                "🗑️ Delete \"%s\" (%d %s)?\nThis removes it from your lists."
                        .formatted(list.get().getName(), count, count == 1 ? "item" : "items"));
        final InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(row(button("🗑️ Yes, delete", DELETE_OK_PREFIX + listId)))
                .keyboardRow(row(button("⬅️ Cancel", MENU_PREFIX + listId)))
                .build();
        gateway.editMarkdown(user.getTgChatId(), messageId, text, keyboard);
        return Optional.of("🗑️ Confirm delete");
    }

    /** Delete step 2 (confirmed): soft-deletes and returns to the My Lists screen. */
    public Optional<String> delete(final User user, final int messageId, final UUID listId) {
        return softDelete(user, messageId, listId, "🗑️ Deleted");
    }

    private Optional<String> softDelete(final User user, final int messageId, final UUID listId,
                                        final String toast) {
        final Optional<TaskList> list = listService.getActiveById(listId);
        if (list.isEmpty()) {
            return Optional.of(LIST_GONE);
        }
        final Optional<String> denied = denyIfNotOwner(user, list.get());
        if (denied.isPresent()) {
            return denied;
        }
        listService.deleteList(listId);
        myListsService.navigate(user, messageId, 0);
        log.debug("Soft-deleted list={} for user id={}", listId, user.getId());
        return Optional.of(toast);
    }

    private boolean hasTaskNudgers(final UUID listId) {
        final List<UUID> taskIds = taskService.activeTasks(listId).stream()
                .map(task -> Objects.requireNonNull(task.getId()))
                .toList();
        return !taskIds.isEmpty() && !taskNudgerRepository.findByTaskIdIn(taskIds).isEmpty();
    }

    private Optional<String> denyIfNotOwner(final User user, final TaskList list) {
        // TODO(TK-192): replace with ListPermissionService (OWNER+EDITOR for edits, OWNER for delete).
        if (!list.getOwnerId().equals(user.getId())) {
            return Optional.of(NO_PERMISSION);
        }
        return Optional.empty();
    }

    private void reprompt(final User user, final ConversationState.RenamingList state, final String notice,
                          final String currentName) {
        final String text = ListRenderer.escape(
                "%s\n✏️ Send the new name for \"%s\":".formatted(notice, currentName));
        gateway.editMarkdown(user.getTgChatId(), state.promptMessageId(), text,
                oneButton("❌ Cancel", MENU_PREFIX + state.listId()));
    }

    private Screen settingsScreen(final TaskList list) {
        final String text = ListRenderer.escape(
                "⚙️ %s %s\n\nList settings".formatted(ListIcons.of(list.getType()), list.getName()));
        final UUID listId = Objects.requireNonNull(list.getId());
        final InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(row(button("✏️ Rename", RENAME_PREFIX + listId)))
                .keyboardRow(row(button("🔄 Change type", TYPE_PREFIX + listId)))
                .keyboardRow(row(button("📦 Archive", ARCHIVE_PREFIX + listId)))
                .keyboardRow(row(button("🗑️ Delete", DELETE_PREFIX + listId)))
                .keyboardRow(row(button("⬅️ Back to list", BACK_PREFIX + listId)))
                .build();
        return new Screen(text, keyboard);
    }

    private InlineKeyboardMarkup typePicker(final UUID listId) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(row(button("🛒 Checklist", SET_TYPE_PREFIX + listId + ":" + ListType.CHECKLIST.name())))
                .keyboardRow(row(button("📋 Project", SET_TYPE_PREFIX + listId + ":" + ListType.PROJECT.name())))
                .keyboardRow(row(button("🔄 Routine", SET_TYPE_PREFIX + listId + ":" + ListType.ROUTINE.name())))
                .keyboardRow(row(button("⬅️ Back", MENU_PREFIX + listId)))
                .build();
    }

    private InlineKeyboardMarkup oneButton(final String text, final String callbackData) {
        return InlineKeyboardMarkup.builder().keyboardRow(row(button(text, callbackData))).build();
    }

    private InlineKeyboardRow row(final InlineKeyboardButton button) {
        final InlineKeyboardRow row = new InlineKeyboardRow();
        row.add(button);
        return row;
    }

    private InlineKeyboardButton button(final String text, final String callbackData) {
        return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
    }

    private record Screen(String text, InlineKeyboardMarkup keyboard) {
    }
}

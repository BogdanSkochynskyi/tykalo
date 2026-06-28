package io.tykalo.menu;

import io.tykalo.list.ListLifecycleService;
import io.tykalo.list.ListMember;
import io.tykalo.list.ListMemberRole;
import io.tykalo.list.ListMemberService;
import io.tykalo.list.ListRenderer;
import io.tykalo.list.ListService;
import io.tykalo.list.ListStatus;
import io.tykalo.list.ListType;
import io.tykalo.list.TaskCompletedEvent;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskService;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * Auto-close detection (TK-253). Listens for {@link TaskCompletedEvent} and, when a list's every live item
 * is now done, either closes it silently or asks its editors whether to close it — driven by the list's
 * {@code auto_close} setting:
 *
 * <ul>
 *   <li>{@code auto_close = true} → {@link ListLifecycleService#markCompleted} on the owner's behalf, with no
 *       message (the user next opening the list sees its read-only state, as with a manual close).</li>
 *   <li>{@code auto_close = false} (default) → a "All done! Close the list?" prompt ({@code ✅ Yes} /
 *       {@code 🙅 Keep open}) sent to OWNER + EDITORs only — those who may actually close it (TK-192).</li>
 * </ul>
 *
 * <p>{@code ROUTINE} and {@code INBOX} lists are skipped (routines repeat; the inbox never closes), as are
 * lists that are not currently ACTIVE. Choosing "Keep open" sets a 1h per-list suppression flag
 * ({@link AutoCloseSuppressionService}) so the prompt stops nagging. The listener runs {@code AFTER_COMMIT}
 * so the completing task — and any recurring respawn — is committed before we count, and the Telegram I/O
 * happens off the database transaction (mirroring {@link io.tykalo.list.ListMessageService}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutoCloseService {

    public static final String CLOSE_PREFIX = "ac:close:";
    public static final String KEEP_PREFIX = "ac:keep:";

    private final ListService listService;
    private final TaskService taskService;
    private final ListLifecycleService lifecycleService;
    private final ListMemberService listMemberService;
    private final CloseListService closeListService;
    private final AutoCloseSuppressionService suppressionService;
    private final UserRepository userRepository;
    private final TelegramMessageGateway gateway;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskCompleted(final TaskCompletedEvent event) {
        evaluate(event.listId());
    }

    /**
     * Decides what should happen to {@code listId} now that one of its items just completed: nothing (not
     * all done, wrong type/status), a silent close, or an editor prompt. Idempotent and side-effect-free
     * when no close is warranted, so a replayed or redundant completion event is harmless.
     */
    public void evaluate(final UUID listId) {
        final Optional<TaskList> found = listService.getActiveById(listId);
        if (found.isEmpty()) {
            return;
        }
        final TaskList list = found.get();
        if (list.getStatus() != ListStatus.ACTIVE || skipsLifecycle(list.getType())) {
            return;
        }
        final TaskService.Counts counts = taskService.counts(listId);
        if (counts.total() == 0 || counts.done() < counts.total()) {
            return;
        }
        if (list.isAutoClose()) {
            silentlyClose(list);
        } else if (!suppressionService.isSuppressed(listId)) {
            prompt(list, counts.total());
        }
    }

    /**
     * Closes {@code listId} now in answer to a "Yes" on the auto-close prompt, by reusing the close-list
     * flow's all-done confirmation (TK-254) — which closes the list and re-renders the prompt message into
     * the read-only list view. Returns the toast to show; empty when the list is gone.
     */
    public Optional<String> confirmClose(final User user, final int messageId, final UUID listId) {
        return closeListService.confirmClose(user, messageId, listId);
    }

    /**
     * Suppresses the prompt for an hour in answer to "Keep open", and edits the prompt message to a plain
     * acknowledgement (dropping its buttons). Returns the toast to show.
     */
    public Optional<String> keepOpen(final User user, final int messageId, final UUID listId) {
        suppressionService.suppress(listId);
        final String name = listService.getActiveById(listId).map(TaskList::getName).orElse("the list");
        gateway.editMarkdown(user.getTgChatId(), messageId,
                ListRenderer.escape("👍 Keeping \"%s\" open.".formatted(name)), null);
        log.info("Auto-close prompt for list {} kept open by user {}", listId, user.getId());
        return Optional.of("👍 Keeping it open");
    }

    private void silentlyClose(final TaskList list) {
        final UUID listId = Objects.requireNonNull(list.getId());
        lifecycleService.markCompleted(list.getOwnerId(), listId);
        log.info("Auto-closed list {} silently (all items done)", listId);
    }

    private void prompt(final TaskList list, final long total) {
        final UUID listId = Objects.requireNonNull(list.getId());
        final String text = ListRenderer.escape(("🎉 All %d item%s in \"%s\" are done! Close the list?")
                .formatted(total, total == 1 ? "" : "s", list.getName()));
        final InlineKeyboardRow buttons = new InlineKeyboardRow();
        buttons.add(button("✅ Yes, close", CLOSE_PREFIX + listId));
        buttons.add(button("🙅 Keep open", KEEP_PREFIX + listId));
        final InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder().keyboardRow(buttons).build();
        int prompted = 0;
        for (final User recipient : recipients(list)) {
            gateway.sendMarkdown(recipient.getTgChatId(), text, keyboard);
            prompted++;
        }
        log.info("Prompted {} editor(s) to close all-done list {}", prompted, listId);
    }

    /** OWNER + EDITORs of the list (deduped, owner first) — the users who may close it. */
    private Set<User> recipients(final TaskList list) {
        final UUID listId = Objects.requireNonNull(list.getId());
        final LinkedHashSet<UUID> userIds = new LinkedHashSet<>();
        userIds.add(list.getOwnerId());
        for (final ListMember member : listMemberService.activeMembers(listId)) {
            if (member.getRole() == ListMemberRole.OWNER || member.getRole() == ListMemberRole.EDITOR) {
                userIds.add(member.getUserId());
            }
        }
        final LinkedHashSet<User> users = new LinkedHashSet<>();
        for (final UUID userId : userIds) {
            userRepository.findById(userId).ifPresent(users::add);
        }
        return users;
    }

    private boolean skipsLifecycle(final ListType type) {
        return type == ListType.ROUTINE || type == ListType.INBOX;
    }

    private InlineKeyboardButton button(final String text, final String callbackData) {
        return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
    }
}

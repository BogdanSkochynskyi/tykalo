package io.tykalo.nudger.handler;

import io.tykalo.list.Task;
import io.tykalo.list.TaskService;
import io.tykalo.nudger.Nudger;
import io.tykalo.nudger.NudgerRepository;
import io.tykalo.nudger.TaskNudgerProposalService;
import io.tykalo.nudger.TaskNudgerService;
import io.tykalo.telegram.CallbackHandler;
import io.tykalo.telegram.CompactUuid;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;

/**
 * Handles the per-task Nudger picker sent on Project-task creation (TK-158). The buttons carry
 * {@link CompactUuid}-packed ids:
 *
 * <ul>
 *   <li>{@code tn:a:{task}:{nudger}} — pin one Nudger to the task;</li>
 *   <li>{@code tn:p:{task}} — make the task private (no escalation);</li>
 *   <li>{@code tn:u:{task}} — use the owner's whole active set (the default).</li>
 * </ul>
 *
 * <p>Every action re-resolves the clicking user from the chat and confirms they own the task (and, for
 * an assignment, the Nudger), so a stale or crafted callback cannot touch someone else's task. The
 * underlying {@link TaskNudgerService} operations are idempotent, so a double tap is harmless. Callbacks
 * that are not a {@code tn:} action are left unclaimed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaskNudgerCallbackHandler implements CallbackHandler {

    private final TaskNudgerService taskNudgerService;
    private final TaskService taskService;
    private final NudgerRepository nudgerRepository;
    private final UserRepository userRepository;

    @Override
    public Optional<String> handle(final CallbackQuery callback) {
        final String data = callback.getData();
        if (data == null) {
            return Optional.empty();
        }
        if (data.startsWith(TaskNudgerProposalService.ASSIGN_PREFIX)) {
            return assign(data.substring(TaskNudgerProposalService.ASSIGN_PREFIX.length()), callback);
        }
        if (data.startsWith(TaskNudgerProposalService.PRIVATE_PREFIX)) {
            return choose(data.substring(TaskNudgerProposalService.PRIVATE_PREFIX.length()), callback, Choice.PRIVATE);
        }
        if (data.startsWith(TaskNudgerProposalService.DEFAULT_PREFIX)) {
            return choose(data.substring(TaskNudgerProposalService.DEFAULT_PREFIX.length()), callback, Choice.DEFAULT);
        }
        return Optional.empty();
    }

    private enum Choice { PRIVATE, DEFAULT }

    private Optional<String> assign(final String payload, final CallbackQuery callback) {
        final String[] parts = payload.split(":", 2);
        if (parts.length != 2) {
            return Optional.of("Unknown task");
        }
        final Optional<UUID> taskId = CompactUuid.decode(parts[0]);
        final Optional<UUID> nudgerId = CompactUuid.decode(parts[1]);
        if (taskId.isEmpty() || nudgerId.isEmpty()) {
            log.warn("Ignoring task-nudger assign callback with unparseable ids: {}", callback.getData());
            return Optional.of("Unknown task");
        }
        final OwnedTask owned = ownedTask(taskId.get(), callback);
        if (owned.error() != null) {
            return Optional.of(owned.error());
        }
        final User owner = Objects.requireNonNull(owned.owner());
        final Optional<Nudger> nudger = nudgerRepository.findById(nudgerId.get());
        if (nudger.isEmpty() || !nudger.get().getOwnerId().equals(owner.getId())) {
            return Optional.of("That isn't one of your Nudgers.");
        }
        taskNudgerService.assignNudger(taskId.get(), nudgerId.get());
        return Optional.of("✅ @%s will nudge you about this.".formatted(nudgerUsername(nudger.get())));
    }

    private Optional<String> choose(final String payload, final CallbackQuery callback, final Choice choice) {
        final Optional<UUID> taskId = CompactUuid.decode(payload);
        if (taskId.isEmpty()) {
            log.warn("Ignoring task-nudger callback with unparseable id: {}", callback.getData());
            return Optional.of("Unknown task");
        }
        final OwnedTask owned = ownedTask(taskId.get(), callback);
        if (owned.error() != null) {
            return Optional.of(owned.error());
        }
        if (choice == Choice.PRIVATE) {
            taskNudgerService.makePrivate(taskId.get());
            return Optional.of("🔒 Private — no Nudgers will see this task.");
        }
        taskNudgerService.useDefault(taskId.get());
        return Optional.of("👥 All your active Nudgers will nudge you about this.");
    }

    private OwnedTask ownedTask(final UUID taskId, final CallbackQuery callback) {
        final Long chatId = chatIdOf(callback);
        if (chatId == null) {
            return OwnedTask.error("This button has expired.");
        }
        final Optional<User> user = userRepository.findByTgChatId(chatId);
        final Optional<Task> task = taskService.find(taskId);
        if (task.isEmpty() || task.get().getArchivedAt() != null) {
            return OwnedTask.error("Task not found.");
        }
        if (user.isEmpty() || !task.get().getOwnerId().equals(user.get().getId())) {
            return OwnedTask.error("That task isn't yours.");
        }
        return OwnedTask.ok(user.get());
    }

    private String nudgerUsername(final Nudger nudger) {
        return userRepository.findById(nudger.getNudgerUserId())
                .map(User::getTgUsername)
                .orElse("them");
    }

    private @Nullable Long chatIdOf(final CallbackQuery callback) {
        final MaybeInaccessibleMessage message = callback.getMessage();
        return message == null ? null : message.getChatId();
    }

    private record OwnedTask(@Nullable User owner, @Nullable String error) {

        static OwnedTask ok(final User owner) {
            return new OwnedTask(owner, null);
        }

        static OwnedTask error(final String error) {
            return new OwnedTask(null, error);
        }
    }
}

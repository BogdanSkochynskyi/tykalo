package io.tykalo.nudger.handler;

import io.tykalo.list.ListRepository;
import io.tykalo.list.ListType;
import io.tykalo.list.Task;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskService;
import io.tykalo.nudger.TaskNudgerService;
import io.tykalo.nudger.TaskNudgerService.AssignResult;
import io.tykalo.telegram.TelegramCommand;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * The {@code /task <id> nudgers …} command (TK-158): chooses who escalation reaches for one Project
 * task. {@code @user1 @user2} pins exactly those Nudgers (replace, not merge), {@code off} makes the
 * task private, and the bare form shows the current choice. Per-task Nudgers only make sense on Project
 * tasks — the only type that escalates — so other list types are turned away.
 *
 * <p>The owned-live-task resolver mirrors {@code TaskModificationCommandHandler}: a blank, malformed,
 * unknown/archived or someone-else's id each get a distinct message.
 */
@Component
@RequiredArgsConstructor
public class TaskNudgerCommandHandler {

    private static final String USAGE = """
            Usage:
            /task <id> nudgers @user1 @user2 — pick who escalates this task
            /task <id> nudgers off — keep it private
            /task <id> nudgers — show the current choice""";
    private static final String PRIVATE = "off";

    private final UserService userService;
    private final TaskService taskService;
    private final TaskNudgerService taskNudgerService;
    private final ListRepository listRepository;

    @TelegramCommand("/task")
    public String task(final Update update) {
        final String[] parts = argsOf(update).split("\\s+", 3);
        if (parts.length < 2 || parts[0].isBlank() || !parts[1].equalsIgnoreCase("nudgers")) {
            return USAGE;
        }
        final User user = userService.findOrCreate(update);
        final Resolution resolved = resolve(parts[0], user);
        if (resolved.error() != null) {
            return resolved.error();
        }
        final Task task = Objects.requireNonNull(resolved.task());
        final TaskList list = listRepository.findById(task.getListId()).orElse(null);
        if (list == null || list.getType() != ListType.PROJECT) {
            return "🔕 Nudgers only apply to Project tasks. \"%s\" isn't in a Project list.".formatted(task.getTitle());
        }
        final UUID taskId = Objects.requireNonNull(task.getId());
        final String rest = parts.length > 2 ? parts[2].strip() : "";
        if (rest.isBlank()) {
            return show(task, taskId);
        }
        if (rest.equalsIgnoreCase(PRIVATE)) {
            taskNudgerService.makePrivate(taskId);
            return "🔒 \"%s\" is now private — no Nudgers will see it.".formatted(task.getTitle());
        }
        return doAssign(user, task, taskId, rest);
    }

    private String show(final Task task, final UUID taskId) {
        if (task.isNudgersPrivate()) {
            return "🔒 \"%s\" is private — no Nudgers see it. Assign some with /task %s nudgers @user."
                    .formatted(task.getTitle(), taskId);
        }
        final List<String> assigned = taskNudgerService.assignedUsernames(taskId);
        if (assigned.isEmpty()) {
            return "👥 \"%s\" uses all your active Nudgers (the default).".formatted(task.getTitle());
        }
        return "🔔 \"%s\" nudges: %s".formatted(task.getTitle(), join(assigned));
    }

    private String doAssign(final User user, final Task task, final UUID taskId, final String rest) {
        final List<String> usernames = new ArrayList<>(List.of(rest.split("\\s+")));
        final AssignResult result = taskNudgerService.assign(user, taskId, usernames);
        if (!result.applied()) {
            return "⚠️ None of those are your Nudgers%s. See /nudgers list".formatted(unresolvedSuffix(result));
        }
        final StringBuilder reply = new StringBuilder(
                "🔔 \"%s\" will nudge %s.".formatted(task.getTitle(), join(result.assigned())));
        final String unresolved = unresolvedSuffix(result);
        if (!unresolved.isBlank()) {
            reply.append("%n⚠️ Skipped%s.".formatted(unresolved));
        }
        return reply.toString();
    }

    private String unresolvedSuffix(final AssignResult result) {
        final List<String> skipped = new ArrayList<>();
        result.notNudgers().forEach(u -> skipped.add("@" + u + " (not your Nudger)"));
        result.notRegistered().forEach(u -> skipped.add("@" + u + " (not on Tykalo)"));
        return skipped.isEmpty() ? "" : " " + String.join(", ", skipped);
    }

    private String join(final List<String> usernames) {
        return usernames.stream().map(u -> "@" + u).reduce((a, b) -> a + ", " + b).orElse("");
    }

    private Resolution resolve(final String rawId, final User user) {
        final UUID taskId;
        try {
            taskId = UUID.fromString(rawId.strip());
        } catch (final IllegalArgumentException e) {
            return Resolution.error("⚠️ \"%s\" is not a valid task id.".formatted(rawId.strip()));
        }
        final Optional<Task> found = taskService.find(taskId);
        if (found.isEmpty() || found.get().getArchivedAt() != null) {
            return Resolution.error("Task not found.");
        }
        final Task task = found.get();
        if (!task.getOwnerId().equals(user.getId())) {
            return Resolution.error("That task isn't yours.");
        }
        return Resolution.ok(task);
    }

    private record Resolution(@Nullable Task task, @Nullable String error) {

        static Resolution ok(final Task task) {
            return new Resolution(task, null);
        }

        static Resolution error(final String error) {
            return new Resolution(null, error);
        }
    }

    private String argsOf(final Update update) {
        final Message message = update.getMessage();
        final String text = message == null ? null : message.getText();
        if (text == null) {
            return "";
        }
        final String[] parts = text.strip().split("\\s+", 2);
        return parts.length > 1 ? parts[1].strip() : "";
    }
}

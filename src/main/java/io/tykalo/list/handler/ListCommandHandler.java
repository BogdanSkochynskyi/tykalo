package io.tykalo.list.handler;

import io.tykalo.list.ListService;
import io.tykalo.list.ListType;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskService;
import io.tykalo.telegram.TelegramCommand;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * List-management commands: {@code /lists}, {@code /list create} and {@code /list delete}.
 *
 * <p>The dispatcher routes by the first token only, so {@code create}/{@code delete} are parsed
 * here as sub-commands of {@code /list}. Deletion is confirmed in a single stateless step —
 * {@code /list delete <name>} returns a prompt and {@code /list delete <name> confirm} performs
 * the soft delete — because dialog state (FSM/Redis) and inline callbacks do not exist yet.
 */
@Component
@RequiredArgsConstructor
public class ListCommandHandler {

    private static final String CONFIRM = "confirm";

    private final UserService userService;
    private final ListService listService;
    private final TaskService taskService;

    @TelegramCommand("/lists")
    public String lists(final Update update) {
        final User user = userService.findOrCreate(update);
        final List<TaskList> lists = listService.findAllByOwner(Objects.requireNonNull(user.getId()));
        if (lists.isEmpty()) {
            return "You have no lists yet. Create one with /list create <name> [type].";
        }
        final StringBuilder rendered = new StringBuilder("📋 Your lists:\n");
        int index = 1;
        for (final TaskList list : lists) {
            final long count = taskService.countActiveTasks(Objects.requireNonNull(list.getId()));
            rendered.append(index++).append(". ").append(list.getName())
                    .append(" (").append(list.getType()).append(") — ")
                    .append(count).append(count == 1 ? " task" : " tasks").append('\n');
        }
        return rendered.toString().stripTrailing();
    }

    @TelegramCommand("/list")
    public String list(final Update update) {
        final String text = textOf(update);
        if (text == null) {
            return usage();
        }
        final String args = afterFirstToken(text);
        final String[] parts = args.split("\\s+", 2);
        final String sub = parts[0].toLowerCase(Locale.ROOT);
        final String rest = parts.length > 1 ? parts[1].strip() : "";
        return switch (sub) {
            case "create" -> create(update, rest);
            case "delete" -> delete(update, rest);
            case "" -> usage();
            default -> "Unknown sub-command '" + parts[0] + "'.\n\n" + usage();
        };
    }

    private String create(final Update update, final String args) {
        if (args.isBlank()) {
            return "Usage: /list create <name> [type] — type is CHECKLIST, ROUTINE or PROJECT.";
        }
        final ParsedCreate parsed = parseCreate(args);
        if (parsed.error() != null) {
            return parsed.error();
        }
        final String name = Objects.requireNonNull(parsed.name());
        final User user = userService.findOrCreate(update);
        if (listService.findActiveByName(Objects.requireNonNull(user.getId()), name).isPresent()) {
            return "You already have a list named \"" + name + "\".";
        }
        final TaskList created = listService.createList(user, name, Objects.requireNonNull(parsed.type()));
        return "✅ Created %s list \"%s\".".formatted(created.getType(), created.getName());
    }

    private String delete(final Update update, final String args) {
        if (args.isBlank()) {
            return "Usage: /list delete <name>.";
        }
        final boolean confirmed = endsWithToken(args, CONFIRM);
        final String name = confirmed ? stripTrailingToken(args, CONFIRM) : args.strip();
        if (name.isBlank()) {
            return "Usage: /list delete <name>.";
        }
        final User user = userService.findOrCreate(update);
        final Optional<TaskList> target = listService.findActiveByName(Objects.requireNonNull(user.getId()), name);
        if (target.isEmpty()) {
            return "No active list named \"" + name + "\". Use /lists to see your lists.";
        }
        final TaskList list = target.get();
        if (!confirmed) {
            final long count = taskService.countActiveTasks(Objects.requireNonNull(list.getId()));
            return """
                    ⚠️ Delete list "%s" (%d %s)? This archives it.
                    To confirm, send: /list delete %s confirm"""
                    .formatted(list.getName(), count, count == 1 ? "task" : "tasks", list.getName());
        }
        listService.deleteList(Objects.requireNonNull(list.getId()));
        return "🗑️ Archived list \"%s\".".formatted(list.getName());
    }

    /**
     * Splits {@code <name> [type]} where the type is recognised only when the last token names a
     * {@link ListType}. {@code INBOX} is reserved (auto-provisioned per user) and rejected as an
     * invalid type. A multi-word name whose last word is not a type keeps the whole text as the name.
     */
    private ParsedCreate parseCreate(final String args) {
        final String[] tokens = args.split("\\s+");
        final Optional<ListType> trailingType = matchType(tokens[tokens.length - 1]);
        if (trailingType.isPresent() && tokens.length > 1) {
            final ListType type = trailingType.get();
            if (type == ListType.INBOX) {
                return ParsedCreate.error(
                        "INBOX is reserved — your Inbox is created automatically. "
                                + "Choose CHECKLIST, ROUTINE or PROJECT.");
            }
            final String name = args.substring(0, args.lastIndexOf(tokens[tokens.length - 1])).strip();
            if (name.isBlank()) {
                return ParsedCreate.error("List name must not be blank.");
            }
            return ParsedCreate.ok(name, type);
        }
        return ParsedCreate.ok(args.strip(), ListType.CHECKLIST);
    }

    private Optional<ListType> matchType(final String token) {
        for (final ListType type : ListType.values()) {
            if (type.name().equalsIgnoreCase(token)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    private boolean endsWithToken(final String args, final String token) {
        final String[] tokens = args.strip().split("\\s+");
        return tokens.length > 1 && tokens[tokens.length - 1].equalsIgnoreCase(token);
    }

    private String stripTrailingToken(final String args, final String token) {
        final String stripped = args.strip();
        final int idx = stripped.toLowerCase(Locale.ROOT).lastIndexOf(token.toLowerCase(Locale.ROOT));
        return stripped.substring(0, idx).strip();
    }

    private @Nullable String textOf(final Update update) {
        final Message message = update.getMessage();
        return message == null ? null : message.getText();
    }

    private String afterFirstToken(final String text) {
        final String[] parts = text.strip().split("\\s+", 2);
        return parts.length > 1 ? parts[1].strip() : "";
    }

    private String usage() {
        return """
                List commands:
                /lists — show your lists
                /list create <name> [type] — type is CHECKLIST, ROUTINE or PROJECT (default CHECKLIST)
                /list delete <name> — archive a list (asks for confirmation)""";
    }

    private record ParsedCreate(@Nullable String name, @Nullable ListType type, @Nullable String error) {

        static ParsedCreate ok(final String name, final ListType type) {
            return new ParsedCreate(name, type, null);
        }

        static ParsedCreate error(final String error) {
            return new ParsedCreate(null, null, error);
        }
    }
}

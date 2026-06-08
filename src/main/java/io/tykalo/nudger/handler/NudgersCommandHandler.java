package io.tykalo.nudger.handler;

import io.tykalo.nudger.InviteResult;
import io.tykalo.nudger.NudgeInvite;
import io.tykalo.nudger.NudgerActionResult;
import io.tykalo.nudger.NudgerPromptService;
import io.tykalo.nudger.NudgerService;
import io.tykalo.nudger.NudgerStatus;
import io.tykalo.nudger.NudgerSummary;
import io.tykalo.telegram.TelegramBotProperties;
import io.tykalo.telegram.TelegramCommand;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * Handles {@code /nudgers} — the nudger management commands. TK-152 implements the {@code add}
 * subcommand; {@code list}/{@code remove}/{@code pause}/{@code resume} arrive in TK-154, so the
 * command is structured as a subcommand router from the start.
 *
 * <p>{@code /nudgers add @username} invites a trusted contact. If they are already on the bot, a
 * {@code PENDING} pairing is created and they get the Yes/No consent prompt (TK-153, via
 * {@link NudgerPromptService}). If they are not registered yet, the owner gets a
 * {@code t.me/<bot>?start=...} deep-link to forward; opening it routes through {@code /start}
 * ({@link io.tykalo.user.handler.StartCommandHandler}).
 */
@Component
@RequiredArgsConstructor
public class NudgersCommandHandler {

    private static final String USAGE = """
            Usage:
            /nudgers list
            /nudgers add @username
            /nudgers remove @username
            /nudgers pause @username
            /nudgers resume @username""";
    private static final String CONFIRM = "confirm";

    private final UserService userService;
    private final NudgerService nudgerService;
    private final NudgerPromptService promptService;
    private final TelegramBotProperties botProperties;

    @TelegramCommand("/nudgers")
    public String nudgers(final Update update) {
        final String[] parts = argsOf(update).split("\\s+", 2);
        final String subcommand = parts[0].toLowerCase(Locale.ROOT);
        final String rest = parts.length > 1 ? parts[1].strip() : "";
        return switch (subcommand) {
            case "list", "" -> list(update);
            case "add" -> add(update, rest);
            case "remove" -> remove(update, rest);
            case "pause" -> pause(update, rest);
            case "resume" -> resume(update, rest);
            default -> USAGE;
        };
    }

    private String list(final Update update) {
        final User owner = userService.findOrCreate(update);
        final List<NudgerSummary> active = nudgerService.listActive(owner);
        if (active.isEmpty()) {
            return "You have no active Nudgers yet. Add one with /nudgers add @username";
        }
        return active.stream()
                .map(n -> "• @%s — karma %d".formatted(n.username(), n.karmaScore()))
                .collect(Collectors.joining("\n", "Your active Nudgers:\n", ""));
    }

    private String remove(final Update update, final String rest) {
        if (rest.isBlank()) {
            return "Usage: /nudgers remove @username";
        }
        final String[] parts = rest.split("\\s+");
        final boolean confirmed = parts.length > 1 && parts[parts.length - 1].equalsIgnoreCase(CONFIRM);
        final User owner = userService.findOrCreate(update);
        if (!confirmed) {
            return switch (nudgerService.find(owner, parts[0])) {
                case NudgerActionResult.Ok found -> removeConfirmPrompt(found.invitee());
                case NudgerActionResult.NotANudger notANudger -> notANudger(notANudger.username());
                case NudgerActionResult.NotRegistered notRegistered -> notRegistered(notRegistered.username());
                case NudgerActionResult.Unchanged ignored -> USAGE;
            };
        }
        return switch (nudgerService.remove(owner, parts[0])) {
            case NudgerActionResult.Ok ok -> "🗑️ Removed @%s as your Nudger.".formatted(ok.invitee().getTgUsername());
            case NudgerActionResult.NotANudger notANudger -> notANudger(notANudger.username());
            case NudgerActionResult.NotRegistered notRegistered -> notRegistered(notRegistered.username());
            case NudgerActionResult.Unchanged ignored -> USAGE;
        };
    }

    private String pause(final Update update, final String rest) {
        if (rest.isBlank()) {
            return "Usage: /nudgers pause @username";
        }
        final User owner = userService.findOrCreate(update);
        final NudgerActionResult result = nudgerService.pause(owner, rest.split("\\s+")[0]);
        return switch (result) {
            case NudgerActionResult.Ok ok ->
                    "⏸ Paused @%s. They won't receive escalations until you /nudgers resume them."
                            .formatted(ok.invitee().getTgUsername());
            case NudgerActionResult.Unchanged unchanged -> unchanged.nudger().getStatus() == NudgerStatus.PAUSED
                    ? "@%s is already paused.".formatted(unchanged.invitee().getTgUsername())
                    : "Only active Nudgers can be paused (@%s is %s)."
                            .formatted(unchanged.invitee().getTgUsername(), unchanged.nudger().getStatus());
            case NudgerActionResult.NotANudger notANudger -> notANudger(notANudger.username());
            case NudgerActionResult.NotRegistered notRegistered -> notRegistered(notRegistered.username());
        };
    }

    private String resume(final Update update, final String rest) {
        if (rest.isBlank()) {
            return "Usage: /nudgers resume @username";
        }
        final User owner = userService.findOrCreate(update);
        final NudgerActionResult result = nudgerService.resume(owner, rest.split("\\s+")[0]);
        return switch (result) {
            case NudgerActionResult.Ok ok ->
                    "▶️ Resumed @%s. They'll receive escalations again.".formatted(ok.invitee().getTgUsername());
            case NudgerActionResult.Unchanged unchanged -> unchanged.nudger().getStatus() == NudgerStatus.ACTIVE
                    ? "@%s is already active.".formatted(unchanged.invitee().getTgUsername())
                    : "Only paused Nudgers can be resumed (@%s is %s)."
                            .formatted(unchanged.invitee().getTgUsername(), unchanged.nudger().getStatus());
            case NudgerActionResult.NotANudger notANudger -> notANudger(notANudger.username());
            case NudgerActionResult.NotRegistered notRegistered -> notRegistered(notRegistered.username());
        };
    }

    private String removeConfirmPrompt(final User invitee) {
        return """
                ⚠️ Remove @%s as your Nudger? This deletes the pairing and its reminder history.
                To confirm, send: /nudgers remove @%s confirm"""
                .formatted(invitee.getTgUsername(), invitee.getTgUsername());
    }

    private String notANudger(final String username) {
        return "@%s isn't one of your Nudgers. See /nudgers list".formatted(username);
    }

    private String notRegistered(final String username) {
        return "@%s isn't on Tykalo yet, so they can't be one of your Nudgers.".formatted(username);
    }

    private String add(final Update update, final String username) {
        if (username.isBlank()) {
            return USAGE;
        }
        final User owner = userService.findOrCreate(update);
        final InviteResult result = nudgerService.invite(owner, username);
        return switch (result) {
            case InviteResult.Invited invited -> {
                promptService.sendConsentPrompt(invited.nudger(), invited.invitee(), owner);
                yield "✅ Invited @%s as your Nudger. They'll need to accept before any escalations reach them."
                        .formatted(invited.invitee().getTgUsername());
            }
            case InviteResult.AlreadyInvited already -> "@%s is already your Nudger (status: %s)."
                    .formatted(already.invitee().getTgUsername(), already.nudger().getStatus());
            case InviteResult.SelfInvite ignored -> "You can't add yourself as a Nudger 🙂";
            case InviteResult.NotRegistered notRegistered -> notRegisteredReply(owner, notRegistered.username());
        };
    }

    private String notRegisteredReply(final User owner, final String username) {
        final String link = "https://t.me/%s?start=%s"
                .formatted(botProperties.getUsername(), NudgeInvite.payloadFor(Objects.requireNonNull(owner.getId())));
        return """
                @%s isn't on Tykalo yet. Send them this link to invite them as your Nudger:
                %s""".formatted(username, link);
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

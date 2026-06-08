package io.tykalo.nudger.handler;

import io.tykalo.list.ListRenderer;
import io.tykalo.nudger.InviteResult;
import io.tykalo.nudger.NudgeInvite;
import io.tykalo.nudger.NudgerService;
import io.tykalo.telegram.TelegramBotProperties;
import io.tykalo.telegram.TelegramCommand;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import java.util.Locale;
import java.util.Objects;
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
 * {@code PENDING} pairing is created and they get a heads-up message (the Yes/No consent prompt is
 * TK-153). If they are not registered yet, the owner gets a {@code t.me/<bot>?start=...} deep-link to
 * forward; opening it routes through {@code /start} ({@link io.tykalo.user.handler.StartCommandHandler}).
 */
@Component
@RequiredArgsConstructor
public class NudgersCommandHandler {

    private static final String USAGE = "Usage: /nudgers add @username";

    private final UserService userService;
    private final NudgerService nudgerService;
    private final TelegramMessageGateway gateway;
    private final TelegramBotProperties botProperties;

    @TelegramCommand("/nudgers")
    public String nudgers(final Update update) {
        final String[] parts = argsOf(update).split("\\s+", 2);
        final String subcommand = parts[0].toLowerCase(Locale.ROOT);
        final String rest = parts.length > 1 ? parts[1].strip() : "";
        return switch (subcommand) {
            case "add" -> add(update, rest);
            default -> USAGE;
        };
    }

    private String add(final Update update, final String username) {
        if (username.isBlank()) {
            return USAGE;
        }
        final User owner = userService.findOrCreate(update);
        final InviteResult result = nudgerService.invite(owner, username);
        return switch (result) {
            case InviteResult.Invited invited -> {
                notifyInvitee(owner, invited.invitee());
                yield "✅ Invited @%s as your Nudger. They'll need to accept before any escalations reach them."
                        .formatted(invited.invitee().getTgUsername());
            }
            case InviteResult.AlreadyInvited already -> "@%s is already your Nudger (status: %s)."
                    .formatted(already.invitee().getTgUsername(), already.nudger().getStatus());
            case InviteResult.SelfInvite ignored -> "You can't add yourself as a Nudger 🙂";
            case InviteResult.NotRegistered notRegistered -> notRegisteredReply(owner, notRegistered.username());
        };
    }

    private void notifyInvitee(final User owner, final User invitee) {
        final String ownerName = owner.getTgUsername() == null ? "Someone" : "@" + owner.getTgUsername();
        final String text = ("🔔 %s wants to add you as their Nudger on Tykalo — you'd get the occasional "
                + "nudge to remind them about an overdue task. They'll need your OK first; "
                + "you'll be asked to confirm shortly.").formatted(ownerName);
        gateway.sendMarkdown(invitee.getTgChatId(), ListRenderer.escape(text), null);
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

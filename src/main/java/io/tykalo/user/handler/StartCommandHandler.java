package io.tykalo.user.handler;

import io.tykalo.nudger.AcceptResult;
import io.tykalo.nudger.NudgeInvite;
import io.tykalo.nudger.NudgerService;
import io.tykalo.telegram.TelegramCommand;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * Handles {@code /start}: registers the user on first contact and greets them. A {@code /start
 * nudge_invite_<payload>} carries a nudger invite deep-link (TK-152) — the new user is wired up as
 * the encoded owner's pending nudger and the greeting notes who invited them.
 */
@Component
@RequiredArgsConstructor
public class StartCommandHandler {

    private final UserService userService;
    private final NudgerService nudgerService;

    @TelegramCommand("/start")
    public String start(final Update update) {
        final User user = userService.findOrCreate(update);
        return greeting(user) + inviteNote(update, user);
    }

    private String inviteNote(final Update update, final User invitee) {
        final Optional<UUID> ownerId = NudgeInvite.parse(startPayload(update));
        if (ownerId.isEmpty()) {
            return "";
        }
        final AcceptResult result = nudgerService.acceptViaDeepLink(invitee, ownerId.get());
        return switch (result) {
            case AcceptResult.Invited invited -> invitedNote(invited.owner());
            case AcceptResult.AlreadyInvited already -> invitedNote(already.owner());
            case AcceptResult.SelfInvite ignored -> "";
            case AcceptResult.OwnerGone ignored -> "";
        };
    }

    private String invitedNote(final User owner) {
        final String name = owner.getTgUsername() == null ? "Someone" : "@" + owner.getTgUsername();
        return "\n\n🔔 %s invited you to be their Nudger. You'll be asked to confirm shortly.".formatted(name);
    }

    private String greeting(final User user) {
        final String name = user.getTgUsername() == null ? "there" : "@" + user.getTgUsername();
        return """
                👋 Hi, %s! Welcome to Tykalo — your personal task & list bot.

                I help you capture tasks and, when you ask, nudge you (and your trusted contacts) \
                until they get done.

                Your timezone is set to %s — change it any time with /tz.
                Send /help to see everything I can do.""".formatted(name, user.getTimezone());
    }

    private String startPayload(final Update update) {
        final Message message = update.getMessage();
        final String text = message == null ? null : message.getText();
        if (text == null) {
            return "";
        }
        final String[] parts = text.strip().split("\\s+", 2);
        return parts.length > 1 ? parts[1].strip() : "";
    }
}

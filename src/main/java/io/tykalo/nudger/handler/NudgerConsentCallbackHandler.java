package io.tykalo.nudger.handler;

import io.tykalo.list.ListRenderer;
import io.tykalo.nudger.ConsentResult;
import io.tykalo.nudger.NudgerPromptService;
import io.tykalo.nudger.NudgerService;
import io.tykalo.nudger.NudgerStatus;
import io.tykalo.telegram.CallbackHandler;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.user.User;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

/**
 * Handles the Yes/No buttons on a nudger consent prompt (TK-153). {@code nudger:accept:{id}} flips the
 * pairing to {@code ACTIVE}; {@code nudger:decline:{id}} flips it to {@code REJECTED}. Either way the
 * owner gets a heads-up that their invite was answered, and the toast confirms the choice to the
 * invitee.
 *
 * <p>Idempotent: a replayed or double-tapped callback that finds the pairing already decided is a
 * no-op (no second transition, no second owner notification) but still answers with a toast. Callbacks
 * whose data is not a {@code nudger:accept:}/{@code nudger:decline:} action are left unclaimed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NudgerConsentCallbackHandler implements CallbackHandler {

    private final NudgerService nudgerService;
    private final TelegramMessageGateway gateway;

    @Override
    public Optional<String> handle(final CallbackQuery callback) {
        final String data = callback.getData();
        if (data == null) {
            return Optional.empty();
        }
        if (data.startsWith(NudgerPromptService.ACCEPT_PREFIX)) {
            return decide(data.substring(NudgerPromptService.ACCEPT_PREFIX.length()), callback, true);
        }
        if (data.startsWith(NudgerPromptService.DECLINE_PREFIX)) {
            return decide(data.substring(NudgerPromptService.DECLINE_PREFIX.length()), callback, false);
        }
        return Optional.empty();
    }

    private Optional<String> decide(final String rawId, final CallbackQuery callback, final boolean accept) {
        final UUID nudgerId = parseId(rawId);
        if (nudgerId == null) {
            log.warn("Ignoring nudger consent callback with unparseable id: {}", callback.getData());
            return Optional.of("Unknown invite");
        }
        final ConsentResult result = nudgerService.consent(nudgerId, accept);
        final String toast = switch (result) {
            case ConsentResult.Accepted accepted -> {
                notifyOwner(accepted.owner(), inviteeName(callback), true);
                yield "✅ You're now a Nudger. Thanks!";
            }
            case ConsentResult.Declined declined -> {
                notifyOwner(declined.owner(), inviteeName(callback), false);
                yield "Declined — no nudges will be sent.";
            }
            case ConsentResult.AlreadyDecided already -> already.nudger().getStatus() == NudgerStatus.ACTIVE
                    ? "You're already a Nudger 🙂"
                    : "You already declined this invite.";
            case ConsentResult.NotFound ignored -> "This invite is no longer valid.";
        };
        return Optional.of(toast);
    }

    private void notifyOwner(final User owner, final String inviteeName, final boolean accepted) {
        final String text = accepted
                ? "✅ %s accepted your Nudger invite — they can now nudge you about overdue tasks."
                        .formatted(inviteeName)
                : "🙅 %s declined your Nudger invite.".formatted(inviteeName);
        gateway.sendMarkdown(owner.getTgChatId(), ListRenderer.escape(text), null);
    }

    private String inviteeName(final CallbackQuery callback) {
        final org.telegram.telegrambots.meta.api.objects.User from = callback.getFrom();
        final String username = from == null ? null : from.getUserName();
        return username == null ? "Someone" : "@" + username;
    }

    private UUID parseId(final String rawId) {
        try {
            return UUID.fromString(rawId);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }
}

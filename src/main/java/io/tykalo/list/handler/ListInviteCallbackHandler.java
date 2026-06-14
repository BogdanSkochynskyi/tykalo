package io.tykalo.list.handler;

import io.tykalo.list.InviteResponseResult;
import io.tykalo.list.ListInvitePromptService;
import io.tykalo.list.ListInviteService;
import io.tykalo.list.ListRenderer;
import io.tykalo.list.TaskList;
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
 * Handles the Yes/No buttons on a shared-list invite prompt (TK-193). {@code lm:accept:{id}} flips the
 * membership to ACTIVE; {@code lm:decline:{id}} deletes the pending row. Either way the inviter gets a
 * heads-up, and the toast confirms the choice to the invitee.
 *
 * <p>Idempotent: a replayed or double-tapped callback that finds the invite already accepted (or gone)
 * is a no-op — no second transition, no second inviter notification — but still answers with a toast.
 * Callbacks whose data is not an {@code lm:accept:}/{@code lm:decline:} action are left unclaimed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ListInviteCallbackHandler implements CallbackHandler {

    private final ListInviteService inviteService;
    private final TelegramMessageGateway gateway;

    @Override
    public Optional<String> handle(final CallbackQuery callback) {
        final String data = callback.getData();
        if (data == null) {
            return Optional.empty();
        }
        if (data.startsWith(ListInvitePromptService.ACCEPT_PREFIX)) {
            return decide(data.substring(ListInvitePromptService.ACCEPT_PREFIX.length()), callback, true);
        }
        if (data.startsWith(ListInvitePromptService.DECLINE_PREFIX)) {
            return decide(data.substring(ListInvitePromptService.DECLINE_PREFIX.length()), callback, false);
        }
        return Optional.empty();
    }

    private Optional<String> decide(final String rawId, final CallbackQuery callback, final boolean accept) {
        final UUID memberId = parseId(rawId);
        if (memberId == null) {
            log.warn("Ignoring list-invite callback with unparseable id: {}", callback.getData());
            return Optional.of("Unknown invite");
        }
        final InviteResponseResult result = inviteService.respond(memberId, accept);
        final String toast = switch (result) {
            case InviteResponseResult.Accepted accepted -> {
                accepted.inviterOpt().ifPresent(inviter ->
                        notifyInviter(inviter, inviteeName(callback), accepted.list(), true));
                yield "✅ You've joined \"%s\".".formatted(accepted.list().getName());
            }
            case InviteResponseResult.Declined declined -> {
                declined.inviterOpt().ifPresent(inviter ->
                        notifyInviter(inviter, inviteeName(callback), declined.list(), false));
                yield "Declined — you won't be added to the list.";
            }
            case InviteResponseResult.AlreadyActive already -> "You're already a member of this list 🙂";
            case InviteResponseResult.NotFound ignored -> "This invite is no longer valid.";
        };
        return Optional.of(toast);
    }

    private void notifyInviter(final User inviter, final String inviteeName, final TaskList list,
            final boolean accepted) {
        final String text = accepted
                ? "✅ %s accepted your invite to join \"%s\"."
                        .formatted(inviteeName, list.getName())
                : "🙅 %s declined your invite to join \"%s\"."
                        .formatted(inviteeName, list.getName());
        gateway.sendMarkdown(inviter.getTgChatId(), ListRenderer.escape(text), null);
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

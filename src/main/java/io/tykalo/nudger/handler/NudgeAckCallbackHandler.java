package io.tykalo.nudger.handler;

import io.tykalo.list.ListRenderer;
import io.tykalo.nudger.AckResult;
import io.tykalo.nudger.EscalationRenderer;
import io.tykalo.nudger.NudgeAckService;
import io.tykalo.telegram.CallbackHandler;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.user.User;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

/**
 * Handles the "✅ I reminded" button on an escalation message (TK-157). {@code nudge:ack:{logId}}
 * stamps the {@code nudge_log} row acknowledged and bumps the nudger's karma (via
 * {@link NudgeAckService}); on the first tap it also thanks the owner with a running monthly count.
 *
 * <p>Idempotent: a replayed or double-tapped button finds the escalation already acknowledged and is a
 * no-op (no extra karma, no second owner notice) but still answers with a toast. Callbacks whose data is
 * not a {@code nudge:ack:} action are left unclaimed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NudgeAckCallbackHandler implements CallbackHandler {

    private final NudgeAckService nudgeAckService;
    private final TelegramMessageGateway gateway;

    @Override
    public Optional<String> handle(final CallbackQuery callback) {
        final String data = callback.getData();
        if (data == null || !data.startsWith(EscalationRenderer.ACK_CALLBACK_PREFIX)) {
            return Optional.empty();
        }
        final UUID logId = parseId(data.substring(EscalationRenderer.ACK_CALLBACK_PREFIX.length()));
        if (logId == null) {
            log.warn("Ignoring ack callback with unparseable nudge-log id: {}", data);
            return Optional.of("Unknown nudge");
        }
        final AckResult result = nudgeAckService.acknowledge(logId, Instant.now());
        return Optional.of(switch (result) {
            case AckResult.Acknowledged ack -> {
                notifyOwner(ack);
                yield "✅ Thanks for the nudge!";
            }
            case AckResult.AlreadyAcknowledged ignored -> "You already acknowledged this 🙂";
            case AckResult.NotFound ignored -> "This nudge is no longer valid.";
        });
    }

    private void notifyOwner(final AckResult.Acknowledged ack) {
        final User owner = ack.owner();
        final String times = ack.monthlyCount() == 1 ? "time" : "times";
        final String text = "👏 %s reminded you %d %s this month.".formatted(ack.nudgerHandle(), ack.monthlyCount(), times);
        gateway.sendMarkdown(owner.getTgChatId(), ListRenderer.escape(text), null);
    }

    private UUID parseId(final String rawId) {
        try {
            return UUID.fromString(rawId);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }
}

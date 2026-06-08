package io.tykalo.nudger;

import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Acknowledges an escalation when a nudger taps "✅ I reminded" (TK-157): stamps
 * {@code nudge_log.acknowledged_at}, rewards the nudger with a karma point, and reports how many times
 * that nudger has reminded the owner this month so the caller can thank the owner.
 *
 * <p>Idempotent: an already-acknowledged escalation — a replayed or double-tapped button — yields
 * {@link AckResult.AlreadyAcknowledged} and is left untouched, so karma is bumped (and the owner
 * notified) exactly once. The owner notification itself lives in the callback handler, outside this
 * transaction, so a Telegram hiccup never rolls back the ack.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NudgeAckService {

    private final NudgeLogRepository nudgeLogRepository;
    private final NudgerRepository nudgerRepository;
    private final UserRepository userRepository;

    /** Records the nudger's acknowledgement of escalation {@code nudgeLogId} as of {@code now}. */
    @Transactional
    public AckResult acknowledge(final UUID nudgeLogId, final Instant now) {
        final Optional<NudgeLog> found = nudgeLogRepository.findById(nudgeLogId);
        if (found.isEmpty()) {
            return new AckResult.NotFound();
        }
        final NudgeLog entry = found.get();
        if (entry.getAcknowledgedAt().isPresent()) {
            return new AckResult.AlreadyAcknowledged();
        }
        entry.setAcknowledgedAt(now);
        nudgeLogRepository.save(entry);

        final Nudger nudger = nudgerRepository.findById(entry.getNudgerId())
                .orElseThrow(() -> new IllegalStateException(
                        "Nudge log %s references missing nudger %s".formatted(entry.getId(), entry.getNudgerId())));
        nudger.setKarmaScore(nudger.getKarmaScore() + 1);
        nudgerRepository.save(nudger);

        final User owner = userRepository.findById(nudger.getOwnerId())
                .orElseThrow(() -> new IllegalStateException(
                        "Nudger %s references missing owner %s".formatted(nudger.getId(), nudger.getOwnerId())));
        final long monthlyCount = nudgeLogRepository
                .countByNudgerIdAndAcknowledgedAtGreaterThanEqual(nudger.getId(), startOfMonth(owner, now));
        log.info("Nudger {} acknowledged escalation {} (karma now {}, {} ack(s) this month)",
                nudger.getId(), entry.getId(), nudger.getKarmaScore(), monthlyCount);
        return new AckResult.Acknowledged(owner, nudgerHandle(nudger), monthlyCount);
    }

    private Instant startOfMonth(final User owner, final Instant now) {
        final ZoneId zone = owner.getTimezone() == null ? ZoneOffset.UTC : owner.getTimezone();
        return now.atZone(zone).toLocalDate().withDayOfMonth(1).atStartOfDay(zone).toInstant();
    }

    private String nudgerHandle(final Nudger nudger) {
        final String username = userRepository.findById(nudger.getNudgerUserId())
                .map(User::getTgUsername)
                .orElse(null);
        return username == null || username.isBlank() ? "Someone" : "@" + username;
    }
}

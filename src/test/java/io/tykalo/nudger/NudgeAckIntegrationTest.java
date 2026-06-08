package io.tykalo.nudger;

import static org.assertj.core.api.Assertions.assertThat;

import io.tykalo.AbstractIntegrationTest;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Drives the TK-157 acknowledgement against the real Flyway-migrated schema: tapping "I reminded"
 * stamps {@code nudge_log.acknowledged_at}, bumps the nudger's {@code karma_score}, counts only this
 * month's acks, and is idempotent across replays.
 *
 * <p>Owns the {@code 820_00x} tg_chat_id range — the singleton Postgres is shared across integration
 * classes and {@code users.tg_chat_id} is UNIQUE.
 */
class NudgeAckIntegrationTest extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2030-06-15T12:00:00Z");

    @Autowired
    private NudgeAckService nudgeAckService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NudgerRepository nudgerRepository;

    @Autowired
    private NudgeLogRepository nudgeLogRepository;

    private User savedUser(final long tgChatId, final String username) {
        return userRepository.save(User.create(tgChatId, username, ZoneId.of("Europe/Kyiv"), "uk"));
    }

    private Nudger savedNudger(final User owner, final User nudgerUser) {
        final Nudger nudger = Nudger.invite(owner, nudgerUser);
        nudger.setStatus(NudgerStatus.ACTIVE);
        return nudgerRepository.save(nudger);
    }

    private NudgeLog savedLog(final Nudger nudger, final int level) {
        return nudgeLogRepository.save(
                NudgeLog.of(EscalationTargetType.TASK, UUID.randomUUID(), nudger.getId(), level, NOW, "body"));
    }

    @Test
    void acknowledge_stampsAck_bumpsKarma_andCountsThisMonth() {
        // Arrange
        final User owner = savedUser(820_001L, "owner");
        final User nudgerUser = savedUser(820_002L, "buddy");
        final Nudger nudger = savedNudger(owner, nudgerUser);
        final NudgeLog entry = savedLog(nudger, 1);

        // Act
        final AckResult result = nudgeAckService.acknowledge(entry.getId(), NOW);

        // Assert — result, then the persisted row and karma
        assertThat(result).isInstanceOfSatisfying(AckResult.Acknowledged.class, ack -> {
            assertThat(ack.nudgerHandle()).isEqualTo("@buddy");
            assertThat(ack.monthlyCount()).isEqualTo(1L);
        });
        assertThat(nudgeLogRepository.findById(entry.getId()).orElseThrow().getAcknowledgedAt()).isPresent();
        assertThat(nudgerRepository.findById(nudger.getId()).orElseThrow().getKarmaScore()).isEqualTo(1);
    }

    @Test
    void acknowledge_isIdempotent_andDoesNotDoubleKarma() {
        // Arrange
        final User owner = savedUser(820_003L, "owner");
        final User nudgerUser = savedUser(820_004L, "buddy");
        final Nudger nudger = savedNudger(owner, nudgerUser);
        final NudgeLog entry = savedLog(nudger, 1);

        // Act — two taps on the same button
        nudgeAckService.acknowledge(entry.getId(), NOW);
        final AckResult second = nudgeAckService.acknowledge(entry.getId(), NOW.plusSeconds(5));

        // Assert
        assertThat(second).isInstanceOf(AckResult.AlreadyAcknowledged.class);
        assertThat(nudgerRepository.findById(nudger.getId()).orElseThrow().getKarmaScore()).isEqualTo(1);
    }

    @Test
    void acknowledge_monthlyCount_excludesAcksFromEarlierMonths() {
        // Arrange — a previously acknowledged escalation from last month, plus a fresh one to ack now
        final User owner = savedUser(820_005L, "owner");
        final User nudgerUser = savedUser(820_006L, "buddy");
        final Nudger nudger = savedNudger(owner, nudgerUser);
        final NudgeLog lastMonth = savedLog(nudger, 1);
        lastMonth.setAcknowledgedAt(Instant.parse("2030-05-20T08:00:00Z"));
        nudgeLogRepository.save(lastMonth);
        final NudgeLog thisMonth = savedLog(nudger, 2);

        // Act
        final AckResult result = nudgeAckService.acknowledge(thisMonth.getId(), NOW);

        // Assert — only this month's ack is counted
        assertThat(result).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.type(AckResult.Acknowledged.class))
                .extracting(AckResult.Acknowledged::monthlyCount)
                .isEqualTo(1L);
    }

    @Test
    void acknowledge_returnsNotFound_forUnknownLog() {
        // Act
        final AckResult result = nudgeAckService.acknowledge(UUID.randomUUID(), NOW);

        // Assert
        assertThat(result).isInstanceOf(AckResult.NotFound.class);
    }
}

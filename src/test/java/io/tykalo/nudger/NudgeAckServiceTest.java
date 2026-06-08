package io.tykalo.nudger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NudgeAckServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-08T10:00:00Z");

    @Mock
    private NudgeLogRepository nudgeLogRepository;

    @Mock
    private NudgerRepository nudgerRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private NudgeAckService service;

    private static User user(final long chatId, final String username) {
        final User user = User.create(chatId, username, ZoneOffset.UTC, "en");
        user.setId(UUID.randomUUID());
        return user;
    }

    private static Nudger activeNudger(final User owner, final User nudgerUser, final int karma) {
        final Nudger nudger = Nudger.invite(owner, nudgerUser);
        nudger.setId(UUID.randomUUID());
        nudger.setStatus(NudgerStatus.ACTIVE);
        nudger.setKarmaScore(karma);
        return nudger;
    }

    private static NudgeLog unacked(final Nudger nudger) {
        return NudgeLog.of(EscalationTargetType.TASK, UUID.randomUUID(), nudger.getId(), 1, NOW, "body");
    }

    @Test
    void acknowledge_stampsAck_bumpsKarma_andReportsMonthlyCount() {
        // Arrange
        final User owner = user(1L, "alice");
        final User nudgerUser = user(2L, "bob");
        final Nudger nudger = activeNudger(owner, nudgerUser, 2);
        final NudgeLog entry = unacked(nudger);
        when(nudgeLogRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(nudgerRepository.findById(nudger.getId())).thenReturn(Optional.of(nudger));
        when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));
        when(userRepository.findById(nudgerUser.getId())).thenReturn(Optional.of(nudgerUser));
        when(nudgeLogRepository.countByNudgerIdAndAcknowledgedAtGreaterThanEqual(
                org.mockito.ArgumentMatchers.eq(nudger.getId()), org.mockito.ArgumentMatchers.any()))
                .thenReturn(3L);

        // Act
        final AckResult result = service.acknowledge(entry.getId(), NOW);

        // Assert
        assertThat(result).isInstanceOfSatisfying(AckResult.Acknowledged.class, ack -> {
            assertThat(ack.owner()).isEqualTo(owner);
            assertThat(ack.nudgerHandle()).isEqualTo("@bob");
            assertThat(ack.monthlyCount()).isEqualTo(3L);
        });
        assertThat(entry.getAcknowledgedAt()).contains(NOW);
        assertThat(nudger.getKarmaScore()).isEqualTo(3);
        verify(nudgeLogRepository).save(entry);
        verify(nudgerRepository).save(nudger);
    }

    @Test
    void acknowledge_isIdempotent_whenAlreadyAcknowledged() {
        // Arrange — the escalation already carries an acknowledgement
        final User owner = user(1L, "alice");
        final User nudgerUser = user(2L, "bob");
        final Nudger nudger = activeNudger(owner, nudgerUser, 5);
        final NudgeLog entry = unacked(nudger);
        entry.setAcknowledgedAt(NOW.minusSeconds(60));
        when(nudgeLogRepository.findById(entry.getId())).thenReturn(Optional.of(entry));

        // Act
        final AckResult result = service.acknowledge(entry.getId(), NOW);

        // Assert — no second karma point, no writes
        assertThat(result).isInstanceOf(AckResult.AlreadyAcknowledged.class);
        assertThat(nudger.getKarmaScore()).isEqualTo(5);
        verify(nudgeLogRepository, never()).save(entry);
        verify(nudgerRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void acknowledge_returnsNotFound_whenNoSuchLog() {
        // Arrange
        final UUID missing = UUID.randomUUID();
        when(nudgeLogRepository.findById(missing)).thenReturn(Optional.empty());

        // Act
        final AckResult result = service.acknowledge(missing, NOW);

        // Assert
        assertThat(result).isInstanceOf(AckResult.NotFound.class);
        verify(nudgerRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}

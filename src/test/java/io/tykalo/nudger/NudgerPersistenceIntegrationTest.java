package io.tykalo.nudger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tykalo.AbstractIntegrationTest;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Exercises the nudger entities and repositories against the real Flyway-migrated schema (V8): the
 * {@code PENDING} status / zero-karma defaults, the polymorphic escalation rows, and the derived
 * finders. Uses the 980_00x tg_chat_id range (the singleton Postgres is shared and never reset
 * between integration-test classes).
 */
class NudgerPersistenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private NudgerRepository nudgerRepository;

    @Autowired
    private EscalationPolicyRepository escalationPolicyRepository;

    @Autowired
    private NudgeLogRepository nudgeLogRepository;

    @Autowired
    private UserRepository userRepository;

    private User savedUser(final long tgChatId, final String username) {
        return userRepository.save(User.create(tgChatId, username, ZoneId.of("Europe/Kyiv"), "uk"));
    }

    @Test
    void invite_persistsPendingNudgerWithDefaults() {
        // Arrange
        final User owner = savedUser(980_001L, "owner");
        final User nudgerUser = savedUser(980_002L, "helper");

        // Act
        final Nudger saved = nudgerRepository.save(Nudger.invite(owner, nudgerUser));
        final Nudger reloaded = nudgerRepository.findById(saved.getId()).orElseThrow();

        // Assert
        assertThat(reloaded.getOwnerId()).isEqualTo(owner.getId());
        assertThat(reloaded.getNudgerUserId()).isEqualTo(nudgerUser.getId());
        assertThat(reloaded.getStatus()).isEqualTo(NudgerStatus.PENDING);
        assertThat(reloaded.getKarmaScore()).isZero();
        assertThat(reloaded.getAddedAt()).isNotNull();
    }

    @Test
    void findByOwnerIdAndStatus_returnsOnlyMatchingStatus() {
        // Arrange
        final User owner = savedUser(980_003L, "owner");
        final User active = savedUser(980_004L, "active");
        final User pending = savedUser(980_005L, "pending");
        final Nudger activeNudger = Nudger.invite(owner, active);
        activeNudger.setStatus(NudgerStatus.ACTIVE);
        nudgerRepository.save(activeNudger);
        nudgerRepository.save(Nudger.invite(owner, pending));

        // Act
        final List<Nudger> result = nudgerRepository.findByOwnerIdAndStatus(owner.getId(), NudgerStatus.ACTIVE);

        // Assert
        assertThat(result).extracting(Nudger::getNudgerUserId).containsExactly(active.getId());
    }

    @Test
    void nudger_rejectsMissingOwner() {
        // Arrange — a nudger pointing at a non-existent owner
        final User nudgerUser = savedUser(980_006L, "helper");
        final Nudger orphan = Nudger.invite(nudgerUser, nudgerUser);
        orphan.setOwnerId(UUID.randomUUID());

        // Act / Assert — FK to users(id) is enforced
        assertThatThrownBy(() -> nudgerRepository.saveAndFlush(orphan))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void escalationPolicy_persistsLadderOrderedByLevel() {
        // Arrange
        final UUID taskId = UUID.randomUUID();
        escalationPolicyRepository.save(
                EscalationPolicy.of(EscalationTargetType.TASK, taskId, 2, 360, RevealField.TITLE));
        escalationPolicyRepository.save(
                EscalationPolicy.of(EscalationTargetType.TASK, taskId, 1, 120, RevealField.NUMBER));
        escalationPolicyRepository.save(
                EscalationPolicy.of(EscalationTargetType.TASK, taskId, 3, 720, RevealField.DESCRIPTION));

        // Act
        final List<EscalationPolicy> ladder =
                escalationPolicyRepository.findByTargetTypeAndTargetIdOrderByLevelAsc(EscalationTargetType.TASK, taskId);

        // Assert
        assertThat(ladder).extracting(EscalationPolicy::getLevel).containsExactly(1, 2, 3);
        assertThat(ladder).extracting(EscalationPolicy::getRevealFields)
                .containsExactly(RevealField.NUMBER, RevealField.TITLE, RevealField.DESCRIPTION);
    }

    @Test
    void nudgeLog_persistsAndFindsByTarget() {
        // Arrange
        final User owner = savedUser(980_007L, "owner");
        final User nudgerUser = savedUser(980_008L, "helper");
        final Nudger nudger = nudgerRepository.save(Nudger.invite(owner, nudgerUser));
        final UUID taskId = UUID.randomUUID();
        nudgeLogRepository.save(NudgeLog.of(
                EscalationTargetType.TASK, taskId, nudger.getId(), 1, Instant.parse("2030-01-07T00:00:00Z"), "tmpl"));

        // Act
        final List<NudgeLog> logs =
                nudgeLogRepository.findByTargetTypeAndTargetId(EscalationTargetType.TASK, taskId);

        // Assert
        assertThat(logs).singleElement().satisfies(log -> {
            assertThat(log.getNudgerId()).isEqualTo(nudger.getId());
            assertThat(log.getLevel()).isEqualTo(1);
            assertThat(log.getAcknowledgedAt()).isEmpty();
            assertThat(log.getMessageTemplate()).contains("tmpl");
        });
    }

    @Test
    void nudgeLog_rejectsMissingNudger() {
        // Arrange — a log row pointing at a non-existent nudger
        final NudgeLog orphan = NudgeLog.of(EscalationTargetType.TASK, UUID.randomUUID(),
                UUID.randomUUID(), 1, Instant.now(), null);

        // Act / Assert — FK to nudgers(id) is enforced
        assertThatThrownBy(() -> nudgeLogRepository.saveAndFlush(orphan))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

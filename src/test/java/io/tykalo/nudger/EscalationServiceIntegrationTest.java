package io.tykalo.nudger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tykalo.AbstractIntegrationTest;
import io.tykalo.list.ListRepository;
import io.tykalo.list.Task;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskRepository;
import io.tykalo.list.TaskService;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Drives the full TK-156 escalation sweep against the real Flyway-migrated schema: an overdue PROJECT
 * task whose seeded ladder has crossed a rung must produce one {@code nudge_log} row per active nudger,
 * deduped across repeated sweeps, never delivered to a PAUSED nudger, and capped by the
 * {@code uq_nudge_log_target_nudger_level} constraint (V11). The {@link io.tykalo.telegram.TelegramMessageGateway}
 * is the non-polling no-op here, so sends are suppressed while the ledger is still written.
 *
 * <p>Owns the {@code 810_00x} tg_chat_id range — the singleton Postgres is shared across integration
 * classes and {@code users.tg_chat_id} is UNIQUE.
 */
class EscalationServiceIntegrationTest extends AbstractIntegrationTest {

    private static final Instant DUE = Instant.parse("2030-01-07T00:00:00Z");

    @Autowired
    private EscalationService escalationService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ListRepository listRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NudgerRepository nudgerRepository;

    @Autowired
    private NudgeLogRepository nudgeLogRepository;

    private User savedUser(final long tgChatId, final String username) {
        return userRepository.save(User.create(tgChatId, username, ZoneId.of("Europe/Kyiv"), "uk"));
    }

    private Task overdueProjectTask(final User owner) {
        final TaskList project = listRepository.save(TaskList.project(owner, "Launch"));
        final Task task = taskService.createTask(project.getId(), "Ship it");
        task.setDueAt(DUE);
        return taskRepository.save(task);
    }

    private Nudger savedNudger(final User owner, final User nudgerUser, final NudgerStatus status) {
        final Nudger nudger = Nudger.invite(owner, nudgerUser);
        nudger.setStatus(status);
        return nudgerRepository.save(nudger);
    }

    @Test
    void runEscalations_writesNudgeLogForActiveNudger_whenTaskHasCrossedARung() {
        // Arrange — 3h overdue (rung 1 = +120m elapsed, rung 2 = +360m not), one active nudger
        final User owner = savedUser(810_001L, "owner");
        final User nudgerUser = savedUser(810_002L, "buddy");
        final Task task = overdueProjectTask(owner);
        final Nudger pair = savedNudger(owner, nudgerUser, NudgerStatus.ACTIVE);
        final Instant now = DUE.plus(3, ChronoUnit.HOURS);

        // Act
        escalationService.runEscalations(now);

        // Assert — a single level-1 entry for this nudger
        final List<NudgeLog> logs =
                nudgeLogRepository.findByTargetTypeAndTargetId(EscalationTargetType.TASK, task.getId());
        assertThat(logs).hasSize(1);
        assertThat(logs.getFirst().getLevel()).isEqualTo(1);
        assertThat(logs.getFirst().getNudgerId()).isEqualTo(pair.getId());
    }

    @Test
    void runEscalations_isIdempotent_acrossRepeatedSweeps() {
        // Arrange
        final User owner = savedUser(810_003L, "owner");
        final User nudgerUser = savedUser(810_004L, "buddy");
        final Task task = overdueProjectTask(owner);
        savedNudger(owner, nudgerUser, NudgerStatus.ACTIVE);
        final Instant now = DUE.plus(3, ChronoUnit.HOURS);

        // Act — two sweeps at the same level
        escalationService.runEscalations(now);
        escalationService.runEscalations(now.plus(5, ChronoUnit.MINUTES));

        // Assert — the level is logged exactly once
        assertThat(nudgeLogRepository.findByTargetTypeAndTargetId(EscalationTargetType.TASK, task.getId()))
                .hasSize(1);
    }

    @Test
    void runEscalations_skipsPausedNudger() {
        // Arrange
        final User owner = savedUser(810_005L, "owner");
        final User nudgerUser = savedUser(810_006L, "buddy");
        final Task task = overdueProjectTask(owner);
        savedNudger(owner, nudgerUser, NudgerStatus.PAUSED);
        final Instant now = DUE.plus(3, ChronoUnit.HOURS);

        // Act
        escalationService.runEscalations(now);

        // Assert
        assertThat(nudgeLogRepository.findByTargetTypeAndTargetId(EscalationTargetType.TASK, task.getId()))
                .isEmpty();
    }

    @Test
    void uniqueConstraint_rejectsSameLevelTwiceForSameNudger() {
        // Arrange
        final User owner = savedUser(810_007L, "owner");
        final User nudgerUser = savedUser(810_008L, "buddy");
        final Task task = overdueProjectTask(owner);
        final Nudger pair = savedNudger(owner, nudgerUser, NudgerStatus.ACTIVE);
        nudgeLogRepository.saveAndFlush(NudgeLog.of(
                EscalationTargetType.TASK, task.getId(), pair.getId(), 1, Instant.now(), "first"));

        // Act + Assert — a second level-1 for the same (target, nudger) violates the UNIQUE
        assertThatThrownBy(() -> nudgeLogRepository.saveAndFlush(NudgeLog.of(
                EscalationTargetType.TASK, task.getId(), pair.getId(), 1, Instant.now(), "second")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

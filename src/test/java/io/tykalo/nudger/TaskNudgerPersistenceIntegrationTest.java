package io.tykalo.nudger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tykalo.AbstractIntegrationTest;
import io.tykalo.list.ListRepository;
import io.tykalo.list.Task;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskRepository;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Exercises per-task Nudger assignment (TK-158) against the real Flyway-migrated schema (V12): the
 * {@code task_nudgers} link table, its {@code (task_id, nudger_id)} uniqueness, the cascade that drops
 * assignments when a Nudger is removed, and the {@code nudgers_private} flag. Uses the 1_000_00x
 * tg_chat_id range (the singleton Postgres is shared and never reset between integration-test classes).
 */
class TaskNudgerPersistenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TaskNudgerService taskNudgerService;

    @Autowired
    private TaskNudgerRepository taskNudgerRepository;

    @Autowired
    private NudgerRepository nudgerRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ListRepository listRepository;

    @Autowired
    private TaskRepository taskRepository;

    private User savedUser(final long tgChatId, final String username) {
        return userRepository.save(User.create(tgChatId, username, ZoneId.of("Europe/Kyiv"), "uk"));
    }

    private Nudger activePairing(final User owner, final User nudgerUser) {
        final Nudger nudger = Nudger.invite(owner, nudgerUser);
        nudger.setStatus(NudgerStatus.ACTIVE);
        return nudgerRepository.save(nudger);
    }

    private Task projectTask(final User owner, final String title) {
        final TaskList list = listRepository.save(TaskList.project(owner, "Launch-" + owner.getTgChatId()));
        return taskRepository.save(Task.create(list, title));
    }

    @Test
    void assign_pinsNudgers_andAssignmentsByTaskReadsThemBack() {
        // Arrange
        final User owner = savedUser(1_000_001L, "owner1");
        final User alice = savedUser(1_000_002L, "alice1");
        final Nudger pair = activePairing(owner, alice);
        final Task task = projectTask(owner, "Ship it");
        task.setNudgersPrivate(true);
        taskRepository.save(task);

        // Act
        final TaskNudgerService.AssignResult result =
                taskNudgerService.assign(owner, task.getId(), List.of("alice1"));

        // Assert
        assertThat(result.applied()).isTrue();
        assertThat(taskNudgerService.assignmentsByTask(List.of(task.getId())).get(task.getId()))
                .containsExactly(pair.getId());
        assertThat(taskRepository.findById(task.getId()).orElseThrow().isNudgersPrivate()).isFalse();
    }

    @Test
    void removingNudger_cascadeDeletesItsAssignments() {
        // Arrange
        final User owner = savedUser(1_000_010L, "owner2");
        final User bob = savedUser(1_000_011L, "bob2");
        final Nudger pair = activePairing(owner, bob);
        final Task task = projectTask(owner, "Migrate DB");
        taskNudgerRepository.save(TaskNudger.of(task.getId(), pair.getId()));

        // Act — hard-delete the Nudger (mirrors /nudgers remove)
        nudgerRepository.delete(pair);

        // Assert — the link is gone via ON DELETE CASCADE
        assertThat(taskNudgerRepository.findByTaskId(task.getId())).isEmpty();
    }

    @Test
    void makePrivate_clearsRows_andSetsFlag() {
        // Arrange
        final User owner = savedUser(1_000_020L, "owner3");
        final User carol = savedUser(1_000_021L, "carol3");
        final Nudger pair = activePairing(owner, carol);
        final Task task = projectTask(owner, "Write docs");
        taskNudgerRepository.save(TaskNudger.of(task.getId(), pair.getId()));

        // Act
        taskNudgerService.makePrivate(task.getId());

        // Assert
        assertThat(taskNudgerRepository.findByTaskId(task.getId())).isEmpty();
        assertThat(taskRepository.findById(task.getId()).orElseThrow().isNudgersPrivate()).isTrue();
    }

    @Test
    void duplicateAssignment_isRejectedByUniqueConstraint() {
        // Arrange
        final User owner = savedUser(1_000_030L, "owner4");
        final User dave = savedUser(1_000_031L, "dave4");
        final Nudger pair = activePairing(owner, dave);
        final Task task = projectTask(owner, "Review PR");
        taskNudgerRepository.saveAndFlush(TaskNudger.of(task.getId(), pair.getId()));

        // Act / Assert — the (task_id, nudger_id) UNIQUE constraint holds
        assertThatThrownBy(() ->
                taskNudgerRepository.saveAndFlush(TaskNudger.of(task.getId(), pair.getId())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

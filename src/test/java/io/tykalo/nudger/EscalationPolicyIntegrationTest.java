package io.tykalo.nudger;

import static org.assertj.core.api.Assertions.assertThat;

import io.tykalo.AbstractIntegrationTest;
import io.tykalo.list.ListRepository;
import io.tykalo.list.Task;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskService;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Drives the full TK-155 wiring against the real Flyway-migrated schema: creating a task in a PROJECT
 * list must publish a {@code TaskCreatedEvent} that the nudger listener turns into three persisted
 * {@code escalation_policies} rows in the same transaction, while a CHECKLIST task seeds none.
 *
 * <p>Owns the {@code 800_00x} tg_chat_id range — the singleton Postgres is shared across integration
 * classes and {@code users.tg_chat_id} is UNIQUE.
 */
class EscalationPolicyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private ListRepository listRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EscalationPolicyRepository escalationPolicyRepository;

    private User savedUser(final long tgChatId) {
        return userRepository.save(User.create(tgChatId, "owner", ZoneId.of("Europe/Kyiv"), "uk"));
    }

    @Test
    void creatingProjectTask_seedsDefaultEscalationLadder() {
        // Arrange
        final User owner = savedUser(800_001L);
        final TaskList project = listRepository.save(TaskList.project(owner, "Launch"));

        // Act
        final Task task = taskService.createTask(project.getId(), "Ship it");

        // Assert
        final List<EscalationPolicy> ladder = escalationPolicyRepository
                .findByTargetTypeAndTargetIdOrderByLevelAsc(EscalationTargetType.TASK, task.getId());
        assertThat(ladder).hasSize(3);
        assertThat(ladder).extracting(EscalationPolicy::getLevel).containsExactly(1, 2, 3);
        assertThat(ladder).extracting(EscalationPolicy::getDelayMinutes).containsExactly(120, 360, 720);
        assertThat(ladder).extracting(EscalationPolicy::getRevealFields)
                .containsExactly(RevealField.NUMBER, RevealField.TITLE, RevealField.DESCRIPTION);
    }

    @Test
    void creatingChecklistTask_seedsNoLadder() {
        // Arrange
        final User owner = savedUser(800_002L);
        final TaskList checklist = listRepository.save(TaskList.checklist(owner, "Groceries"));

        // Act
        final Task task = taskService.createTask(checklist.getId(), "Buy milk");

        // Assert
        assertThat(escalationPolicyRepository
                .findByTargetTypeAndTargetIdOrderByLevelAsc(EscalationTargetType.TASK, task.getId()))
                .isEmpty();
    }
}

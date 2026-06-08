package io.tykalo.nudger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.list.ListType;
import io.tykalo.list.Task;
import io.tykalo.list.TaskCreatedEvent;
import io.tykalo.list.TaskList;
import io.tykalo.user.User;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EscalationPolicyServiceTest {

    @Mock
    private EscalationPolicyRepository escalationPolicyRepository;

    @InjectMocks
    private EscalationPolicyService service;

    private Task persistedTask() {
        final User owner = User.create(1L, "owner", ZoneId.of("Europe/Kyiv"), "uk");
        owner.setId(UUID.randomUUID());
        final TaskList list = TaskList.project(owner, "Launch");
        list.setId(UUID.randomUUID());
        final Task task = Task.create(list, "Ship it");
        task.setId(UUID.randomUUID());
        return task;
    }

    @Test
    void createDefaults_buildsThreeRungLadder_withDocumentedDelaysAndRevealFields() {
        // Arrange
        final Task task = persistedTask();
        when(escalationPolicyRepository.findByTargetTypeAndTargetIdOrderByLevelAsc(
                EscalationTargetType.TASK, task.getId())).thenReturn(List.of());
        when(escalationPolicyRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        service.createDefaults(task);

        // Assert
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<List<EscalationPolicy>> captor = ArgumentCaptor.forClass(List.class);
        verify(escalationPolicyRepository).saveAll(captor.capture());
        final List<EscalationPolicy> ladder = captor.getValue();
        assertThat(ladder).hasSize(3);
        assertThat(ladder).allSatisfy(p -> {
            assertThat(p.getTargetType()).isEqualTo(EscalationTargetType.TASK);
            assertThat(p.getTargetId()).isEqualTo(task.getId());
        });
        assertThat(ladder).extracting(EscalationPolicy::getLevel).containsExactly(1, 2, 3);
        assertThat(ladder).extracting(EscalationPolicy::getDelayMinutes).containsExactly(120, 360, 720);
        assertThat(ladder).extracting(EscalationPolicy::getRevealFields)
                .containsExactly(RevealField.NUMBER, RevealField.TITLE, RevealField.DESCRIPTION);
    }

    @Test
    void createDefaults_isIdempotent_whenLadderAlreadyExists() {
        // Arrange
        final Task task = persistedTask();
        when(escalationPolicyRepository.findByTargetTypeAndTargetIdOrderByLevelAsc(
                EscalationTargetType.TASK, task.getId()))
                .thenReturn(List.of(EscalationPolicy.of(EscalationTargetType.TASK, task.getId(), 1, 120,
                        RevealField.NUMBER)));

        // Act
        final List<EscalationPolicy> result = service.createDefaults(task);

        // Assert
        assertThat(result).isEmpty();
        verify(escalationPolicyRepository, never()).saveAll(any());
    }

    @Test
    void createDefaults_throws_whenTaskNotPersisted() {
        // Arrange
        final Task unsaved = Task.create(persistedList(), "No id yet");

        // Act + Assert
        assertThatThrownBy(() -> service.createDefaults(unsaved))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("must be persisted");
        verify(escalationPolicyRepository, never()).saveAll(any());
    }

    @Test
    void onTaskCreated_seedsLadder_forProjectList() {
        // Arrange
        final Task task = persistedTask();
        when(escalationPolicyRepository.findByTargetTypeAndTargetIdOrderByLevelAsc(
                EscalationTargetType.TASK, task.getId())).thenReturn(List.of());
        when(escalationPolicyRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        service.onTaskCreated(new TaskCreatedEvent(task, ListType.PROJECT));

        // Assert
        verify(escalationPolicyRepository).saveAll(any());
    }

    @Test
    void onTaskCreated_doesNothing_forNonProjectLists() {
        // Arrange
        final Task task = persistedTask();

        // Act
        service.onTaskCreated(new TaskCreatedEvent(task, ListType.CHECKLIST));
        service.onTaskCreated(new TaskCreatedEvent(task, ListType.ROUTINE));
        service.onTaskCreated(new TaskCreatedEvent(task, ListType.INBOX));

        // Assert
        verify(escalationPolicyRepository, never()).findByTargetTypeAndTargetIdOrderByLevelAsc(
                eq(EscalationTargetType.TASK), any());
        verify(escalationPolicyRepository, never()).saveAll(any());
    }

    private TaskList persistedList() {
        final User owner = User.create(2L, "owner", ZoneId.of("Europe/Kyiv"), "uk");
        owner.setId(UUID.randomUUID());
        final TaskList list = TaskList.project(owner, "Launch");
        list.setId(UUID.randomUUID());
        return list;
    }
}

package io.tykalo.nudger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.list.Task;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskRepository;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EscalationServiceTest {

    private static final Instant DUE = Instant.parse("2026-06-08T00:00:00Z");

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private NudgerRepository nudgerRepository;

    @Mock
    private EscalationPolicyRepository escalationPolicyRepository;

    @Mock
    private NudgeLogRepository nudgeLogRepository;

    @Mock
    private EscalationRenderer renderer;

    @Mock
    private TelegramMessageGateway gateway;

    @InjectMocks
    private EscalationService service;

    private static User user(final long chatId) {
        final User user = User.create(chatId, "u" + chatId, ZoneOffset.UTC, "en");
        user.setId(UUID.randomUUID());
        return user;
    }

    private static Task task(final User owner) {
        final TaskList list = TaskList.project(owner, "Launch");
        list.setId(UUID.randomUUID());
        final Task task = Task.create(list, "Ship it");
        task.setId(UUID.randomUUID());
        task.setDueAt(DUE);
        return task;
    }

    private static Nudger activeNudger(final User owner, final User nudgerUser) {
        final Nudger nudger = Nudger.invite(owner, nudgerUser);
        nudger.setId(UUID.randomUUID());
        nudger.setStatus(NudgerStatus.ACTIVE);
        return nudger;
    }

    private static List<EscalationPolicy> ladder(final Task task) {
        return List.of(
                EscalationPolicy.of(EscalationTargetType.TASK, task.getId(), 1, 120, RevealField.NUMBER),
                EscalationPolicy.of(EscalationTargetType.TASK, task.getId(), 2, 360, RevealField.TITLE),
                EscalationPolicy.of(EscalationTargetType.TASK, task.getId(), 3, 720, RevealField.DESCRIPTION));
    }

    private void stubLoad(final Task task, final List<User> users, final List<Nudger> nudgers,
                          final List<EscalationPolicy> ladder, final List<NudgeLog> alreadySent, final Instant now) {
        when(taskRepository.findOverdueProjectTasks(now)).thenReturn(List.of(task));
        when(userRepository.findAllById(any())).thenReturn(users);
        when(nudgerRepository.findByOwnerIdInAndStatus(any(), eq(NudgerStatus.ACTIVE))).thenReturn(nudgers);
        when(escalationPolicyRepository.findByTargetTypeAndTargetIdInOrderByLevelAsc(eq(EscalationTargetType.TASK), any()))
                .thenReturn(ladder);
        when(nudgeLogRepository.findByTargetTypeAndTargetIdIn(eq(EscalationTargetType.TASK), any()))
                .thenReturn(alreadySent);
    }

    @Test
    void runEscalations_sendsLevelOneToAllActiveNudgers_whenTwoHoursOverdueAndNothingSent() {
        // Arrange — 2h past due, both nudgers active, nothing sent yet
        final User owner = user(810_001L);
        final User nudgerA = user(810_002L);
        final User nudgerB = user(810_003L);
        final Task task = task(owner);
        final Nudger pairA = activeNudger(owner, nudgerA);
        final Nudger pairB = activeNudger(owner, nudgerB);
        final Instant now = DUE.plus(Duration.ofHours(2));
        stubLoad(task, List.of(owner, nudgerA, nudgerB), List.of(pairA, pairB), ladder(task), List.of(), now);
        when(renderer.render(any(), any(), any(), any())).thenReturn("body");

        // Act
        service.runEscalations(now);

        // Assert — both nudgers receive it and two level-1 logs are persisted
        verify(gateway).sendMarkdown(eq(810_002L), eq("body"), isNull());
        verify(gateway).sendMarkdown(eq(810_003L), eq("body"), isNull());
        final ArgumentCaptor<NudgeLog> captor = ArgumentCaptor.forClass(NudgeLog.class);
        verify(nudgeLogRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(NudgeLog::getLevel).containsExactly(1, 1);
    }

    @Test
    void runEscalations_escalatesToLevelTwo_whenSixHoursOverdueAndLevelOneSent() {
        // Arrange — 6h past due (level 2 elapsed), level 1 already delivered to this nudger
        final User owner = user(810_010L);
        final User nudgerUser = user(810_011L);
        final Task task = task(owner);
        final Nudger pair = activeNudger(owner, nudgerUser);
        final Instant now = DUE.plus(Duration.ofHours(6));
        final NudgeLog level1 = NudgeLog.of(
                EscalationTargetType.TASK, task.getId(), pair.getId(), 1, DUE.plus(Duration.ofHours(2)), "old");
        stubLoad(task, List.of(owner, nudgerUser), List.of(pair), ladder(task), List.of(level1), now);
        when(renderer.render(any(), any(), any(), any())).thenReturn("body");

        // Act
        service.runEscalations(now);

        // Assert — a fresh level-2 log
        final ArgumentCaptor<NudgeLog> captor = ArgumentCaptor.forClass(NudgeLog.class);
        verify(nudgeLogRepository).save(captor.capture());
        assertThat(captor.getValue().getLevel()).isEqualTo(2);
    }

    @Test
    void runEscalations_doesNotResend_whenCurrentLevelAlreadySentToNudger() {
        // Arrange — 3h past due (current level 1) but level 1 already logged for this nudger
        final User owner = user(810_020L);
        final User nudgerUser = user(810_021L);
        final Task task = task(owner);
        final Nudger pair = activeNudger(owner, nudgerUser);
        final Instant now = DUE.plus(Duration.ofHours(3));
        final NudgeLog level1 = NudgeLog.of(
                EscalationTargetType.TASK, task.getId(), pair.getId(), 1, DUE.plus(Duration.ofHours(2)), "old");
        stubLoad(task, List.of(owner, nudgerUser), List.of(pair), ladder(task), List.of(level1), now);

        // Act
        service.runEscalations(now);

        // Assert
        verify(gateway, never()).sendMarkdown(anyLong(), any(), any());
        verify(nudgeLogRepository, never()).save(any());
    }

    @Test
    void runEscalations_sendsOnlyToNudgerMissingCurrentLevel_whenAnotherAlreadyHasIt() {
        // Arrange — current level 1; nudger A already got it, nudger B (added later) has not
        final User owner = user(810_030L);
        final User nudgerA = user(810_031L);
        final User nudgerB = user(810_032L);
        final Task task = task(owner);
        final Nudger pairA = activeNudger(owner, nudgerA);
        final Nudger pairB = activeNudger(owner, nudgerB);
        final Instant now = DUE.plus(Duration.ofHours(2));
        final NudgeLog aLevel1 = NudgeLog.of(
                EscalationTargetType.TASK, task.getId(), pairA.getId(), 1, now, "old");
        stubLoad(task, List.of(owner, nudgerA, nudgerB), List.of(pairA, pairB), ladder(task), List.of(aLevel1), now);
        when(renderer.render(any(), any(), any(), any())).thenReturn("body");

        // Act
        service.runEscalations(now);

        // Assert — only nudger B is nudged
        verify(gateway).sendMarkdown(eq(810_032L), eq("body"), isNull());
        verify(gateway, never()).sendMarkdown(eq(810_031L), any(), any());
    }

    @Test
    void runEscalations_doesNothing_whenOverdueButNoLevelElapsedYet() {
        // Arrange — task is overdue (due passed) but the first rung (+120m) has not elapsed
        final User owner = user(810_040L);
        final User nudgerUser = user(810_041L);
        final Task task = task(owner);
        final Nudger pair = activeNudger(owner, nudgerUser);
        final Instant now = DUE.plus(Duration.ofMinutes(30));
        stubLoad(task, List.of(owner, nudgerUser), List.of(pair), ladder(task), List.of(), now);

        // Act
        service.runEscalations(now);

        // Assert
        verify(gateway, never()).sendMarkdown(anyLong(), any(), any());
        verify(nudgeLogRepository, never()).save(any());
    }

    @Test
    void runEscalations_skipsTask_whenOwnerHasNoActiveNudgers() {
        // Arrange — overdue and elapsed, but no active nudgers
        final User owner = user(810_050L);
        final Task task = task(owner);
        final Instant now = DUE.plus(Duration.ofHours(2));
        stubLoad(task, List.of(owner), List.of(), ladder(task), List.of(), now);

        // Act
        service.runEscalations(now);

        // Assert
        verify(gateway, never()).sendMarkdown(anyLong(), any(), any());
        verify(nudgeLogRepository, never()).save(any());
    }

    @Test
    void runEscalations_doesNothing_whenNoOverdueTasks() {
        // Arrange
        final Instant now = DUE.plus(Duration.ofHours(2));
        when(taskRepository.findOverdueProjectTasks(now)).thenReturn(List.of());

        // Act
        service.runEscalations(now);

        // Assert — never even resolves owners
        verify(userRepository, never()).findAllById(any());
        verify(gateway, never()).sendMarkdown(anyLong(), any(), any());
    }

    @Test
    void runEscalations_isolatesFailure_andContinuesToOtherNudgers() {
        // Arrange — two active nudgers; sending to the first throws
        final User owner = user(810_060L);
        final User failing = user(810_061L);
        final User ok = user(810_062L);
        final Task task = task(owner);
        final Nudger pairFailing = activeNudger(owner, failing);
        final Nudger pairOk = activeNudger(owner, ok);
        final Instant now = DUE.plus(Duration.ofHours(2));
        stubLoad(task, List.of(owner, failing, ok), List.of(pairFailing, pairOk), ladder(task), List.of(), now);
        when(renderer.render(any(), any(), any(), any())).thenReturn("body");
        when(gateway.sendMarkdown(eq(810_061L), any(), any())).thenThrow(new RuntimeException("Telegram down"));

        // Act + Assert — the sweep swallows the failure and still delivers to the second nudger
        assertThatCode(() -> service.runEscalations(now)).doesNotThrowAnyException();
        verify(gateway).sendMarkdown(eq(810_062L), eq("body"), isNull());
        verify(nudgeLogRepository).save(any());
    }
}

package io.tykalo.nudger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
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
import java.util.Map;
import java.util.Set;
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
    private TaskNudgerService taskNudgerService;

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
        stubLoad(task, users, nudgers, ladder, alreadySent, Map.of(), now);
    }

    private void stubLoad(final Task task, final List<User> users, final List<Nudger> nudgers,
                          final List<EscalationPolicy> ladder, final List<NudgeLog> alreadySent,
                          final Map<UUID, Set<UUID>> assignments, final Instant now) {
        when(taskRepository.findOverdueProjectTasks(now)).thenReturn(List.of(task));
        when(userRepository.findAllById(any())).thenReturn(users);
        when(nudgerRepository.findByOwnerIdInAndStatus(any(), eq(NudgerStatus.ACTIVE))).thenReturn(nudgers);
        when(escalationPolicyRepository.findByTargetTypeAndTargetIdInOrderByLevelAsc(eq(EscalationTargetType.TASK), any()))
                .thenReturn(ladder);
        when(taskNudgerService.assignmentsByTask(any())).thenReturn(assignments);
        when(nudgeLogRepository.findByTargetTypeAndTargetIdIn(eq(EscalationTargetType.TASK), any()))
                .thenReturn(alreadySent);
    }

    private void stubMultiTaskLoad(final List<Task> tasks, final List<User> users, final List<Nudger> nudgers,
                                   final Instant now) {
        final List<EscalationPolicy> ladders = tasks.stream().flatMap(task -> ladder(task).stream()).toList();
        when(taskRepository.findOverdueProjectTasks(now)).thenReturn(tasks);
        when(userRepository.findAllById(any())).thenReturn(users);
        when(nudgerRepository.findByOwnerIdInAndStatus(any(), eq(NudgerStatus.ACTIVE))).thenReturn(nudgers);
        when(escalationPolicyRepository.findByTargetTypeAndTargetIdInOrderByLevelAsc(eq(EscalationTargetType.TASK), any()))
                .thenReturn(ladders);
        when(taskNudgerService.assignmentsByTask(any())).thenReturn(Map.of());
        when(nudgeLogRepository.findByTargetTypeAndTargetIdIn(eq(EscalationTargetType.TASK), any()))
                .thenReturn(List.of());
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
    void runEscalations_skipsTask_whenMarkedPrivate() {
        // Arrange — elapsed and an active nudger, but the task is private (TK-158)
        final User owner = user(810_080L);
        final User nudgerUser = user(810_081L);
        final Task task = task(owner);
        task.setNudgersPrivate(true);
        final Nudger pair = activeNudger(owner, nudgerUser);
        final Instant now = DUE.plus(Duration.ofHours(2));
        stubLoad(task, List.of(owner, nudgerUser), List.of(pair), ladder(task), List.of(), now);

        // Act
        service.runEscalations(now);

        // Assert
        verify(gateway, never()).sendMarkdown(anyLong(), any(), any());
        verify(nudgeLogRepository, never()).save(any());
    }

    @Test
    void runEscalations_sendsOnlyToAssignedNudger_whenTaskPinsASubset() {
        // Arrange — two active nudgers, but only A is pinned to this task (TK-158)
        final User owner = user(810_090L);
        final User nudgerA = user(810_091L);
        final User nudgerB = user(810_092L);
        final Task task = task(owner);
        final Nudger pairA = activeNudger(owner, nudgerA);
        final Nudger pairB = activeNudger(owner, nudgerB);
        final Instant now = DUE.plus(Duration.ofHours(2));
        stubLoad(task, List.of(owner, nudgerA, nudgerB), List.of(pairA, pairB), ladder(task), List.of(),
                Map.of(task.getId(), Set.of(pairA.getId())), now);
        when(renderer.render(any(), any(), any(), any())).thenReturn("body");

        // Act
        service.runEscalations(now);

        // Assert — only the pinned nudger is reached
        verify(gateway).sendMarkdown(eq(810_091L), eq("body"), isNull());
        verify(gateway, never()).sendMarkdown(eq(810_092L), any(), any());
    }

    @Test
    void runEscalations_skipsTask_whenAssignedNudgerIsNoLongerActive() {
        // Arrange — the only active nudger is not the one pinned to the task
        final User owner = user(810_100L);
        final User activeUser = user(810_101L);
        final Task task = task(owner);
        final Nudger activePair = activeNudger(owner, activeUser);
        final Instant now = DUE.plus(Duration.ofHours(2));
        stubLoad(task, List.of(owner, activeUser), List.of(activePair), ladder(task), List.of(),
                Map.of(task.getId(), Set.of(UUID.randomUUID())), now);

        // Act
        service.runEscalations(now);

        // Assert — the pinned nudger dropped out of the active set, so nobody is reached
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
    void runEscalations_attachesAckKeyboardBuiltFromTheLoggedEscalationId() {
        // Arrange — one active nudger, nothing sent yet
        final User owner = user(810_070L);
        final User nudgerUser = user(810_071L);
        final Task task = task(owner);
        final Nudger pair = activeNudger(owner, nudgerUser);
        final Instant now = DUE.plus(Duration.ofHours(2));
        stubLoad(task, List.of(owner, nudgerUser), List.of(pair), ladder(task), List.of(), now);
        when(renderer.render(any(), any(), any(), any())).thenReturn("body");
        final org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup keyboard =
                org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup.builder()
                        .keyboard(List.of()).build();
        when(renderer.ackKeyboard(any())).thenReturn(keyboard);

        // Act
        service.runEscalations(now);

        // Assert — the keyboard is forwarded, and it was built from the very id the log row carries
        verify(gateway).sendMarkdown(eq(810_071L), eq("body"), eq(keyboard));
        final ArgumentCaptor<UUID> idCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(renderer).ackKeyboard(idCaptor.capture());
        final ArgumentCaptor<NudgeLog> logCaptor = ArgumentCaptor.forClass(NudgeLog.class);
        verify(nudgeLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getId()).isEqualTo(idCaptor.getValue());
    }

    @Test
    void runEscalations_capsAtDailyLimit_whenFiveTasksTargetOneNudger() {
        // Arrange — one active nudger due on five overdue tasks at once, default limit 3, nothing sent today
        final User owner = user(810_110L);
        final User nudgerUser = user(810_111L);
        final Nudger pair = activeNudger(owner, nudgerUser);
        final List<Task> tasks = List.of(task(owner), task(owner), task(owner), task(owner), task(owner));
        final Instant now = DUE.plus(Duration.ofHours(2));
        stubMultiTaskLoad(tasks, List.of(owner, nudgerUser), List.of(pair), now);
        when(renderer.render(any(), any(), any(), any())).thenReturn("body");
        when(nudgeLogRepository.countByNudgerIdAndSentAtGreaterThanEqual(any(), any())).thenReturn(0L);

        // Act
        service.runEscalations(now);

        // Assert — only three of the five reach the nudger; the rest are throttled
        verify(gateway, org.mockito.Mockito.times(3)).sendMarkdown(eq(810_111L), eq("body"), isNull());
        verify(nudgeLogRepository, org.mockito.Mockito.times(3)).save(any());
    }

    @Test
    void runEscalations_skipsCappedNudger_butDeliversToAnother() {
        // Arrange — one task, two active nudgers; A is already at the cap, B is fresh
        final User owner = user(810_120L);
        final User cappedUser = user(810_121L);
        final User freshUser = user(810_122L);
        final Task task = task(owner);
        final Nudger cappedPair = activeNudger(owner, cappedUser);
        final Nudger freshPair = activeNudger(owner, freshUser);
        final Instant now = DUE.plus(Duration.ofHours(2));
        stubLoad(task, List.of(owner, cappedUser, freshUser), List.of(cappedPair, freshPair),
                ladder(task), List.of(), now);
        when(renderer.render(any(), any(), any(), any())).thenReturn("body");
        when(nudgeLogRepository.countByNudgerIdAndSentAtGreaterThanEqual(eq(cappedPair.getId()), any()))
                .thenReturn(3L);

        // Act
        service.runEscalations(now);

        // Assert — the capped nudger is skipped, the message moves to the other active nudger
        verify(gateway).sendMarkdown(eq(810_122L), eq("body"), isNull());
        verify(gateway, never()).sendMarkdown(eq(810_121L), any(), any());
    }

    @Test
    void runEscalations_sendsNothing_whenAllNudgersAreCapped() {
        // Arrange — every active nudger has already hit the cap today
        final User owner = user(810_130L);
        final User nudgerA = user(810_131L);
        final User nudgerB = user(810_132L);
        final Task task = task(owner);
        final Nudger pairA = activeNudger(owner, nudgerA);
        final Nudger pairB = activeNudger(owner, nudgerB);
        final Instant now = DUE.plus(Duration.ofHours(2));
        stubLoad(task, List.of(owner, nudgerA, nudgerB), List.of(pairA, pairB), ladder(task), List.of(), now);
        when(nudgeLogRepository.countByNudgerIdAndSentAtGreaterThanEqual(any(), any())).thenReturn(3L);

        // Act
        service.runEscalations(now);

        // Assert
        verify(gateway, never()).sendMarkdown(anyLong(), any(), any());
        verify(nudgeLogRepository, never()).save(any());
    }

    @Test
    void runEscalations_respectsCustomPerUserLimit() {
        // Arrange — owner lowered the cap to 1; one nudger due on three tasks
        final User owner = user(810_140L);
        owner.setNudgerDailyLimit(1);
        final User nudgerUser = user(810_141L);
        final Nudger pair = activeNudger(owner, nudgerUser);
        final List<Task> tasks = List.of(task(owner), task(owner), task(owner));
        final Instant now = DUE.plus(Duration.ofHours(2));
        stubMultiTaskLoad(tasks, List.of(owner, nudgerUser), List.of(pair), now);
        when(renderer.render(any(), any(), any(), any())).thenReturn("body");
        when(nudgeLogRepository.countByNudgerIdAndSentAtGreaterThanEqual(any(), any())).thenReturn(0L);

        // Act
        service.runEscalations(now);

        // Assert — only one reminder gets through
        verify(gateway, org.mockito.Mockito.times(1)).sendMarkdown(eq(810_141L), eq("body"), isNull());
        verify(nudgeLogRepository, org.mockito.Mockito.times(1)).save(any());
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
        doThrow(new RuntimeException("Telegram down")).when(gateway).sendMarkdown(eq(810_061L), any(), any());

        // Act + Assert — the sweep swallows the failure and still delivers to the second nudger
        assertThatCode(() -> service.runEscalations(now)).doesNotThrowAnyException();
        verify(gateway).sendMarkdown(eq(810_062L), eq("body"), isNull());
        verify(nudgeLogRepository).save(any());
    }
}

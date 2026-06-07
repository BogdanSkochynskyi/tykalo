package io.tykalo.list.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.list.DueDateParser;
import io.tykalo.list.Priority;
import io.tykalo.list.SnoozeParser;
import io.tykalo.list.Task;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskService;
import io.tykalo.list.TaskStatus;
import io.tykalo.telegram.TelegramUpdateFixtures;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.Update;

@ExtendWith(MockitoExtension.class)
class TaskModificationCommandHandlerTest {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    @Mock
    private UserService userService;

    @Mock
    private TaskService taskService;

    private TaskModificationCommandHandler handler;

    private final User owner = owner(1L);

    @BeforeEach
    void setUp() {
        handler = new TaskModificationCommandHandler(
                userService, taskService, new DueDateParser(), new SnoozeParser());
    }

    private static User owner(final long chatId) {
        final User user = User.create(chatId, "owner", KYIV, "uk");
        user.setId(UUID.randomUUID());
        return user;
    }

    private Task task(final UUID ownerId, final String title) {
        final User listOwner = User.create(99L, "list-owner", KYIV, "uk");
        listOwner.setId(ownerId);
        final TaskList list = TaskList.checklist(listOwner, "Work");
        list.setId(UUID.randomUUID());
        final Task task = Task.create(list, title);
        task.setId(UUID.randomUUID());
        return task;
    }

    private Update cmd(final String text) {
        return TelegramUpdateFixtures.command(text, 1L, "owner", "uk");
    }

    private void ownsTask(final Task task) {
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        when(taskService.find(task.getId())).thenReturn(Optional.of(task));
    }

    // ---- /edit ----

    @Test
    void edit_renamesOwnTask() {
        final Task task = task(owner.getId(), "old");
        ownsTask(task);

        final String reply = handler.edit(cmd("/edit " + task.getId() + " title Buy oat milk"));

        assertThat(reply).contains("Renamed").contains("Buy oat milk");
        verify(taskService).updateTitle(task.getId(), "Buy oat milk");
    }

    @Test
    void edit_setsDue_fromNaturalLanguage() {
        final Task task = task(owner.getId(), "Report");
        ownsTask(task);

        final String reply = handler.edit(cmd("/edit " + task.getId() + " due tomorrow 9am"));

        assertThat(reply).contains("Due");
        verify(taskService).updateDueAt(eq(task.getId()), any(Instant.class));
    }

    @Test
    void edit_setsPriority_caseInsensitively() {
        final Task task = task(owner.getId(), "Report");
        ownsTask(task);

        final String reply = handler.edit(cmd("/edit " + task.getId() + " priority high"));

        assertThat(reply).contains("HIGH");
        verify(taskService).updatePriority(task.getId(), Priority.HIGH);
    }

    @Test
    void edit_updatesDescription() {
        final Task task = task(owner.getId(), "Report");
        ownsTask(task);

        final String reply = handler.edit(cmd("/edit " + task.getId() + " description draft due Friday"));

        assertThat(reply).contains("description");
        verify(taskService).updateDescription(task.getId(), "draft due Friday");
    }

    @Test
    void edit_rejectsUnknownField() {
        final Task task = task(owner.getId(), "Report");
        ownsTask(task);

        final String reply = handler.edit(cmd("/edit " + task.getId() + " colour red"));

        assertThat(reply).contains("Unknown field");
        verify(taskService, never()).updateTitle(any(), any());
    }

    @Test
    void edit_rejectsInvalidPriority() {
        final Task task = task(owner.getId(), "Report");
        ownsTask(task);

        final String reply = handler.edit(cmd("/edit " + task.getId() + " priority huge"));

        assertThat(reply).contains("LOW, MEDIUM, HIGH or URGENT");
        verify(taskService, never()).updatePriority(any(), any());
    }

    @Test
    void edit_rejectsUnparseableDate() {
        final Task task = task(owner.getId(), "Report");
        ownsTask(task);

        final String reply = handler.edit(cmd("/edit " + task.getId() + " due someday"));

        assertThat(reply).contains("couldn't read a date");
        verify(taskService, never()).updateDueAt(any(), any());
    }

    @Test
    void edit_showsUsage_whenValueMissing() {
        final String reply = handler.edit(cmd("/edit " + UUID.randomUUID() + " title"));

        assertThat(reply).contains("Usage");
        verify(taskService, never()).updateTitle(any(), any());
    }

    @Test
    void edit_rejectsMalformedId() {
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);

        final String reply = handler.edit(cmd("/edit not-a-uuid title x"));

        assertThat(reply).contains("not a valid task id");
    }

    @Test
    void edit_refusesTaskOwnedBySomeoneElse() {
        final Task someoneElses = task(UUID.randomUUID(), "Theirs");
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        when(taskService.find(someoneElses.getId())).thenReturn(Optional.of(someoneElses));

        final String reply = handler.edit(cmd("/edit " + someoneElses.getId() + " title x"));

        assertThat(reply).contains("isn't yours");
    }

    // ---- /snooze ----

    @Test
    void snooze_pushesDueDate() {
        final Task task = task(owner.getId(), "Report");
        ownsTask(task);

        final String reply = handler.snooze(cmd("/snooze " + task.getId() + " 2d"));

        assertThat(reply).contains("Snoozed");
        verify(taskService).snoozeUntil(eq(task.getId()), any(Instant.class));
    }

    @Test
    void snooze_acceptsKeywordDuration() {
        final Task task = task(owner.getId(), "Report");
        ownsTask(task);

        final String reply = handler.snooze(cmd("/snooze " + task.getId() + " next week"));

        assertThat(reply).contains("Snoozed");
        verify(taskService).snoozeUntil(eq(task.getId()), any(Instant.class));
    }

    @Test
    void snooze_refusesDoneTask() {
        final Task task = task(owner.getId(), "Report");
        task.setStatus(TaskStatus.DONE);
        ownsTask(task);

        final String reply = handler.snooze(cmd("/snooze " + task.getId() + " 2d"));

        assertThat(reply).contains("open tasks");
        verify(taskService, never()).snoozeUntil(any(), any());
    }

    @Test
    void snooze_rejectsUnparseableDuration() {
        final Task task = task(owner.getId(), "Report");
        ownsTask(task);

        final String reply = handler.snooze(cmd("/snooze " + task.getId() + " whenever"));

        assertThat(reply).contains("couldn't read a duration");
        verify(taskService, never()).snoozeUntil(any(), any());
    }

    @Test
    void snooze_showsUsage_whenDurationMissing() {
        final String reply = handler.snooze(cmd("/snooze " + UUID.randomUUID()));

        assertThat(reply).contains("Usage");
        verify(taskService, never()).snoozeUntil(any(), any());
    }

    // ---- /delete ----

    @Test
    void delete_promptsForConfirmation() {
        final Task task = task(owner.getId(), "Report");
        ownsTask(task);

        final String reply = handler.delete(cmd("/delete " + task.getId()));

        assertThat(reply).contains("Delete").contains("confirm");
        verify(taskService, never()).deleteTask(any());
    }

    @Test
    void delete_archivesOnConfirm() {
        final Task task = task(owner.getId(), "Report");
        ownsTask(task);

        final String reply = handler.delete(cmd("/delete " + task.getId() + " confirm"));

        assertThat(reply).contains("Deleted");
        verify(taskService).deleteTask(task.getId());
    }

    @Test
    void delete_reportsNotFound() {
        final UUID id = UUID.randomUUID();
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        when(taskService.find(id)).thenReturn(Optional.empty());

        final String reply = handler.delete(cmd("/delete " + id));

        assertThat(reply).contains("not found");
        verify(taskService, never()).deleteTask(any());
    }

    @Test
    void delete_showsUsage_whenBlank() {
        final String reply = handler.delete(cmd("/delete"));

        assertThat(reply).contains("Usage");
        verify(taskService, never()).deleteTask(any());
    }
}

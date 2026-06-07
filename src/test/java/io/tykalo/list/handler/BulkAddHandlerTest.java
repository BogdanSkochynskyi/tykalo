package io.tykalo.list.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.list.CurrentContextService;
import io.tykalo.list.ListType;
import io.tykalo.list.Task;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskService;
import io.tykalo.telegram.TelegramUpdateFixtures;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.Update;

@ExtendWith(MockitoExtension.class)
class BulkAddHandlerTest {

    @Mock
    private UserService userService;

    @Mock
    private CurrentContextService currentContext;

    @Mock
    private TaskService taskService;

    @InjectMocks
    private BulkAddHandler handler;

    private final User owner = owner();

    private static User owner() {
        final User user = User.create(1L, "owner", ZoneId.of("Europe/Kyiv"), "uk");
        user.setId(UUID.randomUUID());
        return user;
    }

    private TaskList list(final String name, final ListType type) {
        final TaskList list = TaskList.of(owner, name, type);
        list.setId(UUID.randomUUID());
        return list;
    }

    private Update msg(final String text) {
        return TelegramUpdateFixtures.command(text, 1L, "owner", "uk");
    }

    @Test
    void handle_createsOneTaskPerLine_inChecklist() {
        // Arrange
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        final TaskList groceries = list("Groceries", ListType.CHECKLIST);
        when(currentContext.resolveCurrentList(owner.getId())).thenReturn(Optional.of(groceries));
        when(taskService.createTasks(eq(groceries.getId()), anyList()))
                .thenReturn(List.of(new Task(), new Task(), new Task()));

        // Act
        final Optional<String> reply = handler.handle(msg("milk\nbread\neggs"));

        // Assert
        final ArgumentCaptor<List<String>> titles = ArgumentCaptor.captor();
        verify(taskService).createTasks(eq(groceries.getId()), titles.capture());
        assertThat(titles.getValue()).containsExactly("milk", "bread", "eggs");
        assertThat(reply).get().asString().contains("Added 3 tasks to list \"Groceries\"");
    }

    @Test
    void handle_ignoresBlankLines_andStripsTitles() {
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        final TaskList inbox = list("Inbox", ListType.INBOX);
        when(currentContext.resolveCurrentList(owner.getId())).thenReturn(Optional.of(inbox));
        when(taskService.createTasks(eq(inbox.getId()), anyList()))
                .thenReturn(List.of(new Task(), new Task()));

        final Optional<String> reply = handler.handle(msg("  milk  \n\n   \n bread "));

        final ArgumentCaptor<List<String>> titles = ArgumentCaptor.captor();
        verify(taskService).createTasks(eq(inbox.getId()), titles.capture());
        assertThat(titles.getValue()).containsExactly("milk", "bread");
        assertThat(reply).get().asString().contains("Added 2 tasks to list \"Inbox\"");
    }

    @Test
    void handle_leavesSingleLineMessageUnclaimed() {
        // A single line is not bulk-add — the handler stays silent and creates nothing.
        final Optional<String> reply = handler.handle(msg("just one line"));

        assertThat(reply).isEmpty();
        verify(taskService, never()).createTasks(any(), anyList());
        verify(userService, never()).findOrCreate(any());
    }

    @Test
    void handle_showsHint_andCreatesNothing_forProjectList() {
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        when(currentContext.resolveCurrentList(owner.getId()))
                .thenReturn(Optional.of(list("Work", ListType.PROJECT)));

        final Optional<String> reply = handler.handle(msg("design\nbuild"));

        assertThat(reply).get().asString().contains("Work").contains("PROJECT");
        verify(taskService, never()).createTasks(any(), anyList());
    }

    @Test
    void handle_showsHint_andCreatesNothing_forRoutineList() {
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        when(currentContext.resolveCurrentList(owner.getId()))
                .thenReturn(Optional.of(list("Morning", ListType.ROUTINE)));

        final Optional<String> reply = handler.handle(msg("stretch\nwater"));

        assertThat(reply).get().asString().contains("Morning").contains("ROUTINE");
        verify(taskService, never()).createTasks(any(), anyList());
    }

    @Test
    void handle_reportsNoList_whenContextResolvesToNothing() {
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        when(currentContext.resolveCurrentList(owner.getId())).thenReturn(Optional.empty());

        final Optional<String> reply = handler.handle(msg("milk\nbread"));

        assertThat(reply).get().asString().contains("No list to add to");
        verify(taskService, never()).createTasks(any(), anyList());
    }
}

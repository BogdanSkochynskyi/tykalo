package io.tykalo.list.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.list.CurrentContextService;
import io.tykalo.list.Task;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskService;
import io.tykalo.list.ListType;
import io.tykalo.telegram.TelegramUpdateFixtures;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.Update;

@ExtendWith(MockitoExtension.class)
class AddCommandHandlerTest {

    @Mock
    private UserService userService;

    @Mock
    private CurrentContextService currentContext;

    @Mock
    private TaskService taskService;

    @InjectMocks
    private AddCommandHandler handler;

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

    private Task savedTask(final TaskList list) {
        final Task task = Task.create(list, "irrelevant");
        task.setId(UUID.randomUUID());
        return task;
    }

    private Update cmd(final String text) {
        return TelegramUpdateFixtures.command(text, 1L, "owner", "uk");
    }

    @Test
    void add_createsTaskInCurrentList_andRepliesWithIdAndName() {
        // Arrange
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        final TaskList work = list("Work", ListType.PROJECT);
        when(currentContext.resolveCurrentList(owner.getId())).thenReturn(Optional.of(work));
        final Task created = savedTask(work);
        when(taskService.createTask(work.getId(), "Buy milk and eggs")).thenReturn(created);

        // Act
        final String reply = handler.add(cmd("/add Buy milk and eggs"));

        // Assert
        verify(taskService).createTask(work.getId(), "Buy milk and eggs");
        assertThat(reply).contains("Work").contains(created.getId().toString());
    }

    @Test
    void add_fallsBackToInbox_whenNoCurrentList() {
        // Arrange
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        final TaskList inbox = list("Inbox", ListType.INBOX);
        when(currentContext.resolveCurrentList(owner.getId())).thenReturn(Optional.of(inbox));
        when(taskService.createTask(eq(inbox.getId()), any())).thenReturn(savedTask(inbox));

        // Act
        final String reply = handler.add(cmd("/add Call plumber"));

        // Assert
        verify(taskService).createTask(inbox.getId(), "Call plumber");
        assertThat(reply).contains("Inbox");
    }

    @Test
    void add_rejectsBlankTitle_andCreatesNothing() {
        // Act
        final String reply = handler.add(cmd("/add   "));

        // Assert
        assertThat(reply).contains("Usage");
        verify(taskService, never()).createTask(any(), any());
    }

    @Test
    void add_rejectsTooLongTitle_andCreatesNothing() {
        // Arrange
        final String longTitle = "x".repeat(AddCommandHandler.MAX_TITLE_LENGTH + 1);

        // Act
        final String reply = handler.add(cmd("/add " + longTitle));

        // Assert
        assertThat(reply).contains("too long");
        verify(taskService, never()).createTask(any(), any());
    }

    @Test
    void add_reportsNoList_whenNoListResolves() {
        // Arrange
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        when(currentContext.resolveCurrentList(owner.getId())).thenReturn(Optional.empty());

        // Act
        final String reply = handler.add(cmd("/add Something"));

        // Assert
        assertThat(reply).contains("No list to add to");
        verify(taskService, never()).createTask(any(), any());
    }
}

package io.tykalo.list.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.list.ListService;
import io.tykalo.list.ListType;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskService;
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
class ListCommandHandlerTest {

    @Mock
    private UserService userService;

    @Mock
    private ListService listService;

    @Mock
    private TaskService taskService;

    @InjectMocks
    private ListCommandHandler handler;

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

    private Update cmd(final String text) {
        return TelegramUpdateFixtures.command(text, 1L, "owner", "uk");
    }

    @Test
    void create_createsListWithExplicitType() {
        // Arrange
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        when(listService.findActiveByName(owner.getId(), "Groceries")).thenReturn(Optional.empty());
        when(listService.createList(owner, "Groceries", ListType.PROJECT))
                .thenReturn(list("Groceries", ListType.PROJECT));

        // Act
        final String reply = handler.list(cmd("/list create Groceries project"));

        // Assert
        verify(listService).createList(owner, "Groceries", ListType.PROJECT);
        assertThat(reply).contains("Created PROJECT").contains("Groceries");
    }

    @Test
    void create_defaultsToChecklist_andKeepsMultiWordName() {
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        when(listService.findActiveByName(owner.getId(), "Buy milk")).thenReturn(Optional.empty());
        when(listService.createList(owner, "Buy milk", ListType.CHECKLIST))
                .thenReturn(list("Buy milk", ListType.CHECKLIST));

        handler.list(cmd("/list create Buy milk"));

        verify(listService).createList(owner, "Buy milk", ListType.CHECKLIST);
    }

    @Test
    void create_rejectsBlankName() {
        final String reply = handler.list(cmd("/list create"));

        assertThat(reply).contains("Usage: /list create");
        verify(listService, never()).createList(any(), any(), any());
    }

    @Test
    void create_rejectsInboxAsReservedType() {
        final String reply = handler.list(cmd("/list create Foo inbox"));

        assertThat(reply).contains("INBOX is reserved");
        verify(listService, never()).createList(any(), any(), any());
    }

    @Test
    void create_rejectsDuplicateName() {
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        when(listService.findActiveByName(owner.getId(), "Groceries"))
                .thenReturn(Optional.of(list("Groceries", ListType.CHECKLIST)));

        final String reply = handler.list(cmd("/list create Groceries"));

        assertThat(reply).contains("already have a list named \"Groceries\"");
        verify(listService, never()).createList(any(), any(), any());
    }

    @Test
    void delete_showsConfirmationPrompt_whenNotConfirmed() {
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        final TaskList groceries = list("Groceries", ListType.CHECKLIST);
        when(listService.findActiveByName(owner.getId(), "Groceries")).thenReturn(Optional.of(groceries));
        when(taskService.countActiveTasks(groceries.getId())).thenReturn(2L);

        final String reply = handler.list(cmd("/list delete Groceries"));

        assertThat(reply)
                .contains("Delete list \"Groceries\" (2 tasks)")
                .contains("/list delete Groceries confirm");
        verify(listService, never()).deleteList(any());
    }

    @Test
    void delete_softDeletes_whenConfirmed() {
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        final TaskList groceries = list("Groceries", ListType.CHECKLIST);
        when(listService.findActiveByName(owner.getId(), "Groceries")).thenReturn(Optional.of(groceries));

        final String reply = handler.list(cmd("/list delete Groceries confirm"));

        verify(listService).deleteList(groceries.getId());
        assertThat(reply).contains("Archived list \"Groceries\"");
    }

    @Test
    void delete_reportsMissingList() {
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        when(listService.findActiveByName(eq(owner.getId()), any())).thenReturn(Optional.empty());

        final String reply = handler.list(cmd("/list delete Ghost"));

        assertThat(reply).contains("No active list named \"Ghost\"");
        verify(listService, never()).deleteList(any());
    }

    @Test
    void list_reportsUnknownSubCommand() {
        final String reply = handler.list(cmd("/list frobnicate stuff"));

        assertThat(reply).contains("Unknown sub-command 'frobnicate'");
    }
}

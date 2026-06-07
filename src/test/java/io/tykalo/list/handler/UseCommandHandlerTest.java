package io.tykalo.list.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.list.CurrentContextService;
import io.tykalo.list.ListService;
import io.tykalo.list.ListType;
import io.tykalo.list.TaskList;
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
class UseCommandHandlerTest {

    @Mock
    private UserService userService;

    @Mock
    private ListService listService;

    @Mock
    private CurrentContextService currentContext;

    @InjectMocks
    private UseCommandHandler handler;

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
    void use_switchesToNamedList_andStoresContext() {
        // Arrange
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        final TaskList groceries = list("Groceries", ListType.CHECKLIST);
        when(listService.findActiveByName(owner.getId(), "Groceries")).thenReturn(Optional.of(groceries));

        // Act
        final String reply = handler.use(cmd("/use Groceries"));

        // Assert
        verify(currentContext).set(owner.getId(), groceries.getId());
        assertThat(reply).contains("Switched to \"Groceries\"");
    }

    @Test
    void use_reportsUnknownList_andStoresNothing() {
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        when(listService.findActiveByName(owner.getId(), "Ghost")).thenReturn(Optional.empty());

        final String reply = handler.use(cmd("/use Ghost"));

        assertThat(reply).contains("No active list named \"Ghost\"");
        verify(currentContext, never()).set(any(), any());
    }

    @Test
    void use_showsExplicitCurrentList_whenNoArgument() {
        // Arrange
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        final TaskList current = list("Work", ListType.PROJECT);
        when(currentContext.get(owner.getId())).thenReturn(Optional.of(current.getId()));
        when(listService.getActiveById(current.getId())).thenReturn(Optional.of(current));

        // Act
        final String reply = handler.use(cmd("/use"));

        // Assert
        assertThat(reply).contains("Current list: \"Work\"").doesNotContain("default");
    }

    @Test
    void use_showsInboxAsDefault_whenNoCurrentContext() {
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        when(currentContext.get(owner.getId())).thenReturn(Optional.empty());
        when(listService.findInbox(owner.getId())).thenReturn(Optional.of(list("Inbox", ListType.INBOX)));

        final String reply = handler.use(cmd("/use"));

        assertThat(reply).contains("Current list: \"Inbox\"").contains("default");
    }
}

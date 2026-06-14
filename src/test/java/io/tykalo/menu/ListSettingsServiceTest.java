package io.tykalo.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.tykalo.list.ListService;
import io.tykalo.list.ListType;
import io.tykalo.list.Task;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskService;
import io.tykalo.nudger.TaskNudger;
import io.tykalo.nudger.TaskNudgerRepository;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.ConversationStateService;
import io.tykalo.user.User;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ListSettingsServiceTest {

    private static final long CHAT_ID = 777L;
    private static final int MESSAGE_ID = 14;

    @Mock
    private ListService listService;

    @Mock
    private TaskService taskService;

    @Mock
    private TaskNudgerRepository taskNudgerRepository;

    @Mock
    private ConversationStateService conversationState;

    @Mock
    private MyListsService myListsService;

    @Mock
    private TelegramMessageGateway gateway;

    @InjectMocks
    private ListSettingsService service;

    private User user;
    private TaskList list;

    @BeforeEach
    void setUp() {
        user = User.create(CHAT_ID, "tester", ZoneId.of("Europe/Kyiv"), "en");
        user.setId(UUID.randomUUID());
        list = TaskList.of(user, "Groceries", ListType.CHECKLIST);
        list.setId(UUID.randomUUID());
    }

    private TaskList ownedBySomeoneElse() {
        final User other = User.create(999L, "other", ZoneId.of("Europe/Kyiv"), "en");
        other.setId(UUID.randomUUID());
        final TaskList foreign = TaskList.of(other, "Theirs", ListType.CHECKLIST);
        foreign.setId(UUID.randomUUID());
        return foreign;
    }

    @Test
    void open_rendersSettings_andSetsState() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));

        final Optional<String> name = service.open(user, MESSAGE_ID, list.getId());

        assertThat(name).contains("Groceries");
        verify(conversationState).setState(user.getId(), new ConversationState.ListSettings(list.getId()));
        verify(gateway).editMarkdown(eq(CHAT_ID), eq(MESSAGE_ID), anyString(), any());
    }

    @Test
    void open_returnsEmpty_whenListGone() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.empty());

        assertThat(service.open(user, MESSAGE_ID, list.getId())).isEmpty();
        verify(conversationState, never()).setState(any(), any());
    }

    @Test
    void startRename_setsRenamingState_andPrompts() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));

        final Optional<String> toast = service.startRename(user, MESSAGE_ID, list.getId());

        assertThat(toast).get().asString().contains("Rename");
        verify(conversationState).setState(user.getId(),
                new ConversationState.RenamingList(list.getId(), MESSAGE_ID));
        verify(gateway).editMarkdown(eq(CHAT_ID), eq(MESSAGE_ID), anyString(), any());
    }

    @Test
    void startRename_deniedForNonOwner() {
        final TaskList foreign = ownedBySomeoneElse();
        when(listService.getActiveById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThat(service.startRename(user, MESSAGE_ID, foreign.getId()))
                .get().asString().contains("permission");
        verify(conversationState, never()).setState(any(), any());
        verifyNoInteractions(gateway);
    }

    @Test
    void submitRename_renames_andReopensSettings() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));
        when(listService.findActiveByName(user.getId(), "Veg")).thenReturn(Optional.empty());
        final var state = new ConversationState.RenamingList(list.getId(), MESSAGE_ID);

        assertThat(service.submitRename(user, state, "Veg")).isEmpty();

        verify(listService).rename(list.getId(), "Veg");
        verify(conversationState).setState(user.getId(), new ConversationState.ListSettings(list.getId()));
    }

    @Test
    void submitRename_repromptsInPlace_onBlankName() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));
        final var state = new ConversationState.RenamingList(list.getId(), MESSAGE_ID);

        assertThat(service.submitRename(user, state, "   ")).isEmpty();

        verify(gateway).editMarkdown(eq(CHAT_ID), eq(MESSAGE_ID), anyString(), any());
        verify(listService, never()).rename(any(), anyString());
    }

    @Test
    void submitRename_repromptsInPlace_onDuplicateName() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));
        when(listService.findActiveByName(user.getId(), "Chores"))
                .thenReturn(Optional.of(TaskList.of(user, "Chores", ListType.CHECKLIST)));
        final var state = new ConversationState.RenamingList(list.getId(), MESSAGE_ID);

        assertThat(service.submitRename(user, state, "Chores")).isEmpty();

        verify(listService, never()).rename(any(), anyString());
    }

    @Test
    void submitRename_sameName_justReopensSettings_withoutRenaming() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));
        final var state = new ConversationState.RenamingList(list.getId(), MESSAGE_ID);

        assertThat(service.submitRename(user, state, "  groceries ")).isEmpty();

        verify(listService, never()).rename(any(), anyString());
        verify(conversationState).setState(user.getId(), new ConversationState.ListSettings(list.getId()));
    }

    @Test
    void submitRename_listGone_clearsState() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.empty());
        final var state = new ConversationState.RenamingList(list.getId(), MESSAGE_ID);

        assertThat(service.submitRename(user, state, "Veg")).get().asString().contains("no longer available");

        verify(conversationState).setState(user.getId(), new ConversationState.Idle());
        verify(listService, never()).rename(any(), anyString());
    }

    @Test
    void changeType_appliesType_andReopensSettings() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));

        final Optional<String> toast = service.changeType(user, MESSAGE_ID, list.getId(), ListType.PROJECT);

        assertThat(toast).get().asString().contains("PROJECT");
        verify(listService).changeType(list.getId(), ListType.PROJECT);
    }

    @Test
    void changeType_sameType_isNoOp() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));

        final Optional<String> toast = service.changeType(user, MESSAGE_ID, list.getId(), ListType.CHECKLIST);

        assertThat(toast).get().asString().contains("Already");
        verify(listService, never()).changeType(any(), any());
    }

    @Test
    void changeType_blocked_whenLeavingProjectWithNudgers() {
        list.setType(ListType.PROJECT);
        final Task task = Task.create(list, "Milk");
        task.setId(UUID.randomUUID());
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));
        when(taskService.activeTasks(list.getId())).thenReturn(List.of(task));
        when(taskNudgerRepository.findByTaskIdIn(List.of(task.getId())))
                .thenReturn(List.of(TaskNudger.of(task.getId(), UUID.randomUUID())));

        assertThat(service.changeType(user, MESSAGE_ID, list.getId(), ListType.CHECKLIST))
                .get().asString().contains("Remove Nudgers");

        verify(listService, never()).changeType(any(), any());
    }

    @Test
    void archive_softDeletes_andReturnsToMyLists() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));

        final Optional<String> toast = service.archive(user, MESSAGE_ID, list.getId());

        assertThat(toast).get().asString().contains("Archived");
        verify(listService).deleteList(list.getId());
        verify(myListsService).navigate(user, MESSAGE_ID, 0);
    }

    @Test
    void confirmDelete_showsConfirmationScreen() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));
        when(taskService.countActiveTasks(list.getId())).thenReturn(3L);

        final Optional<String> toast = service.confirmDelete(user, MESSAGE_ID, list.getId());

        assertThat(toast).get().asString().contains("Confirm");
        verify(gateway).editMarkdown(eq(CHAT_ID), eq(MESSAGE_ID), anyString(), any());
        verify(listService, never()).deleteList(any());
    }

    @Test
    void delete_softDeletes_andReturnsToMyLists() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));

        final Optional<String> toast = service.delete(user, MESSAGE_ID, list.getId());

        assertThat(toast).get().asString().contains("Deleted");
        verify(listService).deleteList(list.getId());
        verify(myListsService).navigate(user, MESSAGE_ID, 0);
    }

    @Test
    void delete_deniedForNonOwner_andDoesNotDelete() {
        final TaskList foreign = ownedBySomeoneElse();
        when(listService.getActiveById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThat(service.delete(user, MESSAGE_ID, foreign.getId())).get().asString().contains("permission");

        verify(listService, never()).deleteList(any());
        verifyNoInteractions(myListsService);
    }
}

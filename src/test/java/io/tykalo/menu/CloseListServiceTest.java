package io.tykalo.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.list.ListClosingService;
import io.tykalo.list.ListLifecycleService;
import io.tykalo.list.ListPermissionService;
import io.tykalo.list.ListService;
import io.tykalo.list.ListStatus;
import io.tykalo.list.ListType;
import io.tykalo.list.Task;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskService;
import io.tykalo.list.TaskStatus;
import io.tykalo.telegram.CompactUuid;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.user.User;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

@ExtendWith(MockitoExtension.class)
class CloseListServiceTest {

    private static final long CHAT_ID = 100L;
    private static final int MESSAGE_ID = 7;

    @Mock
    private ListService listService;

    @Mock
    private TaskService taskService;

    @Mock
    private ListClosingService closingService;

    @Mock
    private ListLifecycleService lifecycleService;

    @Mock
    private ListPermissionService permissionService;

    @Mock
    private io.tykalo.telegram.conversation.ConversationStateService conversationState;

    @Mock
    private ListViewService listViewService;

    @Mock
    private TelegramMessageGateway gateway;

    @InjectMocks
    private CloseListService service;

    private User user;
    private TaskList list;
    private UUID listId;

    @BeforeEach
    void setUp() {
        user = User.create(CHAT_ID, "tester", ZoneId.of("Europe/Kyiv"), "en");
        user.setId(UUID.randomUUID());
        list = TaskList.of(user, "Groceries", ListType.CHECKLIST);
        listId = UUID.randomUUID();
        list.setId(listId);
    }

    private Task task(final String title) {
        final Task t = Task.create(list, title);
        t.setId(UUID.randomUUID());
        t.setStatus(TaskStatus.TODO);
        return t;
    }

    private void stubEditable() {
        when(listService.getActiveById(listId)).thenReturn(Optional.of(list));
        when(permissionService.canEditList(user.getId(), listId)).thenReturn(true);
    }

    private InlineKeyboardMarkup capturedKeyboard() {
        final ArgumentCaptor<InlineKeyboardMarkup> keyboard = ArgumentCaptor.captor();
        verify(gateway).editMarkdown(eq(CHAT_ID), eq(MESSAGE_ID), anyString(), keyboard.capture());
        return keyboard.getValue();
    }

    private List<String> capturedCallbackData() {
        return capturedKeyboard().getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();
    }

    private String capturedText() {
        final ArgumentCaptor<String> text = ArgumentCaptor.captor();
        verify(gateway).editMarkdown(eq(CHAT_ID), eq(MESSAGE_ID), text.capture(), any());
        return text.getValue();
    }

    // ----- start -----

    @Test
    void start_allDone_rendersSimpleConfirm_andSetsClosingState() {
        stubEditable();
        when(taskService.counts(listId)).thenReturn(new TaskService.Counts(2, 2));

        assertThat(service.start(user, MESSAGE_ID, listId)).contains("Groceries");

        verify(conversationState).setState(user.getId(), new ConversationState.ClosingList(listId));
        assertThat(capturedCallbackData())
                .contains(CloseListService.CONFIRM_PREFIX + listId, CloseListService.CANCEL_PREFIX + listId)
                .doesNotContain(CloseListService.SAVE_PREFIX + listId);
    }

    @Test
    void start_withUnfinished_rendersThreeCarryOverOptions() {
        stubEditable();
        when(taskService.counts(listId)).thenReturn(new TaskService.Counts(3, 1));

        service.start(user, MESSAGE_ID, listId);

        assertThat(capturedText()).contains("not done");
        assertThat(capturedCallbackData()).contains(
                CloseListService.SAVE_PREFIX + listId,
                CloseListService.MOVE_PREFIX + listId,
                CloseListService.DROP_PREFIX + listId,
                CloseListService.CANCEL_PREFIX + listId);
    }

    @Test
    void start_returnsEmpty_whenUserCannotEdit() {
        when(listService.getActiveById(listId)).thenReturn(Optional.of(list));
        when(permissionService.canEditList(user.getId(), listId)).thenReturn(false);

        assertThat(service.start(user, MESSAGE_ID, listId)).isEmpty();
        verify(gateway, never()).editMarkdown(anyLong(), anyInt(), anyString(), any());
    }

    @Test
    void start_returnsEmpty_whenListIsAlreadyCompleted() {
        list.setStatus(ListStatus.COMPLETED);
        when(listService.getActiveById(listId)).thenReturn(Optional.of(list));

        assertThat(service.start(user, MESSAGE_ID, listId)).isEmpty();
    }

    // ----- move picker -----

    @Test
    void showMovePicker_listsOtherActiveLists_andSetsTargetState() {
        stubEditable();
        final TaskList other = TaskList.of(user, "Backlog", ListType.PROJECT);
        other.setId(UUID.randomUUID());
        when(lifecycleService.findActiveByOwner(user.getId())).thenReturn(List.of(list, other));

        assertThat(service.showMovePicker(user, MESSAGE_ID, listId)).contains("Groceries");

        verify(conversationState).setState(user.getId(), new ConversationState.ClosingListTarget(listId));
        final String expectedMove = CloseListService.MOVE_TO_PREFIX
                + CompactUuid.encode(listId) + ":" + CompactUuid.encode(other.getId());
        assertThat(capturedCallbackData()).contains(expectedMove, CloseListService.START_PREFIX + listId);
    }

    @Test
    void showMovePicker_showsGuidance_whenNoOtherActiveList() {
        stubEditable();
        when(lifecycleService.findActiveByOwner(user.getId())).thenReturn(List.of(list));

        service.showMovePicker(user, MESSAGE_ID, listId);

        assertThat(capturedText()).contains("No other active list");
        assertThat(capturedCallbackData()).containsExactly(CloseListService.START_PREFIX + listId);
    }

    // ----- drop confirm -----

    @Test
    void showDropConfirm_rendersDropConfirmation() {
        stubEditable();
        when(closingService.unfinishedTasks(listId)).thenReturn(List.of(task("Milk"), task("Eggs")));

        service.showDropConfirm(user, MESSAGE_ID, listId);

        assertThat(capturedText()).contains("Drop 2 unfinished");
        assertThat(capturedCallbackData())
                .contains(CloseListService.DROP_CONFIRM_PREFIX + listId, CloseListService.CANCEL_PREFIX + listId);
    }

    // ----- execute: confirm / save / move / drop -----

    @Test
    void confirmClose_marksCompleted_andRerendersListView() {
        stubEditable();
        when(listViewService.show(user, MESSAGE_ID, listId, 0)).thenReturn(Optional.of("Groceries"));

        assertThat(service.confirmClose(user, MESSAGE_ID, listId)).get().asString().contains("List closed");
        verify(lifecycleService).markCompleted(user.getId(), listId);
    }

    @Test
    void confirmClose_isDenied_whenUserCannotEdit() {
        when(listService.getActiveById(listId)).thenReturn(Optional.of(list));
        when(permissionService.canEditList(user.getId(), listId)).thenReturn(false);

        assertThat(service.confirmClose(user, MESSAGE_ID, listId)).get().asString().contains("owners and editors");
        verify(lifecycleService, never()).markCompleted(any(), any());
    }

    @Test
    void confirmClose_reportsAlreadyClosed_whenListNotActive() {
        list.setStatus(ListStatus.COMPLETED);
        when(listService.getActiveById(listId)).thenReturn(Optional.of(list));
        when(permissionService.canEditList(user.getId(), listId)).thenReturn(true);

        assertThat(service.confirmClose(user, MESSAGE_ID, listId)).get().asString().contains("already closed");
        verify(lifecycleService, never()).markCompleted(any(), any());
    }

    @Test
    void saveForLater_carriesItemsToPending_andRerenders() {
        stubEditable();
        when(closingService.unfinishedTasks(listId)).thenReturn(List.of(task("Milk"), task("Eggs")));
        when(listViewService.show(user, MESSAGE_ID, listId, 0)).thenReturn(Optional.of("Groceries"));

        assertThat(service.saveForLater(user, MESSAGE_ID, listId)).get().asString()
                .contains("Saved 2 items").contains("Pending");
        verify(closingService).closeSavingForLater(user.getId(), listId);
    }

    @Test
    void moveTo_movesItems_andRerenders() {
        stubEditable();
        final UUID targetId = UUID.randomUUID();
        final TaskList target = TaskList.of(user, "Backlog", ListType.PROJECT);
        target.setId(targetId);
        when(listService.getActiveById(targetId)).thenReturn(Optional.of(target));
        when(closingService.unfinishedTasks(listId)).thenReturn(List.of(task("Milk")));
        when(listViewService.show(user, MESSAGE_ID, listId, 0)).thenReturn(Optional.of("Groceries"));

        assertThat(service.moveTo(user, MESSAGE_ID, listId, targetId)).get().asString()
                .contains("Moved 1 item").contains("Backlog");
        verify(closingService).closeMovingTo(user.getId(), listId, targetId);
    }

    @Test
    void moveTo_reportsGone_whenTargetNoLongerActive() {
        final UUID targetId = UUID.randomUUID();
        when(listService.getActiveById(targetId)).thenReturn(Optional.empty());

        assertThat(service.moveTo(user, MESSAGE_ID, listId, targetId)).get().asString().contains("no longer available");
        verify(closingService, never()).closeMovingTo(any(), any(), any());
    }

    @Test
    void drop_marksCompleted_andRerenders() {
        stubEditable();
        when(listViewService.show(user, MESSAGE_ID, listId, 0)).thenReturn(Optional.of("Groceries"));

        assertThat(service.drop(user, MESSAGE_ID, listId)).get().asString().contains("List closed");
        verify(lifecycleService).markCompleted(user.getId(), listId);
    }

    // ----- reopen -----

    @Test
    void reopen_reopensCompletedList_andRerenders() {
        list.setStatus(ListStatus.COMPLETED);
        when(listService.getActiveById(listId)).thenReturn(Optional.of(list));
        when(permissionService.canEditList(user.getId(), listId)).thenReturn(true);
        when(listViewService.show(user, MESSAGE_ID, listId, 0)).thenReturn(Optional.of("Groceries"));

        assertThat(service.reopen(user, MESSAGE_ID, listId)).get().asString().contains("reopened");
        verify(lifecycleService).reopen(user.getId(), listId);
    }

    @Test
    void reopen_reportsAlreadyOpen_whenListIsActive() {
        when(listService.getActiveById(listId)).thenReturn(Optional.of(list));
        when(permissionService.canEditList(user.getId(), listId)).thenReturn(true);

        assertThat(service.reopen(user, MESSAGE_ID, listId)).get().asString().contains("already open");
        verify(lifecycleService, never()).reopen(any(), any());
    }

    @Test
    void cancel_returnsToListView() {
        when(listViewService.show(user, MESSAGE_ID, listId, 0)).thenReturn(Optional.of("Groceries"));

        assertThat(service.cancel(user, MESSAGE_ID, listId)).get().asString().contains("cancelled");
    }
}

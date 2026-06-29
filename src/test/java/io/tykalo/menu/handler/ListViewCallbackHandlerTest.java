package io.tykalo.menu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.tykalo.list.ListType;
import io.tykalo.list.PendingItem;
import io.tykalo.list.Task;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskService;
import io.tykalo.list.TaskStatus;
import io.tykalo.menu.AddItemsService;
import io.tykalo.menu.ListSettingsService;
import io.tykalo.menu.ListViewService;
import io.tykalo.menu.MembersService;
import io.tykalo.menu.MyListsService;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

@ExtendWith(MockitoExtension.class)
class ListViewCallbackHandlerTest {

    private static final long CHAT_ID = 100L;
    private static final int MESSAGE_ID = 7;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TaskService taskService;

    @Mock
    private ListViewService listViewService;

    @Mock
    private MyListsService myListsService;

    @Mock
    private AddItemsService addItemsService;

    @Mock
    private MembersService membersService;

    @Mock
    private ListSettingsService listSettingsService;

    @InjectMocks
    private ListViewCallbackHandler handler;

    private User user;
    private TaskList list;

    @BeforeEach
    void setUp() {
        user = User.create(CHAT_ID, "tester", ZoneId.of("Europe/Kyiv"), "en");
        user.setId(UUID.randomUUID());
        list = TaskList.of(user, "Groceries", ListType.CHECKLIST);
        list.setId(UUID.randomUUID());
    }

    private Task task(final TaskStatus status) {
        final Task task = Task.create(list, "Milk");
        task.setId(UUID.randomUUID());
        task.setStatus(status);
        return task;
    }

    private void stubUser() {
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.of(user));
    }

    @Test
    void done_marksTheTaskDone_reRendersSamePage_andToasts() {
        stubUser();
        final Task milk = task(TaskStatus.TODO);
        when(taskService.find(milk.getId())).thenReturn(Optional.of(milk));
        when(listViewService.show(user, MESSAGE_ID, list.getId(), 2)).thenReturn(Optional.of("Groceries"));

        final Optional<String> toast =
                handler.handle(callbackOnMessage(ListViewService.DONE_PREFIX + milk.getId() + ":2"));

        assertThat(toast).get().asString().contains("Done");
        verify(taskService).markDone(user.getId(), milk.getId());
        verify(listViewService).show(user, MESSAGE_ID, list.getId(), 2);
    }

    @Test
    void undo_reopensTheTask_andToasts() {
        stubUser();
        final Task milk = task(TaskStatus.DONE);
        when(taskService.find(milk.getId())).thenReturn(Optional.of(milk));
        when(listViewService.show(user, MESSAGE_ID, list.getId(), 0)).thenReturn(Optional.of("Groceries"));

        final Optional<String> toast =
                handler.handle(callbackOnMessage(ListViewService.UNDO_PREFIX + milk.getId() + ":0"));

        assertThat(toast).get().asString().contains("Reopened");
        verify(taskService).reopen(user.getId(), milk.getId());
    }

    @Test
    void toggle_reportsTaskNotFound_andDoesNotMutate() {
        stubUser();
        final UUID taskId = UUID.randomUUID();
        when(taskService.find(taskId)).thenReturn(Optional.empty());

        assertThat(handler.handle(callbackOnMessage(ListViewService.DONE_PREFIX + taskId + ":0")))
                .get().asString().contains("not found");
        verify(taskService, never()).markDone(user.getId(), taskId);
    }

    @Test
    void save_defersTheTask_reRendersSamePage_andToastsWithPendingHint() {
        stubUser();
        final Task milk = task(TaskStatus.TODO);
        final java.util.List<String> noTags = java.util.List.of();
        when(taskService.find(milk.getId())).thenReturn(Optional.of(milk));
        when(taskService.saveForLater(user.getId(), milk.getId()))
                .thenReturn(PendingItem.defer(user.getId(), "Milk", list.getId(), noTags, milk.getId()));
        when(listViewService.show(user, MESSAGE_ID, list.getId(), 1)).thenReturn(Optional.of("Groceries"));

        final Optional<String> toast =
                handler.handle(callbackOnMessage(ListViewService.SAVE_PREFIX + milk.getId() + ":1"));

        assertThat(toast).get().asString().contains("Saved 'Milk' for later").contains("📥 Pending");
        verify(taskService).saveForLater(user.getId(), milk.getId());
        verify(listViewService).show(user, MESSAGE_ID, list.getId(), 1);
    }

    @Test
    void save_reportsTaskNotFound_andDoesNotDefer_whenAlreadyArchived() {
        stubUser();
        final Task milk = task(TaskStatus.DEFERRED);
        milk.setArchivedAt(java.time.Instant.now());
        when(taskService.find(milk.getId())).thenReturn(Optional.of(milk));

        assertThat(handler.handle(callbackOnMessage(ListViewService.SAVE_PREFIX + milk.getId() + ":0")))
                .get().asString().contains("not found");
        verify(taskService, never()).saveForLater(user.getId(), milk.getId());
    }

    @Test
    void page_rendersTheRequestedPage() {
        stubUser();
        when(listViewService.show(user, MESSAGE_ID, list.getId(), 3)).thenReturn(Optional.of("Groceries"));

        final Optional<String> toast =
                handler.handle(callbackOnMessage(ListViewService.PAGE_PREFIX + list.getId() + ":3"));

        assertThat(toast).get().asString().contains("Page 4");
        verify(listViewService).show(user, MESSAGE_ID, list.getId(), 3);
    }

    @Test
    void back_returnsToMyLists() {
        stubUser();

        final Optional<String> toast = handler.handle(callbackOnMessage(ListViewService.BACK));

        assertThat(toast).get().asString().contains("My Lists");
        verify(myListsService).navigate(user, MESSAGE_ID, 0);
    }

    @Test
    void addItems_startsTheAddFlow() {
        stubUser();
        when(addItemsService.start(user, MESSAGE_ID, list.getId())).thenReturn(Optional.of("Groceries"));

        final Optional<String> toast =
                handler.handle(callbackOnMessage(ListViewService.ADD_PREFIX + list.getId()));

        assertThat(toast).get().asString().contains("Adding items");
        verify(addItemsService).start(user, MESSAGE_ID, list.getId());
    }

    @Test
    void addItems_reportsListGone_whenStartFindsNoList() {
        stubUser();
        when(addItemsService.start(user, MESSAGE_ID, list.getId())).thenReturn(Optional.empty());

        assertThat(handler.handle(callbackOnMessage(ListViewService.ADD_PREFIX + list.getId())))
                .get().asString().contains("no longer available");
    }

    @Test
    void more_opensTheSettingsScreen() {
        stubUser();
        when(listSettingsService.open(user, MESSAGE_ID, list.getId())).thenReturn(Optional.of("Groceries"));

        final Optional<String> toast =
                handler.handle(callbackOnMessage(ListViewService.MORE_PREFIX + list.getId()));

        assertThat(toast).get().asString().contains("Settings");
        verify(listSettingsService).open(user, MESSAGE_ID, list.getId());
    }

    @Test
    void more_reportsListGoneOrNotEditable_whenOpenFails() {
        stubUser();
        when(listSettingsService.open(user, MESSAGE_ID, list.getId())).thenReturn(Optional.empty());

        assertThat(handler.handle(callbackOnMessage(ListViewService.MORE_PREFIX + list.getId())))
                .get().asString().contains("can't edit");
    }

    @Test
    void members_opensTheMembersScreen() {
        stubUser();
        when(membersService.open(user, MESSAGE_ID, list.getId())).thenReturn(Optional.of("Groceries"));

        final Optional<String> toast =
                handler.handle(callbackOnMessage(ListViewService.MEMBERS_PREFIX + list.getId()));

        assertThat(toast).get().asString().contains("Members");
        verify(membersService).open(user, MESSAGE_ID, list.getId());
    }

    @Test
    void members_reportsListGone_whenOpenFails() {
        stubUser();
        when(membersService.open(user, MESSAGE_ID, list.getId())).thenReturn(Optional.empty());

        assertThat(handler.handle(callbackOnMessage(ListViewService.MEMBERS_PREFIX + list.getId())))
                .get().asString().contains("no longer available");
    }

    @Test
    void toggle_reportsListGone_whenReRenderFindsNoList() {
        stubUser();
        final Task milk = task(TaskStatus.TODO);
        when(taskService.find(milk.getId())).thenReturn(Optional.of(milk));
        when(listViewService.show(user, MESSAGE_ID, list.getId(), 0)).thenReturn(Optional.empty());

        assertThat(handler.handle(callbackOnMessage(ListViewService.DONE_PREFIX + milk.getId() + ":0")))
                .get().asString().contains("no longer available");
    }

    @Test
    void page_reportsExpired_whenMessageIsGone() {
        // No message on the callback → nothing to edit; resolve short-circuits before any service call.
        assertThat(handler.handle(callback(ListViewService.PAGE_PREFIX + list.getId() + ":0")))
                .get().asString().contains("expired");
        verifyNoInteractions(userRepository, listViewService, taskService, myListsService);
    }

    @Test
    void nonListViewCallback_isLeftUnclaimed() {
        assertThat(handler.handle(callback("lists:open:" + list.getId()))).isEmpty();
    }

    private CallbackQuery callback(final String data) {
        final CallbackQuery query = new CallbackQuery();
        query.setId("cb-id");
        query.setData(data);
        return query;
    }

    private CallbackQuery callbackOnMessage(final String data) {
        final Message message = new Message();
        message.setMessageId(MESSAGE_ID);
        message.setChat(new Chat(CHAT_ID, "private"));

        final CallbackQuery query = callback(data);
        query.setMessage(message);
        return query;
    }
}

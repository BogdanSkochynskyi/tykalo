package io.tykalo.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
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
import io.tykalo.list.TaskStatus;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.ConversationStateService;
import io.tykalo.user.User;
import java.time.ZoneId;
import java.util.ArrayList;
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
class ListViewServiceTest {

    private static final long CHAT_ID = 100L;
    private static final int MESSAGE_ID = 7;

    @Mock
    private ListService listService;

    @Mock
    private TaskService taskService;

    @Mock
    private ConversationStateService conversationState;

    @Mock
    private TelegramMessageGateway gateway;

    @InjectMocks
    private ListViewService service;

    private User user;
    private TaskList list;

    @BeforeEach
    void setUp() {
        user = User.create(CHAT_ID, "tester", ZoneId.of("Europe/Kyiv"), "en");
        user.setId(UUID.randomUUID());
        list = TaskList.of(user, "Groceries", ListType.CHECKLIST);
        list.setId(UUID.randomUUID());
    }

    private Task task(final String title, final TaskStatus status) {
        final Task task = Task.create(list, title);
        task.setId(UUID.randomUUID());
        task.setStatus(status);
        return task;
    }

    private List<String> callbackData(final InlineKeyboardMarkup keyboard) {
        return keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();
    }

    private InlineKeyboardMarkup capturedKeyboard() {
        final ArgumentCaptor<InlineKeyboardMarkup> keyboard = ArgumentCaptor.captor();
        verify(gateway).editMarkdown(eq(CHAT_ID), eq(MESSAGE_ID), anyString(), keyboard.capture());
        return keyboard.getValue();
    }

    @Test
    void show_rendersHeaderItemsAndControlRows_andSetsListViewState() {
        final Task milk = task("Milk", TaskStatus.TODO);
        final Task eggs = task("Eggs", TaskStatus.DONE);
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));
        when(taskService.activeTasks(list.getId())).thenReturn(List.of(milk, eggs));

        final Optional<String> result = service.show(user, MESSAGE_ID, list.getId(), 0);

        assertThat(result).contains("Groceries");
        verify(conversationState).setState(user.getId(), new ConversationState.ListView(list.getId()));

        final ArgumentCaptor<String> text = ArgumentCaptor.captor();
        verify(gateway).editMarkdown(eq(CHAT_ID), eq(MESSAGE_ID), text.capture(), any());
        assertThat(text.getValue()).contains("🛒 Groceries").contains("☐ Milk").contains("✅ Eggs");

        assertThat(callbackData(capturedKeyboard())).contains(
                ListViewService.DONE_PREFIX + milk.getId() + ":0",   // TODO item → tap to complete
                ListViewService.UNDO_PREFIX + eggs.getId() + ":0",   // DONE item → tap to reopen
                ListViewService.ADD_PREFIX + list.getId(),
                ListViewService.MEMBERS_PREFIX + list.getId(),
                ListViewService.MORE_PREFIX + list.getId(),
                ListViewService.BACK);
    }

    @Test
    void show_addsSaveForLaterButton_forTodoItemsOnly() {
        final Task milk = task("Milk", TaskStatus.TODO);
        final Task eggs = task("Eggs", TaskStatus.DONE);
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));
        when(taskService.activeTasks(list.getId())).thenReturn(List.of(milk, eggs));

        service.show(user, MESSAGE_ID, list.getId(), 0);

        final List<String> data = callbackData(capturedKeyboard());
        // The actionable item gets a 📌 Save-for-later; the DONE item does not.
        assertThat(data).contains(ListViewService.SAVE_PREFIX + milk.getId() + ":0");
        assertThat(data).doesNotContain(ListViewService.SAVE_PREFIX + eggs.getId() + ":0");
    }

    @Test
    void show_returnsEmpty_andTouchesNothing_whenListIsGone() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.empty());

        assertThat(service.show(user, MESSAGE_ID, list.getId(), 0)).isEmpty();

        verifyNoInteractions(conversationState);
        verify(gateway, never()).editMarkdown(anyLong(), anyInt(), anyString(), any());
    }

    @Test
    void show_rendersEmptyState_whenListHasNoItems() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));
        when(taskService.activeTasks(list.getId())).thenReturn(List.of());

        service.show(user, MESSAGE_ID, list.getId(), 0);

        final ArgumentCaptor<String> text = ArgumentCaptor.captor();
        verify(gateway).editMarkdown(eq(CHAT_ID), eq(MESSAGE_ID), text.capture(), any());
        assertThat(text.getValue()).contains("No items yet");
        // Only the control rows — no toggle buttons.
        final List<String> data = callbackData(capturedKeyboard());
        assertThat(data).containsExactly(ListViewService.ADD_PREFIX + list.getId(),
                ListViewService.MEMBERS_PREFIX + list.getId(), ListViewService.MORE_PREFIX + list.getId(),
                ListViewService.BACK);
    }

    @Test
    void show_firstPage_holdsTwentyItems_andOnlyANextPager() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));
        when(taskService.activeTasks(list.getId())).thenReturn(tasks(25));

        service.show(user, MESSAGE_ID, list.getId(), 0);

        final List<String> data = callbackData(capturedKeyboard());
        assertThat(data.stream().filter(d -> d.startsWith(ListViewService.DONE_PREFIX)).count()).isEqualTo(20);
        assertThat(data).contains(ListViewService.PAGE_PREFIX + list.getId() + ":1");
        assertThat(data).noneMatch(d -> d.startsWith(ListViewService.PAGE_PREFIX + list.getId() + ":-"));
    }

    @Test
    void show_lastPage_holdsTheRemainder_andOnlyAPrevPager() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));
        when(taskService.activeTasks(list.getId())).thenReturn(tasks(25));

        service.show(user, MESSAGE_ID, list.getId(), 1);

        final List<String> data = callbackData(capturedKeyboard());
        assertThat(data.stream().filter(d -> d.startsWith(ListViewService.DONE_PREFIX)).count()).isEqualTo(5);
        assertThat(data).contains(ListViewService.PAGE_PREFIX + list.getId() + ":0");
    }

    private List<Task> tasks(final int count) {
        final List<Task> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            tasks.add(task("Item " + i, TaskStatus.TODO));
        }
        return tasks;
    }
}

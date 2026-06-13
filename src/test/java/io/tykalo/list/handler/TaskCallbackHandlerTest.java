package io.tykalo.list.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.tykalo.list.Task;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskService;
import io.tykalo.list.TaskService.TaskToggle;
import io.tykalo.list.TaskStatus;
import io.tykalo.user.User;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

@ExtendWith(MockitoExtension.class)
class TaskCallbackHandlerTest {

    private static final long CHAT_ID = 555L;

    @Mock
    private TaskService taskService;

    private TaskCallbackHandler handler;

    private TaskList list;

    @BeforeEach
    void setUp() {
        handler = new TaskCallbackHandler(taskService);
        final User owner = User.create(1L, "owner", ZoneId.of("Europe/Kyiv"), "uk");
        owner.setId(UUID.randomUUID());
        list = TaskList.checklist(owner, "Groceries");
        list.setId(UUID.randomUUID());
    }

    @Test
    void markDone_togglesTask_andReturnsDoneToast() {
        final UUID taskId = UUID.randomUUID();
        when(taskService.markDone(taskId)).thenReturn(new TaskToggle(taskInList(taskId), true));

        final Optional<String> toast = handler.handle(callback("task:done:" + taskId));

        // The live message re-renders off the ListChangedEvent that markDone fires, not from here.
        assertThat(toast).contains("Done!");
        verify(taskService).markDone(taskId);
    }

    @Test
    void undo_reopensTask_andReturnsReopenedToast() {
        final UUID taskId = UUID.randomUUID();
        when(taskService.reopen(taskId)).thenReturn(new TaskToggle(taskInList(taskId), true));

        final Optional<String> toast = handler.handle(callback("task:undo:" + taskId));

        assertThat(toast).contains("Reopened");
        verify(taskService).reopen(taskId);
    }

    @Test
    void malformedTaskId_isClaimedWithToast_butTouchesNoServices() {
        final Optional<String> toast = handler.handle(callback("task:done:not-a-uuid"));

        assertThat(toast).contains("Unknown task");
        verifyNoInteractions(taskService);
    }

    @Test
    void unknownCallbackData_isLeftUnclaimed() {
        final Optional<String> toast = handler.handle(callback("noise:42"));

        assertThat(toast).isEmpty();
        verifyNoInteractions(taskService);
    }

    @Test
    void nullCallbackData_isLeftUnclaimed() {
        final Optional<String> toast = handler.handle(callback(null));

        assertThat(toast).isEmpty();
        verifyNoInteractions(taskService);
    }

    private Task taskInList(final UUID taskId) {
        final Task task = Task.create(list, "Buy milk");
        task.setId(taskId);
        task.setStatus(TaskStatus.TODO);
        return task;
    }

    private CallbackQuery callback(final String data) {
        final Message message = new Message();
        message.setMessageId(1);
        message.setChat(new Chat(CHAT_ID, "private"));

        final CallbackQuery query = new CallbackQuery();
        query.setId("cb-id");
        query.setFrom(new org.telegram.telegrambots.meta.api.objects.User(100L, "Test", false));
        query.setMessage(message);
        query.setData(data);
        return query;
    }
}

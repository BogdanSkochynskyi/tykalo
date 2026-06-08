package io.tykalo.nudger.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.list.Task;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskService;
import io.tykalo.nudger.Nudger;
import io.tykalo.nudger.NudgerRepository;
import io.tykalo.nudger.TaskNudgerProposalService;
import io.tykalo.nudger.TaskNudgerService;
import io.tykalo.telegram.CompactUuid;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

@ExtendWith(MockitoExtension.class)
class TaskNudgerCallbackHandlerTest {

    private static final long CHAT_ID = 555L;

    @Mock
    private TaskNudgerService taskNudgerService;

    @Mock
    private TaskService taskService;

    @Mock
    private NudgerRepository nudgerRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private TaskNudgerCallbackHandler handler;

    private final User owner = user(CHAT_ID, "owner");

    private static User user(final long chatId, final String username) {
        final User user = User.create(chatId, username, ZoneOffset.UTC, "en");
        user.setId(UUID.randomUUID());
        return user;
    }

    private Task projectTaskOf(final User taskOwner) {
        final TaskList list = TaskList.project(taskOwner, "Launch");
        list.setId(UUID.randomUUID());
        final Task task = Task.create(list, "Ship v2");
        task.setId(UUID.randomUUID());
        return task;
    }

    private void clickerIsOwner() {
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.of(owner));
    }

    @Test
    void assign_pinsNudger_andToastsHandle() {
        final Task task = projectTaskOf(owner);
        final User alice = user(2L, "alice");
        final Nudger pair = Nudger.invite(owner, alice);
        pair.setId(UUID.randomUUID());
        clickerIsOwner();
        when(taskService.find(task.getId())).thenReturn(Optional.of(task));
        when(nudgerRepository.findById(pair.getId())).thenReturn(Optional.of(pair));
        when(userRepository.findById(alice.getId())).thenReturn(Optional.of(alice));

        final Optional<String> toast = handler.handle(callback(
                TaskNudgerProposalService.ASSIGN_PREFIX
                        + CompactUuid.encode(task.getId()) + ":" + CompactUuid.encode(pair.getId())));

        assertThat(toast.orElseThrow()).contains("@alice");
        verify(taskNudgerService).assignNudger(task.getId(), pair.getId());
    }

    @Test
    void keepPrivate_makesTaskPrivate() {
        final Task task = projectTaskOf(owner);
        clickerIsOwner();
        when(taskService.find(task.getId())).thenReturn(Optional.of(task));

        final Optional<String> toast = handler.handle(callback(
                TaskNudgerProposalService.PRIVATE_PREFIX + CompactUuid.encode(task.getId())));

        assertThat(toast.orElseThrow()).contains("Private");
        verify(taskNudgerService).makePrivate(task.getId());
    }

    @Test
    void useAll_resetsToDefault() {
        final Task task = projectTaskOf(owner);
        clickerIsOwner();
        when(taskService.find(task.getId())).thenReturn(Optional.of(task));

        final Optional<String> toast = handler.handle(callback(
                TaskNudgerProposalService.DEFAULT_PREFIX + CompactUuid.encode(task.getId())));

        assertThat(toast.orElseThrow()).contains("active Nudgers");
        verify(taskNudgerService).useDefault(task.getId());
    }

    @Test
    void rejects_whenTaskBelongsToSomeoneElse() {
        final Task task = projectTaskOf(user(99L, "stranger"));
        clickerIsOwner();
        when(taskService.find(task.getId())).thenReturn(Optional.of(task));

        final Optional<String> toast = handler.handle(callback(
                TaskNudgerProposalService.PRIVATE_PREFIX + CompactUuid.encode(task.getId())));

        assertThat(toast.orElseThrow()).contains("isn't yours");
        verify(taskNudgerService, never()).makePrivate(any());
    }

    @Test
    void unparseableId_isClaimedWithToast() {
        final Optional<String> toast = handler.handle(callback(
                TaskNudgerProposalService.PRIVATE_PREFIX + "not-a-real-token"));

        assertThat(toast).contains("Unknown task");
        verify(taskNudgerService, never()).makePrivate(any());
    }

    @Test
    void unknownCallbackData_isLeftUnclaimed() {
        assertThat(handler.handle(callback("noise:42"))).isEmpty();
    }

    private CallbackQuery callback(final String data) {
        final Message message = new Message();
        message.setMessageId(1);
        message.setChat(new Chat(CHAT_ID, "private"));

        final CallbackQuery query = new CallbackQuery();
        query.setId("cb-id");
        query.setFrom(new org.telegram.telegrambots.meta.api.objects.User(CHAT_ID, "Owner", false));
        query.setMessage(message);
        query.setData(data);
        return query;
    }
}

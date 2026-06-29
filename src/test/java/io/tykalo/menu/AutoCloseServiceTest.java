package io.tykalo.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.tykalo.list.ListLifecycleService;
import io.tykalo.list.ListMember;
import io.tykalo.list.ListMemberRole;
import io.tykalo.list.ListMemberService;
import io.tykalo.list.ListService;
import io.tykalo.list.ListStatus;
import io.tykalo.list.ListType;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskService;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

@ExtendWith(MockitoExtension.class)
class AutoCloseServiceTest {

    private static final UUID LIST_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final long OWNER_CHAT = 100L;
    private static final int MESSAGE_ID = 7;

    @Mock
    private ListService listService;

    @Mock
    private TaskService taskService;

    @Mock
    private ListLifecycleService lifecycleService;

    @Mock
    private ListMemberService listMemberService;

    @Mock
    private CloseListService closeListService;

    @Mock
    private AutoCloseSuppressionService suppressionService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TelegramMessageGateway gateway;

    @InjectMocks
    private AutoCloseService service;

    private User owner() {
        final User user = User.create(OWNER_CHAT, "owner", ZoneId.of("Europe/Kyiv"), "en");
        user.setId(OWNER_ID);
        return user;
    }

    private TaskList list(final ListType type, final boolean autoClose, final ListStatus status) {
        final TaskList list = TaskList.of(owner(), "Groceries", type);
        list.setId(LIST_ID);
        list.setAutoClose(autoClose);
        list.setStatus(status);
        return list;
    }

    @Test
    void evaluate_silentlyCloses_whenAutoCloseOnAndAllDone() {
        when(listService.getActiveById(LIST_ID))
                .thenReturn(Optional.of(list(ListType.CHECKLIST, true, ListStatus.ACTIVE)));
        when(taskService.counts(LIST_ID)).thenReturn(new TaskService.Counts(3, 3));

        service.evaluate(LIST_ID);

        verify(lifecycleService).markCompleted(OWNER_ID, LIST_ID);
        verify(gateway, never()).sendMarkdown(eq(OWNER_CHAT), anyString(), any());
    }

    @Test
    void evaluate_prompts_whenAutoCloseOffAndAllDone() {
        when(listService.getActiveById(LIST_ID))
                .thenReturn(Optional.of(list(ListType.CHECKLIST, false, ListStatus.ACTIVE)));
        when(taskService.counts(LIST_ID)).thenReturn(new TaskService.Counts(2, 2));
        when(suppressionService.isSuppressed(LIST_ID)).thenReturn(false);
        when(listMemberService.activeMembers(LIST_ID)).thenReturn(List.of());
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner()));

        service.evaluate(LIST_ID);

        verify(gateway).sendMarkdown(eq(OWNER_CHAT), anyString(), any(InlineKeyboardMarkup.class));
        verify(lifecycleService, never()).markCompleted(any(), any());
    }

    @Test
    void evaluate_doesNothing_whenNotAllDone() {
        when(listService.getActiveById(LIST_ID))
                .thenReturn(Optional.of(list(ListType.CHECKLIST, false, ListStatus.ACTIVE)));
        when(taskService.counts(LIST_ID)).thenReturn(new TaskService.Counts(3, 1));

        service.evaluate(LIST_ID);

        verify(lifecycleService, never()).markCompleted(any(), any());
        verifyNoInteractions(gateway, suppressionService);
    }

    @Test
    void evaluate_doesNothing_whenListHasNoItems() {
        when(listService.getActiveById(LIST_ID))
                .thenReturn(Optional.of(list(ListType.CHECKLIST, true, ListStatus.ACTIVE)));
        when(taskService.counts(LIST_ID)).thenReturn(new TaskService.Counts(0, 0));

        service.evaluate(LIST_ID);

        verify(lifecycleService, never()).markCompleted(any(), any());
        verifyNoInteractions(gateway);
    }

    @Test
    void evaluate_skips_routineType() {
        when(listService.getActiveById(LIST_ID))
                .thenReturn(Optional.of(list(ListType.ROUTINE, true, ListStatus.ACTIVE)));

        service.evaluate(LIST_ID);

        verifyNoInteractions(taskService, lifecycleService, gateway);
    }

    @Test
    void evaluate_skips_inboxType() {
        when(listService.getActiveById(LIST_ID))
                .thenReturn(Optional.of(list(ListType.INBOX, true, ListStatus.ACTIVE)));

        service.evaluate(LIST_ID);

        verifyNoInteractions(taskService, lifecycleService, gateway);
    }

    @Test
    void evaluate_skips_whenPromptSuppressed() {
        when(listService.getActiveById(LIST_ID))
                .thenReturn(Optional.of(list(ListType.CHECKLIST, false, ListStatus.ACTIVE)));
        when(taskService.counts(LIST_ID)).thenReturn(new TaskService.Counts(2, 2));
        when(suppressionService.isSuppressed(LIST_ID)).thenReturn(true);

        service.evaluate(LIST_ID);

        verifyNoInteractions(gateway);
        verify(lifecycleService, never()).markCompleted(any(), any());
    }

    @Test
    void evaluate_skips_whenListGone() {
        when(listService.getActiveById(LIST_ID)).thenReturn(Optional.empty());

        service.evaluate(LIST_ID);

        verifyNoInteractions(taskService, lifecycleService, gateway);
    }

    @Test
    void evaluate_skips_whenListNotActive() {
        when(listService.getActiveById(LIST_ID))
                .thenReturn(Optional.of(list(ListType.CHECKLIST, true, ListStatus.COMPLETED)));

        service.evaluate(LIST_ID);

        verifyNoInteractions(taskService, lifecycleService, gateway);
    }

    @Test
    void evaluate_promptsOwnerAndEditors_butNotMembers() {
        final UUID editorId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        final UUID memberId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        final User editor = User.create(200L, "editor", ZoneId.of("Europe/Kyiv"), "en");
        editor.setId(editorId);

        when(listService.getActiveById(LIST_ID))
                .thenReturn(Optional.of(list(ListType.PROJECT, false, ListStatus.ACTIVE)));
        when(taskService.counts(LIST_ID)).thenReturn(new TaskService.Counts(1, 1));
        when(suppressionService.isSuppressed(LIST_ID)).thenReturn(false);
        when(listMemberService.activeMembers(LIST_ID)).thenReturn(List.of(
                ListMember.of(LIST_ID, editorId, ListMemberRole.EDITOR),
                ListMember.of(LIST_ID, memberId, ListMemberRole.MEMBER)));
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner()));
        when(userRepository.findById(editorId)).thenReturn(Optional.of(editor));

        service.evaluate(LIST_ID);

        verify(gateway).sendMarkdown(eq(OWNER_CHAT), anyString(), any());
        verify(gateway).sendMarkdown(eq(200L), anyString(), any());
        verify(userRepository, never()).findById(memberId);
    }

    @Test
    void confirmClose_delegatesToCloseListService() {
        final User user = owner();
        when(closeListService.confirmClose(user, MESSAGE_ID, LIST_ID)).thenReturn(Optional.of("✅ List closed"));

        assertThat(service.confirmClose(user, MESSAGE_ID, LIST_ID)).contains("✅ List closed");
    }

    @Test
    void keepOpen_suppressesTheList_andEditsThePromptMessage() {
        final User user = owner();
        when(listService.getActiveById(LIST_ID))
                .thenReturn(Optional.of(list(ListType.CHECKLIST, false, ListStatus.ACTIVE)));

        final Optional<String> toast = service.keepOpen(user, MESSAGE_ID, LIST_ID);

        assertThat(toast).get().asString().contains("Keeping it open");
        verify(suppressionService).suppress(LIST_ID);
        verify(gateway).editMarkdown(eq(OWNER_CHAT), eq(MESSAGE_ID), anyString(), eq(null));
    }
}

package io.tykalo.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.list.ListPermissionService;
import io.tykalo.list.ListService;
import io.tykalo.list.ListStatus;
import io.tykalo.list.ListType;
import io.tykalo.list.TaskList;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.ConversationStateService;
import io.tykalo.user.User;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TagsServiceTest {

    private static final long CHAT_ID = 100L;
    private static final int MESSAGE_ID = 7;

    @Mock
    private ListService listService;

    @Mock
    private ListPermissionService permissionService;

    @Mock
    private ListSettingsService listSettingsService;

    @Mock
    private ConversationStateService conversationState;

    @Mock
    private TelegramMessageGateway gateway;

    @InjectMocks
    private TagsService service;

    private final User user = user();
    private final TaskList list = list();

    private User user() {
        final User u = User.create(CHAT_ID, "owner", ZoneId.of("Europe/Kyiv"), "en");
        u.setId(UUID.randomUUID());
        return u;
    }

    private TaskList list() {
        final TaskList l = TaskList.of(user, "Groceries", ListType.CHECKLIST);
        l.setId(UUID.randomUUID());
        l.setStatus(ListStatus.ACTIVE);
        return l;
    }

    private void editable() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));
        when(permissionService.canEditList(user.getId(), list.getId())).thenReturn(true);
    }

    @Test
    void open_rendersTheScreen_andEntersEditingTagsState() {
        editable();

        assertThat(service.open(user, MESSAGE_ID, list.getId())).contains("Groceries");

        verify(conversationState).setState(user.getId(), new ConversationState.EditingListTags(list.getId()));
        verify(gateway).editMarkdown(eq(CHAT_ID), eq(MESSAGE_ID), anyString(), any());
    }

    @Test
    void open_returnsEmpty_whenUserCannotEdit() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));
        when(permissionService.canEditList(user.getId(), list.getId())).thenReturn(false);

        assertThat(service.open(user, MESSAGE_ID, list.getId())).isEmpty();
        verify(gateway, never()).editMarkdown(eq(CHAT_ID), eq(MESSAGE_ID), anyString(), any());
    }

    @Test
    void open_returnsEmpty_whenListGone() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.empty());

        assertThat(service.open(user, MESSAGE_ID, list.getId())).isEmpty();
    }

    @Test
    void promptAdd_entersAddingTagState_carryingTheMessageId() {
        editable();

        assertThat(service.promptAdd(user, MESSAGE_ID, list.getId())).contains("Groceries");

        verify(conversationState).setState(user.getId(),
                new ConversationState.AddingTag(list.getId(), MESSAGE_ID));
        verify(gateway).editMarkdown(eq(CHAT_ID), eq(MESSAGE_ID), anyString(), any());
    }

    @Test
    void quickAdd_addsTheSuggestion_andReRenders() {
        editable();
        when(listService.addTag(user.getId(), list.getId(), "shopping")).thenReturn(list);

        final Optional<String> toast = service.quickAdd(user, MESSAGE_ID, list.getId(), 0);

        assertThat(toast).get().asString().contains("shopping");
        verify(listService).addTag(user.getId(), list.getId(), "shopping");
        verify(gateway).editMarkdown(eq(CHAT_ID), eq(MESSAGE_ID), anyString(), any());
    }

    @Test
    void quickAdd_doesNotReAdd_whenSuggestionAlreadyPresent() {
        list.getTags().add("shopping");
        editable();

        final Optional<String> toast = service.quickAdd(user, MESSAGE_ID, list.getId(), 0);

        assertThat(toast).get().asString().contains("Already");
        verify(listService, never()).addTag(any(), any(), anyString());
    }

    @Test
    void confirmRemove_rendersConfirmation_withoutRemoving() {
        list.getTags().add("shopping");
        editable();

        assertThat(service.confirmRemove(user, MESSAGE_ID, list.getId(), 0)).contains("Groceries");

        verify(listService, never()).removeTag(any(), any(), anyString());
        verify(gateway).editMarkdown(eq(CHAT_ID), eq(MESSAGE_ID), anyString(), any());
    }

    @Test
    void remove_dropsTheTag_andReRenders() {
        list.getTags().add("shopping");
        editable();
        when(listService.removeTag(user.getId(), list.getId(), "shopping")).thenReturn(list);

        final Optional<String> toast = service.remove(user, MESSAGE_ID, list.getId(), 0);

        assertThat(toast).get().asString().contains("shopping");
        verify(listService).removeTag(user.getId(), list.getId(), "shopping");
    }

    @Test
    void remove_isSafe_whenIndexStale() {
        editable();

        final Optional<String> toast = service.remove(user, MESSAGE_ID, list.getId(), 5);

        assertThat(toast).get().asString().contains("no longer there");
        verify(listService, never()).removeTag(any(), any(), anyString());
    }

    @Test
    void submit_addsNormalizedTag_andReturnsToEditingTagsState() {
        editable();
        when(listService.addTag(user.getId(), list.getId(), "work")).thenReturn(list);
        final var state = new ConversationState.AddingTag(list.getId(), MESSAGE_ID);

        assertThat(service.submit(user, state, "  Work  ")).isEmpty();

        verify(listService).addTag(user.getId(), list.getId(), "work");
        verify(conversationState).setState(user.getId(), new ConversationState.EditingListTags(list.getId()));
    }

    @Test
    void submit_rejectsDuplicate_keepsPrompt_withoutAdding() {
        list.getTags().add("work");
        editable();
        final var state = new ConversationState.AddingTag(list.getId(), MESSAGE_ID);

        assertThat(service.submit(user, state, "work")).isEmpty();

        verify(listService, never()).addTag(any(), any(), anyString());
        verify(conversationState, never())
                .setState(user.getId(), new ConversationState.EditingListTags(list.getId()));
        verify(gateway).editMarkdown(eq(CHAT_ID), eq(MESSAGE_ID), anyString(), any());
    }

    @Test
    void submit_rejectsInvalidInput_keepsPrompt_withoutAdding() {
        editable();
        final var state = new ConversationState.AddingTag(list.getId(), MESSAGE_ID);

        assertThat(service.submit(user, state, "to-do!")).isEmpty();

        verify(listService, never()).addTag(any(), any(), anyString());
        verify(gateway).editMarkdown(eq(CHAT_ID), eq(MESSAGE_ID), anyString(), any());
    }

    @Test
    void submit_endsFlow_whenListGoneMidway() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.empty());
        final var state = new ConversationState.AddingTag(list.getId(), MESSAGE_ID);

        assertThat(service.submit(user, state, "work")).get().asString().contains("no longer available");
        verify(conversationState).setState(user.getId(), new ConversationState.Idle());
    }

    @Test
    void back_returnsToTheSettingsScreen() {
        when(listSettingsService.open(user, MESSAGE_ID, list.getId())).thenReturn(Optional.of("Groceries"));

        assertThat(service.back(user, MESSAGE_ID, list.getId())).contains("Groceries");
    }
}

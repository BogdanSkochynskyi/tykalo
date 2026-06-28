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
class ListSettingsServiceTest {

    private static final long CHAT_ID = 100L;
    private static final int MESSAGE_ID = 7;

    @Mock
    private ListService listService;

    @Mock
    private ListPermissionService permissionService;

    @Mock
    private ListViewService listViewService;

    @Mock
    private TelegramMessageGateway gateway;

    @InjectMocks
    private ListSettingsService service;

    private final User user = user();
    private final TaskList list = list(false);

    private User user() {
        final User u = User.create(CHAT_ID, "owner", ZoneId.of("Europe/Kyiv"), "en");
        u.setId(UUID.randomUUID());
        return u;
    }

    private TaskList list(final boolean autoClose) {
        final TaskList l = TaskList.of(user, "Groceries", ListType.CHECKLIST);
        l.setId(UUID.randomUUID());
        l.setAutoClose(autoClose);
        l.setStatus(ListStatus.ACTIVE);
        return l;
    }

    @Test
    void open_rendersTheScreen_forAnEditorOfAnActiveList() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));
        when(permissionService.canEditList(user.getId(), list.getId())).thenReturn(true);

        assertThat(service.open(user, MESSAGE_ID, list.getId())).contains("Groceries");
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
    void toggleAutoClose_flipsTheFlag_andReRenders() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));
        when(permissionService.canEditList(user.getId(), list.getId())).thenReturn(true);
        when(listService.setAutoClose(user.getId(), list.getId(), true)).thenReturn(list(true));

        final Optional<String> toast = service.toggleAutoClose(user, MESSAGE_ID, list.getId());

        assertThat(toast).get().asString().contains("Auto-close on");
        verify(listService).setAutoClose(user.getId(), list.getId(), true);
        verify(gateway).editMarkdown(eq(CHAT_ID), eq(MESSAGE_ID), anyString(), any());
    }

    @Test
    void back_returnsToTheListView() {
        when(listViewService.show(user, MESSAGE_ID, list.getId(), 0)).thenReturn(Optional.of("Groceries"));

        assertThat(service.back(user, MESSAGE_ID, list.getId())).contains("Groceries");
    }
}

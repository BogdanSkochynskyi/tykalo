package io.tykalo.list;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.user.User;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ListMessageServiceTest {

    private static final long CHAT_ID = 42L;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private ListMessageRepository listMessageRepository;

    @Mock
    private TelegramMessageGateway gateway;

    private ListMessageService service;

    private TaskList list;

    @BeforeEach
    void setUp() {
        service = new ListMessageService(taskRepository, listMessageRepository, new ListRenderer(), gateway);
        final User owner = User.create(1L, "owner", ZoneId.of("Europe/Kyiv"), "uk");
        owner.setId(UUID.randomUUID());
        list = TaskList.checklist(owner, "Groceries");
        list.setId(UUID.randomUUID());
        when(taskRepository.findByListIdAndArchivedAtIsNullOrderByCreatedAtAsc(list.getId()))
                .thenReturn(List.of(task("Buy milk")));
    }

    private Task task(final String title) {
        final Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setTitle(title);
        task.setStatus(TaskStatus.TODO);
        return task;
    }

    @Test
    void publish_sendsNewMessage_andStoresMessageId_whenNoneExists() {
        // Arrange
        when(listMessageRepository.findByListIdAndTgChatId(list.getId(), CHAT_ID)).thenReturn(Optional.empty());
        when(gateway.sendMarkdownDirect(eq(CHAT_ID), anyString(), any())).thenReturn(Optional.of(555));

        // Act
        service.publish(list, CHAT_ID);

        // Assert — the composed text carries the list-name header and rendered body
        final ArgumentCaptor<String> text = ArgumentCaptor.captor();
        verify(gateway).sendMarkdownDirect(eq(CHAT_ID), text.capture(), any());
        assertThat(text.getValue()).isEqualTo("*Groceries*\n\n1\\. Buy milk");
        verify(gateway, never()).editMarkdown(anyLong(), anyInt(), anyString(), any());

        final ArgumentCaptor<ListMessage> saved = ArgumentCaptor.captor();
        verify(listMessageRepository).save(saved.capture());
        assertThat(saved.getValue().getListId()).isEqualTo(list.getId());
        assertThat(saved.getValue().getTgChatId()).isEqualTo(CHAT_ID);
        assertThat(saved.getValue().getTgMessageId()).isEqualTo(555L);
    }

    @Test
    void publish_editsExistingMessageInPlace_whenOneExists() {
        // Arrange
        final ListMessage existing = ListMessage.of(list.getId(), CHAT_ID, 777L);
        when(listMessageRepository.findByListIdAndTgChatId(list.getId(), CHAT_ID))
                .thenReturn(Optional.of(existing));

        // Act
        service.publish(list, CHAT_ID);

        // Assert
        verify(gateway).editMarkdown(eq(CHAT_ID), eq(777), eq("*Groceries*\n\n1\\. Buy milk"), any());
        verify(gateway, never()).sendMarkdownDirect(anyLong(), anyString(), any());
        verify(listMessageRepository).save(existing);
        assertThat(existing.getLastRenderedAt()).isNotNull();
    }

    @Test
    void publish_persistsNothing_whenSendYieldsNoMessageId() {
        // Arrange — e.g. the no-op gateway when polling is disabled
        when(listMessageRepository.findByListIdAndTgChatId(list.getId(), CHAT_ID)).thenReturn(Optional.empty());
        when(gateway.sendMarkdownDirect(eq(CHAT_ID), anyString(), any())).thenReturn(Optional.empty());

        // Act
        service.publish(list, CHAT_ID);

        // Assert
        verify(listMessageRepository, never()).save(any());
    }
}

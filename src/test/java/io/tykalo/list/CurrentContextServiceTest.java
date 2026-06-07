package io.tykalo.list;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class CurrentContextServiceTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private ListService listService;

    private CurrentContextService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CurrentContextService(redis, listService);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void set_writesListIdUnderUserKey_withTwentyFourHourTtl() {
        // Arrange
        final UUID listId = UUID.randomUUID();

        // Act
        service.set(userId, listId);

        // Assert
        verify(valueOps).set("user:" + userId + ":currentList", listId.toString(), Duration.ofHours(24));
    }

    @Test
    void get_parsesStoredUuid() {
        final UUID listId = UUID.randomUUID();
        when(valueOps.get("user:" + userId + ":currentList")).thenReturn(listId.toString());

        assertThat(service.get(userId)).contains(listId);
    }

    @Test
    void get_returnsEmpty_whenNothingStored() {
        when(valueOps.get("user:" + userId + ":currentList")).thenReturn(null);

        assertThat(service.get(userId)).isEmpty();
    }

    @Test
    void clear_deletesUserKey() {
        service.clear(userId);

        verify(redis).delete("user:" + userId + ":currentList");
    }

    @Test
    void resolveCurrentList_returnsStoredList_whenActive() {
        // Arrange
        final TaskList current = list(ListType.PROJECT);
        when(valueOps.get(keyFor(userId))).thenReturn(current.getId().toString());
        when(listService.getActiveById(current.getId())).thenReturn(Optional.of(current));

        // Act
        final Optional<TaskList> resolved = service.resolveCurrentList(userId);

        // Assert
        assertThat(resolved).contains(current);
    }

    @Test
    void resolveCurrentList_clearsStaleKeyAndFallsBackToInbox_whenStoredListArchived() {
        // Arrange
        final UUID archivedId = UUID.randomUUID();
        final TaskList inbox = list(ListType.INBOX);
        when(valueOps.get(keyFor(userId))).thenReturn(archivedId.toString());
        when(listService.getActiveById(archivedId)).thenReturn(Optional.empty());
        when(listService.findInbox(userId)).thenReturn(Optional.of(inbox));

        // Act
        final Optional<TaskList> resolved = service.resolveCurrentList(userId);

        // Assert
        assertThat(resolved).contains(inbox);
        verify(redis).delete(keyFor(userId));
    }

    @Test
    void resolveCurrentList_fallsBackToInbox_whenNoCurrentSet() {
        final TaskList inbox = list(ListType.INBOX);
        when(valueOps.get(keyFor(userId))).thenReturn(null);
        when(listService.findInbox(userId)).thenReturn(Optional.of(inbox));

        assertThat(service.resolveCurrentList(userId)).contains(inbox);
    }

    private String keyFor(final UUID id) {
        return "user:" + id + ":currentList";
    }

    private TaskList list(final ListType type) {
        final TaskList list = new TaskList();
        list.setId(UUID.randomUUID());
        list.setOwnerId(userId);
        list.setName(type.name());
        list.setType(type);
        return list;
    }
}

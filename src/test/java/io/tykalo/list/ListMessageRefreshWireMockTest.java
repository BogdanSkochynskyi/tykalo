package io.tykalo.list;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.tykalo.telegram.TelegramApiMessageGateway;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.telegram.ratelimit.MessageQueueService;
import io.tykalo.user.User;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.TelegramUrl;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Verifies that a list change drives a real Telegram <em>Edit Message</em> HTTP call. It wires the
 * production {@link TelegramApiMessageGateway} to a real {@link OkHttpTelegramClient} pointed at a
 * WireMock server standing in for {@code api.telegram.org}, then triggers the same refresh that the
 * {@code ListChangedEvent} listener runs and asserts an {@code editMessageText} request reached the
 * API. Repositories are mocked so the test stays a focused HTTP-boundary integration test (no DB) and
 * runs without Docker.
 */
class ListMessageRefreshWireMockTest {

    private static final String TOKEN = "TEST-TOKEN";
    // The telegrambots client lower-cases the method name in the request path.
    private static final String EDIT_PATH = "/bot" + TOKEN + "/editmessagetext";
    private static final long CHAT_ID = 42L;
    private static final int MESSAGE_ID = 777;

    private WireMockServer wireMock;

    private final TaskRepository taskRepository = mock(TaskRepository.class);
    private final ListRepository listRepository = mock(ListRepository.class);
    private final ListMessageRepository listMessageRepository = mock(ListMessageRepository.class);
    private final MessageQueueService messageQueue = mock(MessageQueueService.class);

    @BeforeEach
    void startWireMock() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        wireMock.stubFor(post(urlPathEqualTo(EDIT_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"ok\":true,\"result\":{\"message_id\":" + MESSAGE_ID
                        + ",\"date\":1,\"chat\":{\"id\":" + CHAT_ID + ",\"type\":\"private\"},\"text\":\"ok\"}}")));
    }

    @AfterEach
    void stopWireMock() {
        wireMock.stop();
    }

    @Test
    void listChange_editsTheLiveMessage_throughTheRealTelegramApi() {
        // Arrange — a list with one live message in one chat
        final UUID listId = UUID.randomUUID();
        final User owner = User.create(1L, "owner", ZoneId.of("Europe/Kyiv"), "uk");
        owner.setId(UUID.randomUUID());
        final TaskList list = TaskList.checklist(owner, "Groceries");
        list.setId(listId);
        when(listMessageRepository.findByListId(listId))
                .thenReturn(List.of(ListMessage.of(listId, CHAT_ID, MESSAGE_ID)));
        when(listRepository.findById(listId)).thenReturn(Optional.of(list));
        when(taskRepository.findByListIdAndArchivedAtIsNullOrderByCreatedAtAsc(listId))
                .thenReturn(List.of(task("Buy milk")));

        final ListMessageService service = new ListMessageService(
                taskRepository, listRepository, listMessageRepository, new ListRenderer(), gateway());

        // Act — the same path the AFTER_COMMIT listener runs
        service.onListChanged(new ListChangedEvent(listId));

        // Assert — a real editMessageText call hit the Telegram API
        wireMock.verify(1, postRequestedFor(urlPathEqualTo(EDIT_PATH)));
    }

    private TelegramMessageGateway gateway() {
        final TelegramUrl url = TelegramUrl.builder()
                .schema("http")
                .host("localhost")
                .port(wireMock.port())
                .testServer(false)
                .build();
        final TelegramClient client = new OkHttpTelegramClient(TOKEN, url);
        return new TelegramApiMessageGateway(client, messageQueue);
    }

    private Task task(final String title) {
        final Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setTitle(title);
        task.setStatus(TaskStatus.TODO);
        return task;
    }
}

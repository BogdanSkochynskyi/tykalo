package io.tykalo.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.tykalo.telegram.ratelimit.MessageQueueService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@ExtendWith(MockitoExtension.class)
class TelegramApiMessageGatewayTest {

    @Mock
    private TelegramClient telegramClient;

    @Mock
    private MessageQueueService messageQueue;

    private TelegramApiMessageGateway gateway;

    private final InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
            .keyboardRow(new InlineKeyboardRow(
                    InlineKeyboardButton.builder().text("✅ 1").callbackData("task:done:x").build()))
            .build();

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        gateway = new TelegramApiMessageGateway(telegramClient, messageQueue);
    }

    @Test
    void sendMarkdown_enqueuesForPacedDelivery_withoutTouchingTheClient() {
        // Act
        gateway.sendMarkdown(42L, "*Groceries*\n\n1\\. Buy milk", keyboard);

        // Assert — queued, never sent directly
        verify(messageQueue).enqueue(42L, "*Groceries*\n\n1\\. Buy milk", "MarkdownV2", keyboard);
        verifyNoInteractions(telegramClient);
    }

    @Test
    void sendMarkdownDirect_buildsMarkdownV2MessageWithKeyboard_andReturnsMessageId() throws TelegramApiException {
        // Arrange
        final Message sent = new Message();
        sent.setMessageId(555);
        when(telegramClient.execute(any(SendMessage.class))).thenReturn(sent);

        // Act
        final Optional<Integer> id = gateway.sendMarkdownDirect(42L, "*Groceries*\n\n1\\. Buy milk", keyboard);

        // Assert
        assertThat(id).contains(555);
        final ArgumentCaptor<SendMessage> message = ArgumentCaptor.captor();
        verify(telegramClient).execute(message.capture());
        assertThat(message.getValue().getChatId()).isEqualTo("42");
        assertThat(message.getValue().getText()).isEqualTo("*Groceries*\n\n1\\. Buy milk");
        assertThat(message.getValue().getParseMode()).isEqualTo("MarkdownV2");
        assertThat(message.getValue().getReplyMarkup()).isSameAs(keyboard);
        verifyNoInteractions(messageQueue);
    }

    @Test
    void sendMarkdownDirect_returnsEmpty_whenTelegramRejectsTheSend() throws TelegramApiException {
        when(telegramClient.execute(any(SendMessage.class))).thenThrow(new TelegramApiException("boom"));

        final Optional<Integer> id = gateway.sendMarkdownDirect(42L, "text", keyboard);

        assertThat(id).isEmpty();
    }

    @Test
    void editMarkdown_buildsMarkdownV2Edit_targetingTheStoredMessageId() throws TelegramApiException {
        final EditOutcome outcome = gateway.editMarkdown(42L, 777, "*Groceries*\n\n1\\. ~Buy milk~", keyboard);

        assertThat(outcome).isEqualTo(EditOutcome.EDITED);
        final ArgumentCaptor<EditMessageText> edit = ArgumentCaptor.captor();
        verify(telegramClient).execute(edit.capture());
        assertThat(edit.getValue().getChatId()).isEqualTo("42");
        assertThat(edit.getValue().getMessageId()).isEqualTo(777);
        assertThat(edit.getValue().getParseMode()).isEqualTo("MarkdownV2");
        assertThat(edit.getValue().getReplyMarkup()).isSameAs(keyboard);
    }

    @Test
    void editMarkdown_reportsMessageGone_whenTelegramSays400MessageNotFound() throws TelegramApiException {
        final TelegramApiRequestException gone = requestException(400, "Bad Request: message to edit not found");
        when(telegramClient.execute(any(EditMessageText.class))).thenThrow(gone);

        final EditOutcome outcome = gateway.editMarkdown(42L, 777, "text", keyboard);

        assertThat(outcome).isEqualTo(EditOutcome.MESSAGE_GONE);
    }

    @Test
    void editMarkdown_reportsMessageGone_whenChatIsUnreachable() throws TelegramApiException {
        final TelegramApiRequestException blocked = requestException(403, "Forbidden: bot was blocked by the user");
        when(telegramClient.execute(any(EditMessageText.class))).thenThrow(blocked);

        final EditOutcome outcome = gateway.editMarkdown(42L, 777, "text", keyboard);

        assertThat(outcome).isEqualTo(EditOutcome.MESSAGE_GONE);
    }

    @Test
    void editMarkdown_reportsFailedNotGone_whenMessageIsUnchanged() throws TelegramApiException {
        // "not modified" is a 400 too — the message still exists, so its record must be kept.
        final TelegramApiRequestException unchanged = requestException(400, "Bad Request: message is not modified");
        when(telegramClient.execute(any(EditMessageText.class))).thenThrow(unchanged);

        final EditOutcome outcome = gateway.editMarkdown(42L, 777, "text", keyboard);

        assertThat(outcome).isEqualTo(EditOutcome.FAILED);
    }

    @Test
    void editMarkdown_reportsFailedNotGone_whenTheMarkdownCannotBeParsed() throws TelegramApiException {
        // A formatting bug must never be mistaken for a deleted message and drop the record.
        final TelegramApiRequestException parseError = requestException(400, "Bad Request: can't parse entities");
        when(telegramClient.execute(any(EditMessageText.class))).thenThrow(parseError);

        final EditOutcome outcome = gateway.editMarkdown(42L, 777, "text", keyboard);

        assertThat(outcome).isEqualTo(EditOutcome.FAILED);
    }

    @Test
    void editMarkdown_reportsFailed_onTransientErrors() throws TelegramApiException {
        when(telegramClient.execute(any(EditMessageText.class)))
                .thenThrow(new TelegramApiException("network blip"));

        final EditOutcome outcome = gateway.editMarkdown(42L, 777, "text", keyboard);

        assertThat(outcome).isEqualTo(EditOutcome.FAILED);
    }

    private static TelegramApiRequestException requestException(final int code, final String description) {
        final TelegramApiRequestException e = mock(TelegramApiRequestException.class);
        when(e.getErrorCode()).thenReturn(code);
        when(e.getApiResponse()).thenReturn(description);
        return e;
    }

    @Test
    void deleteMessage_buildsDeleteTargetingTheMessageId() throws TelegramApiException {
        gateway.deleteMessage(42L, 888);

        final ArgumentCaptor<DeleteMessage> delete = ArgumentCaptor.captor();
        verify(telegramClient).execute(delete.capture());
        assertThat(delete.getValue().getChatId()).isEqualTo("42");
        assertThat(delete.getValue().getMessageId()).isEqualTo(888);
    }

    @Test
    void deleteMessage_swallowsTelegramErrors_soTheFlowSurvivesAGoneMessage() throws TelegramApiException {
        when(telegramClient.execute(any(DeleteMessage.class)))
                .thenThrow(new TelegramApiException("message to delete not found"));

        // Act + Assert — no exception propagates
        gateway.deleteMessage(42L, 888);
    }

    @Test
    void answerCallback_buildsAnswerCallbackQueryWithToastText() throws TelegramApiException {
        gateway.answerCallback("cb-1", "Done!");

        final ArgumentCaptor<AnswerCallbackQuery> answer = ArgumentCaptor.captor();
        verify(telegramClient).execute(answer.capture());
        assertThat(answer.getValue().getCallbackQueryId()).isEqualTo("cb-1");
        assertThat(answer.getValue().getText()).isEqualTo("Done!");
    }

    @Test
    void answerCallback_swallowsTelegramErrors_soTheFlowSurvives() throws TelegramApiException {
        when(telegramClient.execute(any(AnswerCallbackQuery.class)))
                .thenThrow(new TelegramApiException("query is too old"));

        // Act + Assert — no exception propagates
        gateway.answerCallback("cb-1", "Done!");
    }
}

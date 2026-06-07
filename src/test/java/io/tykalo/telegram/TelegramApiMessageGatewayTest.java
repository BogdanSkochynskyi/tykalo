package io.tykalo.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@ExtendWith(MockitoExtension.class)
class TelegramApiMessageGatewayTest {

    @Mock
    private TelegramClient telegramClient;

    private TelegramApiMessageGateway gateway;

    private final InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
            .keyboardRow(new InlineKeyboardRow(
                    InlineKeyboardButton.builder().text("✅ 1").callbackData("task:done:x").build()))
            .build();

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        gateway = new TelegramApiMessageGateway(telegramClient);
    }

    @Test
    void sendMarkdown_buildsMarkdownV2MessageWithKeyboard_andReturnsMessageId() throws TelegramApiException {
        // Arrange
        final Message sent = new Message();
        sent.setMessageId(555);
        when(telegramClient.execute(any(SendMessage.class))).thenReturn(sent);

        // Act
        final Optional<Integer> id = gateway.sendMarkdown(42L, "*Groceries*\n\n1\\. Buy milk", keyboard);

        // Assert
        assertThat(id).contains(555);
        final ArgumentCaptor<SendMessage> message = ArgumentCaptor.captor();
        verify(telegramClient).execute(message.capture());
        assertThat(message.getValue().getChatId()).isEqualTo("42");
        assertThat(message.getValue().getText()).isEqualTo("*Groceries*\n\n1\\. Buy milk");
        assertThat(message.getValue().getParseMode()).isEqualTo("MarkdownV2");
        assertThat(message.getValue().getReplyMarkup()).isSameAs(keyboard);
    }

    @Test
    void sendMarkdown_returnsEmpty_whenTelegramRejectsTheSend() throws TelegramApiException {
        when(telegramClient.execute(any(SendMessage.class))).thenThrow(new TelegramApiException("boom"));

        final Optional<Integer> id = gateway.sendMarkdown(42L, "text", keyboard);

        assertThat(id).isEmpty();
    }

    @Test
    void editMarkdown_buildsMarkdownV2Edit_targetingTheStoredMessageId() throws TelegramApiException {
        gateway.editMarkdown(42L, 777, "*Groceries*\n\n1\\. ~Buy milk~", keyboard);

        final ArgumentCaptor<EditMessageText> edit = ArgumentCaptor.captor();
        verify(telegramClient).execute(edit.capture());
        assertThat(edit.getValue().getChatId()).isEqualTo("42");
        assertThat(edit.getValue().getMessageId()).isEqualTo(777);
        assertThat(edit.getValue().getParseMode()).isEqualTo("MarkdownV2");
        assertThat(edit.getValue().getReplyMarkup()).isSameAs(keyboard);
    }

    @Test
    void editMarkdown_swallowsTelegramErrors_soTheFlowSurvivesADeletedMessage() throws TelegramApiException {
        when(telegramClient.execute(any(EditMessageText.class)))
                .thenThrow(new TelegramApiException("message to edit not found"));

        // Act + Assert — no exception propagates
        gateway.editMarkdown(42L, 777, "text", keyboard);
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

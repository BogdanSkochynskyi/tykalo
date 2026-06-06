package io.tykalo.telegram;

import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/** Builds Telegram {@link Update} objects for tests without hitting the API. */
public final class TelegramUpdateFixtures {

    private TelegramUpdateFixtures() {
    }

    public static Update command(final String text, final long chatId,
                                 final String username, final String languageCode) {
        final User from = new User(chatId, "Test", false);
        from.setUserName(username);
        from.setLanguageCode(languageCode);

        final Message message = new Message();
        message.setMessageId(1);
        message.setText(text);
        message.setFrom(from);
        message.setChat(new Chat(chatId, "private"));

        final Update update = new Update();
        update.setMessage(message);
        return update;
    }

    public static Update textMessage(final String text) {
        return command(text, 100L, "tester", "en");
    }

    public static Update withoutMessage() {
        final Update update = new Update();
        update.setUpdateId(7);
        return update;
    }
}

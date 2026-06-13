package io.tykalo.menu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.menu.MenuService;
import io.tykalo.telegram.TelegramUpdateFixtures;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.Update;

@ExtendWith(MockitoExtension.class)
class MenuCommandHandlerTest {

    @Mock
    private UserService userService;

    @Mock
    private MenuService menuService;

    @InjectMocks
    private MenuCommandHandler handler;

    @Test
    void menu_showsMainMenu_andStaysSilent() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/menu", 100L, "tester", "en");
        final User user = User.create(100L, "tester", ZoneId.of("Europe/Kyiv"), "en");
        when(userService.findOrCreate(update)).thenReturn(user);

        // Act
        final String reply = handler.menu(update);

        // Assert — the keyboard goes out via the gateway, so the string reply is null
        verify(menuService).showMainMenu(user);
        assertThat(reply).isNull();
    }
}

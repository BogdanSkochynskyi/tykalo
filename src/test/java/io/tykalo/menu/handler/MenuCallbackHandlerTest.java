package io.tykalo.menu.handler;

import static org.assertj.core.api.Assertions.assertThat;

import io.tykalo.menu.MenuService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

class MenuCallbackHandlerTest {

    private final MenuCallbackHandler handler = new MenuCallbackHandler();

    @Test
    void everyMenuButton_isClaimedWithAToast() {
        final List<String> menuActions = List.of(MenuService.MY_LISTS, MenuService.SHARED,
                MenuService.CREATE, MenuService.STATS, MenuService.SETTINGS, MenuService.HELP);

        for (final String action : menuActions) {
            assertThat(handler.handle(callback(action))).as("toast for %s", action).isPresent();
        }
    }

    @Test
    void helpButton_pointsToTheHelpCommand() {
        assertThat(handler.handle(callback(MenuService.HELP))).get().asString().contains("/help");
    }

    @Test
    void createButton_pointsToTheListCreateCommand() {
        assertThat(handler.handle(callback(MenuService.CREATE))).get().asString().contains("/list create");
    }

    @Test
    void nonMenuCallback_isLeftUnclaimed() {
        assertThat(handler.handle(callback("task:done:abc"))).isEmpty();
    }

    @Test
    void unknownMenuAction_isLeftUnclaimed() {
        assertThat(handler.handle(callback("menu:bogus"))).isEmpty();
    }

    @Test
    void nullCallbackData_isLeftUnclaimed() {
        assertThat(handler.handle(callback(null))).isEmpty();
    }

    private CallbackQuery callback(final String data) {
        final CallbackQuery query = new CallbackQuery();
        query.setId("cb-id");
        query.setData(data);
        return query;
    }
}

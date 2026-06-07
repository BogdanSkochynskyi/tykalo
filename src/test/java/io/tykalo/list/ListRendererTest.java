package io.tykalo.list;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

class ListRendererTest {

    private final ListRenderer renderer = new ListRenderer();

    private static Task task(final String title, final TaskStatus status) {
        final Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setTitle(title);
        task.setStatus(status);
        return task;
    }

    @Test
    void render_numbersTodoTasks_oldestFirst() {
        final String text = renderer.render(List.of(
                task("Buy milk", TaskStatus.TODO),
                task("Walk dog", TaskStatus.TODO)));

        assertThat(text).isEqualTo("1\\. Buy milk\n2\\. Walk dog");
    }

    @Test
    void render_strikesThroughDoneTasks() {
        final String text = renderer.render(List.of(
                task("Buy milk", TaskStatus.DONE),
                task("Walk dog", TaskStatus.TODO)));

        assertThat(text).isEqualTo("1\\. ~Buy milk~\n2\\. Walk dog");
    }

    @Test
    void render_escapesMarkdownV2SpecialCharsInTitles() {
        final String text = renderer.render(List.of(task("Fix bug (urgent!) #42", TaskStatus.TODO)));

        // ( ) ! # are all MarkdownV2 specials and must be backslash-escaped.
        assertThat(text).isEqualTo("1\\. Fix bug \\(urgent\\!\\) \\#42");
    }

    @Test
    void render_emptyList_showsPlaceholder() {
        assertThat(renderer.render(List.of())).isEqualTo("_No tasks yet\\._");
    }

    @Test
    void keyboard_hasOneDoneButtonPerTask_carryingTaskDoneCallback() {
        final Task first = task("Buy milk", TaskStatus.TODO);
        final Task second = task("Walk dog", TaskStatus.TODO);

        final InlineKeyboardMarkup keyboard = renderer.keyboard(List.of(first, second));

        final List<InlineKeyboardButton> buttons = keyboard.getKeyboard().stream().flatMap(List::stream).toList();
        assertThat(buttons).extracting(InlineKeyboardButton::getText).containsExactly("✅ 1", "✅ 2");
        assertThat(buttons).extracting(InlineKeyboardButton::getCallbackData)
                .containsExactly("task:done:" + first.getId(), "task:done:" + second.getId());
    }

    @Test
    void keyboard_givesDoneTaskAnUndoButton_carryingTaskUndoCallback() {
        final Task done = task("Buy milk", TaskStatus.DONE);

        final InlineKeyboardMarkup keyboard = renderer.keyboard(List.of(done));

        final InlineKeyboardButton button = keyboard.getKeyboard().get(0).get(0);
        assertThat(button.getText()).isEqualTo("↩️ 1");
        assertThat(button.getCallbackData()).isEqualTo("task:undo:" + done.getId());
    }

    @Test
    void keyboard_mixesDoneAndTodoButtons_byStatus() {
        final Task done = task("Buy milk", TaskStatus.DONE);
        final Task todo = task("Walk dog", TaskStatus.TODO);

        final InlineKeyboardMarkup keyboard = renderer.keyboard(List.of(done, todo));

        final List<InlineKeyboardButton> buttons = keyboard.getKeyboard().stream().flatMap(List::stream).toList();
        assertThat(buttons).extracting(InlineKeyboardButton::getCallbackData)
                .containsExactly("task:undo:" + done.getId(), "task:done:" + todo.getId());
    }

    @Test
    void keyboard_chunksButtonsAtFivePerRow() {
        final List<Task> six = List.of(
                task("a", TaskStatus.TODO), task("b", TaskStatus.TODO), task("c", TaskStatus.TODO),
                task("d", TaskStatus.TODO), task("e", TaskStatus.TODO), task("f", TaskStatus.TODO));

        final InlineKeyboardMarkup keyboard = renderer.keyboard(six);

        assertThat(keyboard.getKeyboard()).hasSize(2);
        assertThat(keyboard.getKeyboard().get(0)).hasSize(5);
        assertThat(keyboard.getKeyboard().get(1)).hasSize(1);
    }

    @Test
    void keyboard_emptyList_hasNoRows() {
        assertThat(renderer.keyboard(List.of()).getKeyboard()).isEmpty();
    }
}

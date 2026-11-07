package cloud.changlu.agentforge.agent.extend.middlewares.todoplan.validation;

import cloud.changlu.agentforge.agent.extend.middlewares.todoplan.model.TodoItem;
import cloud.changlu.agentforge.agent.extend.middlewares.todoplan.model.TodoPriority;
import cloud.changlu.agentforge.agent.extend.middlewares.todoplan.model.TodoStatus;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TodoValidatorTest {

    @Test
    public void shouldAcceptEmptyList() {
        assertTrue(TodoValidator.normalize(new ArrayList<TodoItem>()).isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectMoreThanThirtyItems() {
        List<TodoItem> todos = new ArrayList<TodoItem>();
        for (int i = 0; i < 31; i++) {
            todos.add(
                    new TodoItem(
                            i + 1,
                            "task " + i,
                            TodoStatus.PENDING.getCode(),
                            TodoPriority.LOW.getCode()));
        }
        TodoValidator.normalize(todos);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectTooLongContent() {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i <= TodoValidator.MAX_CONTENT_LENGTH; i++) {
            content.append('a');
        }
        TodoValidator.normalize(
                Arrays.asList(
                        new TodoItem(
                                1,
                                content.toString(),
                                TodoStatus.PENDING.getCode(),
                                TodoPriority.LOW.getCode())));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectMultipleInProgressItems() {
        TodoValidator.normalize(
                Arrays.asList(
                        new TodoItem(
                                1,
                                "a",
                                TodoStatus.IN_PROGRESS.getCode(),
                                TodoPriority.HIGH.getCode()),
                        new TodoItem(
                                2,
                                "b",
                                TodoStatus.IN_PROGRESS.getCode(),
                                TodoPriority.LOW.getCode())));
    }

    @Test
    public void shouldTrimContent() {
        List<TodoItem> normalized =
                TodoValidator.normalize(
                        Arrays.asList(
                                new TodoItem(
                                        1,
                                        "  task  ",
                                        TodoStatus.PENDING.getCode(),
                                        TodoPriority.MEDIUM.getCode())));

        assertEquals("task", normalized.get(0).getContent());
    }

    @Test
    public void shouldSortBySeq() {
        List<TodoItem> normalized =
                TodoValidator.normalize(
                        Arrays.asList(
                                new TodoItem(
                                        2,
                                        "b",
                                        TodoStatus.PENDING.getCode(),
                                        TodoPriority.LOW.getCode()),
                                new TodoItem(
                                        1,
                                        "a",
                                        TodoStatus.PENDING.getCode(),
                                        TodoPriority.HIGH.getCode())));

        assertEquals(Integer.valueOf(1), normalized.get(0).getSeq());
        assertEquals("a", normalized.get(0).getContent());
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectDuplicateSeq() {
        TodoValidator.normalize(
                Arrays.asList(
                        new TodoItem(
                                1, "a", TodoStatus.PENDING.getCode(), TodoPriority.HIGH.getCode()),
                        new TodoItem(
                                1, "b", TodoStatus.PENDING.getCode(), TodoPriority.LOW.getCode())));
    }
}

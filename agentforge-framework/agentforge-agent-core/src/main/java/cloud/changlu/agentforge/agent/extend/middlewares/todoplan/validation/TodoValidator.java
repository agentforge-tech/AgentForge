package cloud.changlu.agentforge.agent.extend.middlewares.todoplan.validation;

import cloud.changlu.agentforge.agent.extend.middlewares.todoplan.model.TodoItem;
import cloud.changlu.agentforge.agent.extend.middlewares.todoplan.model.TodoPriority;
import cloud.changlu.agentforge.agent.extend.middlewares.todoplan.model.TodoStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Todo 计划校验。 */
public final class TodoValidator {

    public static final int MAX_TODOS = 30;
    public static final int MAX_CONTENT_LENGTH = 300;

    private TodoValidator() {}

    public static List<TodoItem> normalize(List<TodoItem> todos) {
        if (todos == null) {
            throw new IllegalArgumentException("todos must not be null");
        }
        if (todos.size() > MAX_TODOS) {
            throw new IllegalArgumentException(
                    "todos cannot contain more than " + MAX_TODOS + " items");
        }

        int inProgress = 0;
        Set<Integer> seqSet = new HashSet<Integer>();
        List<TodoItem> normalized = new ArrayList<TodoItem>(todos.size());
        for (TodoItem item : todos) {
            if (item == null) {
                throw new IllegalArgumentException("todo item must not be null");
            }
            if (item.getSeq() == null || item.getSeq() <= 0) {
                throw new IllegalArgumentException("todo seq must be a positive integer");
            }
            if (!seqSet.add(item.getSeq())) {
                throw new IllegalArgumentException("todo seq must be unique");
            }
            if (item.getContent() == null || item.getContent().trim().isEmpty()) {
                throw new IllegalArgumentException("todo content must not be blank");
            }
            String content = item.getContent().trim();
            if (content.length() > MAX_CONTENT_LENGTH) {
                throw new IllegalArgumentException(
                        "todo content cannot exceed " + MAX_CONTENT_LENGTH + " characters");
            }
            TodoStatus status = TodoStatus.fromCode(item.getStatus());
            if (status == null) {
                throw new IllegalArgumentException("todo status must be one of 1, 2, 3, 4");
            }
            TodoPriority priority = TodoPriority.fromCode(item.getPriority());
            if (priority == null) {
                throw new IllegalArgumentException("todo priority must be one of 1, 2, 3");
            }
            if (status == TodoStatus.IN_PROGRESS && ++inProgress > 1) {
                throw new IllegalArgumentException(
                        "todos can contain at most one in_progress item");
            }
            normalized.add(
                    new TodoItem(item.getSeq(), content, status.getCode(), priority.getCode()));
        }
        Collections.sort(
                normalized,
                new Comparator<TodoItem>() {
                    @Override
                    public int compare(TodoItem left, TodoItem right) {
                        return left.getSeq().compareTo(right.getSeq());
                    }
                });
        return Collections.unmodifiableList(normalized);
    }
}

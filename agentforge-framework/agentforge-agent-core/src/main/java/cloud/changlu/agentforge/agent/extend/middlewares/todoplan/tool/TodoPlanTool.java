package cloud.changlu.agentforge.agent.extend.middlewares.todoplan.tool;

import cloud.changlu.agentforge.agent.extend.middlewares.todoplan.model.TodoItem;
import cloud.changlu.agentforge.agent.extend.middlewares.todoplan.model.TodoStatus;
import cloud.changlu.agentforge.agent.extend.middlewares.todoplan.validation.TodoValidator;
import cloud.changlu.agentforge.model.internal.json.Json;
import cloud.changlu.agentforge.model.tool.P;
import cloud.changlu.agentforge.model.tool.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Todo 计划内置工具。 */
public final class TodoPlanTool {

    public static final String TOOL_NAME = "todoplan";

    public static final String DESCRIPTION =
            "Replace the current session's complete todo list and return status counts.\n"
                    + "Pass a todos array containing at most 30 items. Each item must contain:\n"
                    + "- seq: a positive integer that defines the task order, unique in the list;\n"
                    + "- content: a non-blank task description of at most 300 characters;\n"
                    + "- status: 1=pending, 2=in_progress, 3=completed, 4=cancelled;\n"
                    + "- priority: 1=high, 2=medium, 3=low.\n"
                    + "At most one item may have status in_progress. Every call is a full "
                    + "replacement: include all items that should remain in the list, because "
                    + "omitted items are removed. Pass an empty array to clear the list.";

    /** 追加到系统提示词中的任务计划使用说明。 */
    public static final String SYSTEM_PROMPT =
            "当任务需要多个步骤、需要调用多个工具，或预计无法在一次回复内完成时，先调用 todoplan 制定计划。\n"
                    + "维护计划时遵守：\n"
                    + "- 每项 seq 使用从 1 开始的递增序号，保证展示顺序；\n"
                    + "- 每项 content 保持简短、可执行；\n"
                    + "- 同一时间最多一项 in_progress；\n"
                    + "- 每次推进任务后立即调用 todoplan 全量更新状态；\n"
                    + "- 已完成、已取消或失败的任务也要保留在列表中并更新状态；\n"
                    + "- 简单问答、单步查询不要调用 todoplan。";

    @Tool(name = TOOL_NAME, value = DESCRIPTION)
    public String plan(
            @P(value = "Complete replacement list of todo objects") List<TodoItem> todos) {
        List<TodoItem> normalized = TodoValidator.normalize(todos);
        return "Plan updated: "
                + count(normalized, TodoStatus.COMPLETED.getCode())
                + " completed, "
                + count(normalized, TodoStatus.IN_PROGRESS.getCode())
                + " in progress, "
                + count(normalized, TodoStatus.PENDING.getCode())
                + " pending.";
    }

    /** 将任务计划说明拼接到已有系统提示词后面。 */
    public static String appendSystemPrompt(String basePrompt) {
        if (basePrompt == null || basePrompt.trim().isEmpty()) {
            return SYSTEM_PROMPT;
        }
        return basePrompt + "\n\n" + SYSTEM_PROMPT;
    }

    /** 解析并校验 todoplan 入参，供 Middleware 监控使用。 */
    public static List<TodoItem> parsePlan(String arguments) {
        if (arguments == null || arguments.trim().isEmpty()) {
            throw new IllegalArgumentException("todoplan arguments must not be blank");
        }
        Map<String, Object> root = Json.parseObject(arguments);
        List<Object> rawTodos = Json.array(root.get("todos"));
        if (rawTodos == null) {
            throw new IllegalArgumentException("todos must be an array");
        }

        List<TodoItem> todos = new ArrayList<TodoItem>(rawTodos.size());
        for (Object rawTodo : rawTodos) {
            Map<String, Object> item = Json.object(rawTodo);
            if (item == null) {
                throw new IllegalArgumentException("todo item must be an object");
            }
            todos.add(
                    new TodoItem(
                            seq(item.get("seq")),
                            Json.string(item.get("content")),
                            code(item.get("status")),
                            code(item.get("priority"))));
        }
        return TodoValidator.normalize(todos);
    }

    private static long count(List<TodoItem> todos, int statusCode) {
        long count = 0L;
        for (TodoItem item : todos) {
            if (item.getStatus() != null && item.getStatus() == statusCode) {
                count++;
            }
        }
        return count;
    }

    private static Integer seq(Object raw) {
        return code(raw);
    }

    private static Integer code(Object raw) {
        if (raw instanceof Number) {
            return ((Number) raw).intValue();
        }
        if (raw instanceof String) {
            try {
                return Integer.valueOf((String) raw);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}

package cloud.changlu.agentforge.agent.extend.middlewares.todoplan.tool;

import cloud.changlu.agentforge.agent.extend.middlewares.todoplan.model.TodoItem;
import cloud.changlu.agentforge.agent.extend.middlewares.todoplan.model.TodoPriority;
import cloud.changlu.agentforge.agent.extend.middlewares.todoplan.model.TodoStatus;
import cloud.changlu.agentforge.agent.tool.local.LocalToolFactory;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.tool.ToolExecutor;
import cloud.changlu.agentforge.model.tool.spec.ToolSpecification;

import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class TodoPlanToolTest {

    private static final String VALID_ARGUMENTS =
            "{\"todos\":["
                    + "{\"seq\":2,\"content\":\"second\",\"status\":2,\"priority\":2},"
                    + "{\"seq\":1,\"content\":\"first\",\"status\":3,\"priority\":1},"
                    + "{\"seq\":3,\"content\":\"third\",\"status\":1,\"priority\":3}"
                    + "]}";

    @Test
    public void shouldParseNumericStatusAndPriority() {
        List<TodoItem> todos = TodoPlanTool.parsePlan(VALID_ARGUMENTS);

        assertEquals(3, todos.size());
        assertEquals(Integer.valueOf(1), todos.get(0).getSeq());
        assertEquals(TodoStatus.COMPLETED.getCode(), todos.get(0).getStatus().intValue());
        assertEquals(TodoPriority.HIGH.getCode(), todos.get(0).getPriority().intValue());
        assertEquals(Integer.valueOf(2), todos.get(1).getSeq());
        assertEquals(TodoStatus.IN_PROGRESS.getCode(), todos.get(1).getStatus().intValue());
        assertEquals(Integer.valueOf(3), todos.get(2).getSeq());
        assertEquals(TodoStatus.PENDING.getCode(), todos.get(2).getStatus().intValue());
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectBlankArguments() {
        TodoPlanTool.parsePlan(" ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectMissingTodos() {
        TodoPlanTool.parsePlan("{}");
    }

    @Test
    public void shouldExecuteThroughLocalToolFactory() {
        Map<ToolSpecification, ToolExecutor> tools =
                LocalToolFactory.buildLocalTools(
                        Collections.<Object>singletonList(new TodoPlanTool()));
        ToolSpecification specification = tools.keySet().iterator().next();
        ToolExecutor executor = tools.get(specification);
        ToolExecutionRequest request =
                ToolExecutionRequest.from("call-1", TodoPlanTool.TOOL_NAME, VALID_ARGUMENTS);

        String result = executor.execute(request, "session-1");

        assertEquals("Plan updated: 1 completed, 1 in progress, 1 pending.", result);
    }

    @Test
    public void shouldExposeNestedItemSchema() {
        Map<ToolSpecification, ToolExecutor> tools =
                LocalToolFactory.buildLocalTools(
                        Collections.<Object>singletonList(new TodoPlanTool()));
        ToolSpecification specification = tools.keySet().iterator().next();

        assertEquals(TodoPlanTool.TOOL_NAME, specification.name());
        Map<String, Object> schema = specification.parameters().toMap();
        Map<String, Object> properties = asMap(schema.get("properties"));
        Map<String, Object> todos = asMap(properties.get("todos"));
        Map<String, Object> items = asMap(todos.get("items"));
        Map<String, Object> itemProperties = asMap(items.get("properties"));

        assertEquals("array", todos.get("type"));
        assertEquals("object", items.get("type"));
        assertEquals("integer", asMap(itemProperties.get("seq")).get("type"));
    }

    @Test
    public void shouldAppendTodoPromptToSystemPrompt() {
        String appended = TodoPlanTool.appendSystemPrompt("base prompt");

        assertEquals("base prompt\n\n" + TodoPlanTool.SYSTEM_PROMPT, appended);
    }

    @Test
    public void shouldUseTodoPromptWhenBasePromptIsBlank() {
        assertEquals(TodoPlanTool.SYSTEM_PROMPT, TodoPlanTool.appendSystemPrompt(" "));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}

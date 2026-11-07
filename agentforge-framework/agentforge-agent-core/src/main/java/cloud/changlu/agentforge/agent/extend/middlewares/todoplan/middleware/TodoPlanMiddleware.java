package cloud.changlu.agentforge.agent.extend.middlewares.todoplan.middleware;

import cloud.changlu.agentforge.agent.component.middleware.IAgentMiddleware;
import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.extend.middlewares.todoplan.listener.TodoPlanListener;
import cloud.changlu.agentforge.agent.extend.middlewares.todoplan.model.TodoItem;
import cloud.changlu.agentforge.agent.extend.middlewares.todoplan.tool.TodoPlanTool;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/** 识别 todoplan 工具，并把最新计划交给监控扩展点。 */
public class TodoPlanMiddleware implements IAgentMiddleware {

    private static final Logger log = Logger.getLogger(TodoPlanMiddleware.class.getName());

    private final TodoPlanListener listener;

    public TodoPlanMiddleware(TodoPlanListener listener) {
        this.listener = listener == null ? TodoPlanListener.noop() : listener;
    }

    @Override
    public String afterToolExecution(
            ToolExecutionRequest request, String result, AgentChatContext context) {
        if (isTodoPlan(request)) {
            notifyListener(request, context);
        }
        return result;
    }

    @Override
    public List<String> afterConcurrentToolExecution(
            List<ToolExecutionRequest> requests, List<String> results, AgentChatContext context) {
        if (requests == null || requests.isEmpty()) {
            return results;
        }
        for (int i = requests.size() - 1; i >= 0; i--) {
            if (isTodoPlan(requests.get(i))) {
                notifyListener(requests.get(i), context);
                break;
            }
        }
        return results;
    }

    private boolean isTodoPlan(ToolExecutionRequest request) {
        return request != null && TodoPlanTool.TOOL_NAME.equals(request.name());
    }

    private void notifyListener(ToolExecutionRequest request, AgentChatContext context) {
        try {
            List<TodoItem> todos = TodoPlanTool.parsePlan(request.arguments());
            listener.onTodoPlan(todos, context);
        } catch (Exception e) {
            log.log(
                    Level.WARNING,
                    "处理 Todo 计划失败，忽略旁路异常: sessionId="
                            + (context == null ? null : context.getMemoryId()),
                    e);
        }
    }
}

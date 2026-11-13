package cloud.changlu.agentforge.service.modules.agent.middleware.todoplan;

import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.extend.middlewares.todoplan.listener.TodoPlanListener;
import cloud.changlu.agentforge.agent.extend.middlewares.todoplan.model.TodoItem;
import cloud.changlu.agentforge.agent.extend.middlewares.todoplan.model.TodoPriority;
import cloud.changlu.agentforge.agent.extend.middlewares.todoplan.model.TodoStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** 在 Service 侧打印 Todo 计划变化，作为 TodoPlanListener 的默认实现。 */
public final class ServiceTodoPlanListener implements TodoPlanListener {

    private static final Logger log = LoggerFactory.getLogger(ServiceTodoPlanListener.class);

    @Override
    public void onTodoPlan(List<TodoItem> todos, AgentChatContext context) {
        Object sessionId = context == null ? null : context.getMemoryId();
        int total = todos == null ? 0 : todos.size();
        log.info("Todo plan updated: sessionId={}, total={}", sessionId, total);
        if (todos == null) {
            return;
        }
        for (TodoItem item : todos) {
            TodoStatus status = TodoStatus.fromCode(item.getStatus());
            TodoPriority priority = TodoPriority.fromCode(item.getPriority());
            log.info(
                    "  #{} [{}] {} ({})",
                    item.getSeq(),
                    status == null ? "unknown" : status.getValue(),
                    item.getContent(),
                    priority == null ? "unknown" : priority.getValue());
        }
    }
}

package cloud.changlu.agentforge.agent.extend.middlewares.todoplan.listener;

import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.extend.middlewares.todoplan.model.TodoItem;

import java.util.List;

/** Todo 计划监控扩展点。 */
@FunctionalInterface
public interface TodoPlanListener {

    void onTodoPlan(List<TodoItem> todos, AgentChatContext context);

    static TodoPlanListener noop() {
        return new TodoPlanListener() {
            @Override
            public void onTodoPlan(List<TodoItem> todos, AgentChatContext context) {}
        };
    }
}

package cloud.changlu.agentforge.service.modules.agent.config;

import cloud.changlu.agentforge.agent.extend.middlewares.todoplan.listener.TodoPlanListener;
import cloud.changlu.agentforge.agent.extend.middlewares.todoplan.tool.TodoPlanTool;
import cloud.changlu.agentforge.service.modules.agent.middleware.todoplan.ServiceTodoPlanListener;
import cloud.changlu.agentforge.service.modules.agent.middleware.todoplan.ServiceTodoPlanMiddleware;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Todo 计划能力装配。 */
@Configuration
public class TodoPlanConfiguration {

    @Bean
    public TodoPlanTool todoPlanTool() {
        return new TodoPlanTool();
    }

    @Bean
    @ConditionalOnMissingBean(TodoPlanListener.class)
    public TodoPlanListener todoPlanListener() {
        return new ServiceTodoPlanListener();
    }

    @Bean
    public ServiceTodoPlanMiddleware serviceTodoPlanMiddleware(TodoPlanListener todoPlanListener) {
        return new ServiceTodoPlanMiddleware(todoPlanListener);
    }
}

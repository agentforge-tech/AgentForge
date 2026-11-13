package cloud.changlu.agentforge.service.modules.agent.middleware.todoplan;

import cloud.changlu.agentforge.agent.extend.middlewares.todoplan.listener.TodoPlanListener;
import cloud.changlu.agentforge.agent.extend.middlewares.todoplan.middleware.TodoPlanMiddleware;

/** Service 侧 Todo 中间件，默认使用 ServiceTodoPlanListener 打印计划日志。 */
public class ServiceTodoPlanMiddleware extends TodoPlanMiddleware {

    public ServiceTodoPlanMiddleware() {
        this(new ServiceTodoPlanListener());
    }

    public ServiceTodoPlanMiddleware(TodoPlanListener listener) {
        super(listener);
    }
}

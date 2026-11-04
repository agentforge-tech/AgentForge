package cloud.changlu.agentforge.service.modules.agent.middleware;

import cloud.changlu.agentforge.agent.extend.middlewares.append.event.UserMessageAppendEventListener;
import cloud.changlu.agentforge.agent.extend.middlewares.append.service.IUserMessageAppendService;

/**
 * Service 业务层的用户追加消息 Middleware。
 *
 * <p>当前复用 Core 的队列消费与 Agent Loop 注入逻辑，并作为后续扩展 Service 业务行为的入口。
 */
public class UserMessageAppendMiddleware
        extends cloud.changlu.agentforge.agent.extend.middlewares.append
                .UserMessageAppendMiddleware {

    public UserMessageAppendMiddleware(
            IUserMessageAppendService appendService, UserMessageAppendEventListener eventListener) {
        super(appendService, eventListener);
    }
}

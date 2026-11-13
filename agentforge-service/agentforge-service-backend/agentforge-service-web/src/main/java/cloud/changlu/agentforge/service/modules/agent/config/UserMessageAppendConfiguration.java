package cloud.changlu.agentforge.service.modules.agent.config;

import cloud.changlu.agentforge.agent.extend.middlewares.append.event.UserMessageAppendEventListener;
import cloud.changlu.agentforge.agent.extend.middlewares.append.service.IUserMessageAppendService;
import cloud.changlu.agentforge.agent.extend.middlewares.append.store.UserMessageAppendStore;
import cloud.changlu.agentforge.service.modules.agent.dao.UserMessageAppendMapper;
import cloud.changlu.agentforge.service.modules.agent.middleware.ServiceStreamingMiddleware;
import cloud.changlu.agentforge.service.modules.agent.middleware.SessionLifecycleMiddleware;
import cloud.changlu.agentforge.service.modules.agent.middleware.append.ServiceUserMessageAppendEventListener;
import cloud.changlu.agentforge.service.modules.agent.middleware.append.UserMessageAppendMiddleware;
import cloud.changlu.agentforge.service.modules.agent.store.MysqlUserMessageAppendStore;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 用户追加消息能力的组件装配配置。 */
@Configuration
public class UserMessageAppendConfiguration {

    /** 默认使用 MySQL 持久化待生效的追加消息。 */
    @Bean
    public UserMessageAppendStore userMessageAppendStore(UserMessageAppendMapper mapper) {
        return new MysqlUserMessageAppendStore(mapper);
    }

    /** 将 Core 追加消息事件适配为 Service SSE 与会话历史事件。 */
    @Bean
    public UserMessageAppendEventListener userMessageAppendEventListener(
            ServiceStreamingMiddleware streamingMiddleware,
            SessionLifecycleMiddleware lifecycleMiddleware) {
        return new ServiceUserMessageAppendEventListener(streamingMiddleware, lifecycleMiddleware);
    }

    /** 注册负责在 Agent Loop 调用边界注入追加消息的 Middleware。 */
    @Bean
    public UserMessageAppendMiddleware userMessageAppendMiddleware(
            IUserMessageAppendService appendService, UserMessageAppendEventListener eventListener) {
        return new UserMessageAppendMiddleware(appendService, eventListener);
    }
}

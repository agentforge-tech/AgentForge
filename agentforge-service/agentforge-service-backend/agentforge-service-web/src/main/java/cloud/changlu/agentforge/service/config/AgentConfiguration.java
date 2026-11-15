package cloud.changlu.agentforge.service.config;

import cloud.changlu.agentforge.service.env.EnvironmentContext;
import cloud.changlu.agentforge.service.modules.agent.interrupt.AgentInterruptRuntimeRegistry;
import cloud.changlu.agentforge.service.modules.agent.middleware.ServiceStreamingMiddleware;
import cloud.changlu.agentforge.service.modules.agent.middleware.SessionLifecycleMiddleware;
import cloud.changlu.agentforge.service.modules.session.dao.AgentSessionMapper;
import cloud.changlu.agentforge.service.modules.session.dao.AgentSessionMessageMapper;
import cloud.changlu.agentforge.service.plugins.openreach.OpenReachClient;
import cloud.changlu.agentforge.service.plugins.openreach.OpenReachTools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfiguration {

    @Bean
    public AgentInterruptRuntimeRegistry agentInterruptRuntimeRegistry() {
        return new AgentInterruptRuntimeRegistry();
    }

    @Bean
    public ServiceStreamingMiddleware serviceStreamingMiddleware(
            AgentInterruptRuntimeRegistry interruptRegistry,
            SessionLifecycleMiddleware lifecycleMiddleware) {
        return new ServiceStreamingMiddleware(interruptRegistry, lifecycleMiddleware);
    }

    @Bean
    public SessionLifecycleMiddleware sessionLifecycleMiddleware(
            AgentSessionMapper agentSessionMapper,
            AgentSessionMessageMapper agentSessionMessageMapper,
            ObjectMapper objectMapper) {
        return new SessionLifecycleMiddleware(
                agentSessionMapper, agentSessionMessageMapper, objectMapper);
    }

    @Bean
    public OpenReachTools openReachTools(EnvironmentContext environmentContext) {
        return new OpenReachTools(new OpenReachClient(environmentContext.getOpenReachBaseUrl()));
    }
}

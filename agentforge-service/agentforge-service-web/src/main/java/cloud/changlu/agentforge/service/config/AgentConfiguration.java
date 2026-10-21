package cloud.changlu.agentforge.service.config;

import cloud.changlu.agentforge.service.plugins.openreach.OpenReachClient;
import cloud.changlu.agentforge.service.plugins.openreach.OpenReachTools;
import cloud.changlu.agentforge.service.stream.ServiceStreamingMiddleware;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfiguration {

    @Bean
    public ServiceStreamingMiddleware serviceStreamingMiddleware() {
        return new ServiceStreamingMiddleware();
    }

    @Bean
    public OpenReachTools openReachTools(
            @Value("${agentforge.openreach.base-url:}") String openReachBaseUrl) {
        return new OpenReachTools(new OpenReachClient(openReachBaseUrl));
    }
}

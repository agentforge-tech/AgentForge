package cloud.changlu.agentforge.service.modules.agent.config;

import cloud.changlu.agentforge.agent.governance.interrupt.spi.AgentInterruptStore;
import cloud.changlu.agentforge.service.modules.agent.interrupt.store.mysql.AgentInterruptSnapshotCodec;
import cloud.changlu.agentforge.service.modules.agent.interrupt.store.mysql.AgentInterruptSnapshotMapper;
import cloud.changlu.agentforge.service.modules.agent.interrupt.store.mysql.MysqlAgentInterruptStore;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InterruptStoreConfiguration {

    @Bean
    public AgentInterruptStore agentInterruptStore(
            AgentInterruptSnapshotMapper mapper, ObjectMapper objectMapper) {
        return new MysqlAgentInterruptStore(mapper, new AgentInterruptSnapshotCodec(objectMapper));
    }
}

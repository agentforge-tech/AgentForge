package cloud.changlu.agentforge.service.config;

import cloud.changlu.agentforge.agent.memory.store.ChatMemoryStore;
import cloud.changlu.agentforge.service.modules.agent.memory.dao.AgentMemoryMapper;
import cloud.changlu.agentforge.service.modules.agent.memory.store.MysqlChatMemoryStore;
import cloud.changlu.agentforge.service.modules.agent.runtime.mode.react.memory.factory.ChatMemoryProviderFactory;
import cloud.changlu.agentforge.service.modules.agent.runtime.mode.react.memory.factory.extension.ChatMemoryProviderFactoryExtension;
import cloud.changlu.agentforge.service.modules.agent.runtime.mode.react.memory.factory.extension.window.WindowChatMemoryProviderFactoryExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 会话记忆存储装配。
 *
 * <p>把 {@link MysqlChatMemoryStore} 的实例化收敛到配置类中，存储实现本身保持纯 POJO， 便于单元测试直接 new，也方便后续按需切换为其他 {@link
 * ChatMemoryStore} 实现（如 Redis、内存版）。
 *
 * @author changlu
 * @since 2026-10-25
 */
@Configuration
public class ChatMemoryConfiguration {

    @Bean
    public ChatMemoryStore chatMemoryStore(AgentMemoryMapper agentMemoryMapper) {
        return new MysqlChatMemoryStore(agentMemoryMapper);
    }

    @Bean
    public ChatMemoryProviderFactoryExtension windowChatMemoryProviderFactoryExtension(
            ObjectMapper objectMapper, ChatMemoryStore chatMemoryStore) {
        return new WindowChatMemoryProviderFactoryExtension(objectMapper, chatMemoryStore);
    }

    @Bean
    public ChatMemoryProviderFactory chatMemoryProviderFactory(
            List<ChatMemoryProviderFactoryExtension> extensions) {
        return new ChatMemoryProviderFactory(extensions);
    }
}

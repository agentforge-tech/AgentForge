package cloud.changlu.agentforge.service.modules.agent.runtime.mode.react.memory.factory.extension.window;

import cloud.changlu.agentforge.agent.memory.ChatMemoryProvider;
import cloud.changlu.agentforge.agent.memory.store.ChatMemoryStore;
import cloud.changlu.agentforge.agent.memory.window.MessageWindowChatMemoryProvider;
import cloud.changlu.agentforge.service.modules.agent.runtime.mode.react.memory.enums.ChatMemoryType;
import cloud.changlu.agentforge.service.modules.agent.runtime.mode.react.memory.factory.extension.ChatMemoryProviderFactoryExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 窗口会话记忆提供者工厂。 */
public class WindowChatMemoryProviderFactoryExtension
        implements ChatMemoryProviderFactoryExtension {

    private static final int DEFAULT_MAX_MESSAGES = 1000;

    private final ObjectMapper objectMapper;
    private final ChatMemoryStore chatMemoryStore;

    public WindowChatMemoryProviderFactoryExtension(
            ObjectMapper objectMapper, ChatMemoryStore chatMemoryStore) {
        this.objectMapper = objectMapper;
        this.chatMemoryStore = chatMemoryStore;
    }

    @Override
    public int getType() {
        return ChatMemoryType.WINDOW.getValue();
    }

    @Override
    public ChatMemoryProvider getChatMemoryProvider(String memoryConfig) {
        return new MessageWindowChatMemoryProvider(
                resolveMaxMessages(memoryConfig), chatMemoryStore);
    }

    private int resolveMaxMessages(String memoryConfig) {
        if (memoryConfig == null || memoryConfig.trim().isEmpty()) {
            return DEFAULT_MAX_MESSAGES;
        }
        try {
            WindowChatMemoryConfig config =
                    objectMapper.readValue(memoryConfig, WindowChatMemoryConfig.class);
            return config.getMaxMessage() > 0 ? config.getMaxMessage() : DEFAULT_MAX_MESSAGES;
        } catch (Exception ignored) {
            return DEFAULT_MAX_MESSAGES;
        }
    }

    private static class WindowChatMemoryConfig {

        private int maxMessage = DEFAULT_MAX_MESSAGES;

        public int getMaxMessage() {
            return maxMessage;
        }

        public void setMaxMessage(int maxMessage) {
            this.maxMessage = maxMessage;
        }
    }
}

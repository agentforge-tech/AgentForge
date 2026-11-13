package cloud.changlu.agentforge.service.modules.agent.runtime.mode.react.memory.factory;

import cloud.changlu.agentforge.agent.memory.ChatMemoryProvider;
import cloud.changlu.agentforge.service.modules.agent.domain.config.ChatMemoryConfig;
import cloud.changlu.agentforge.service.modules.agent.runtime.mode.react.memory.enums.ChatMemoryType;
import cloud.changlu.agentforge.service.modules.agent.runtime.mode.react.memory.factory.extension.ChatMemoryProviderFactoryExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 根据记忆配置创建对话记忆提供者。 */
public class ChatMemoryProviderFactory {

    private final Map<Integer, ChatMemoryProviderFactoryExtension> extensions;

    public ChatMemoryProviderFactory(List<ChatMemoryProviderFactoryExtension> extensions) {
        this.extensions = new HashMap<Integer, ChatMemoryProviderFactoryExtension>();
        for (ChatMemoryProviderFactoryExtension extension : extensions) {
            this.extensions.put(extension.getType(), extension);
        }
    }

    public ChatMemoryProvider getChatMemoryProvider(ChatMemoryConfig config) {
        int memoryType =
                config == null || config.getType() == null
                        ? ChatMemoryType.WINDOW.getValue()
                        : config.getType();
        ChatMemoryProviderFactoryExtension extension = extensions.get(memoryType);
        if (extension == null) {
            throw new IllegalArgumentException("不支持的 ChatMemoryConfig.type: " + memoryType);
        }
        String memoryConfig = config == null ? "" : config.getMemoryConfig();
        return extension.getChatMemoryProvider(memoryConfig);
    }
}

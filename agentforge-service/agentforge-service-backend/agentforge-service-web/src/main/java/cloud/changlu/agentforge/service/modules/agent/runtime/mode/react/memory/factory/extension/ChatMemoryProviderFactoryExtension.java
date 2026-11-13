package cloud.changlu.agentforge.service.modules.agent.runtime.mode.react.memory.factory.extension;

import cloud.changlu.agentforge.agent.memory.ChatMemoryProvider;

/** 单一类型的对话记忆提供者工厂扩展。 */
public interface ChatMemoryProviderFactoryExtension {

    int getType();

    ChatMemoryProvider getChatMemoryProvider(String memoryConfig);
}

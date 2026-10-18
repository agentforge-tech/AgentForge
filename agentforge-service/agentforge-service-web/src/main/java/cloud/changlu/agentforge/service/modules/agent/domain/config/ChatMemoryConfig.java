package cloud.changlu.agentforge.service.modules.agent.domain.config;

/** Agent 对话记忆配置。 */
public class ChatMemoryConfig {

    private Integer chatMemoryMaxMessages = 100;

    public Integer getChatMemoryMaxMessages() {
        return chatMemoryMaxMessages;
    }

    public void setChatMemoryMaxMessages(Integer chatMemoryMaxMessages) {
        this.chatMemoryMaxMessages = chatMemoryMaxMessages;
    }
}

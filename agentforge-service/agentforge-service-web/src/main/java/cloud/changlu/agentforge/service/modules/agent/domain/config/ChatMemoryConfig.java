package cloud.changlu.agentforge.service.modules.agent.domain.config;

/** Agent 对话记忆配置。 */
public class ChatMemoryConfig {

    private Integer type = 1;
    private String memoryConfig;

    public Integer getType() {
        return type;
    }

    public void setType(Integer type) {
        this.type = type;
    }

    public String getMemoryConfig() {
        return memoryConfig;
    }

    public void setMemoryConfig(String memoryConfig) {
        this.memoryConfig = memoryConfig;
    }
}

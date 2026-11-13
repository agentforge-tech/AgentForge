package cloud.changlu.agentforge.service.modules.agent.domain.config;

/** 调试态或正式态统一运行时配置。 */
public class AgentRuntimeConfig {

    private final String aid;
    private final String name;
    private final Integer type;
    private final String agentConfig;

    public AgentRuntimeConfig(String aid, String name, Integer type, String agentConfig) {
        this.aid = aid;
        this.name = name;
        this.type = type;
        this.agentConfig = agentConfig;
    }

    public String getAid() {
        return aid;
    }

    public String getName() {
        return name;
    }

    public Integer getType() {
        return type;
    }

    public String getAgentConfig() {
        return agentConfig;
    }
}

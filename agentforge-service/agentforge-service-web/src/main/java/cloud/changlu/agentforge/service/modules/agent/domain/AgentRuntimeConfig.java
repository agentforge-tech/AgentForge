package cloud.changlu.agentforge.service.modules.agent.domain;

/** 调试态或正式态统一运行时配置。 */
public class AgentRuntimeConfig {

    private final String aid;
    private final Integer type;
    private final String agentConfig;

    public AgentRuntimeConfig(String aid, Integer type, String agentConfig) {
        this.aid = aid;
        this.type = type;
        this.agentConfig = agentConfig;
    }

    public String getAid() {
        return aid;
    }

    public Integer getType() {
        return type;
    }

    public String getAgentConfig() {
        return agentConfig;
    }
}

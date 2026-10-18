package cloud.changlu.agentforge.service.modules.agent.runtime.context;

/** Agent 初始化上下文。 */
public class AgentInitContext {
    public static final String WEBSITE_CHANNEL_ID = "-1";
    private String aid;
    private String agentConfig;
    private boolean debug;
    private String channelId;

    public static Builder builder() {
        return new Builder();
    }

    public String getAid() {
        return aid;
    }

    public String getAgentConfig() {
        return agentConfig;
    }

    public boolean isDebug() {
        return debug;
    }

    public String getNormalizedChannelId() {
        return channelId == null || channelId.trim().isEmpty() ? WEBSITE_CHANNEL_ID : channelId;
    }

    public static class Builder {
        private final AgentInitContext context = new AgentInitContext();

        public Builder aid(String aid) {
            context.aid = aid;
            return this;
        }

        public Builder agentConfig(String agentConfig) {
            context.agentConfig = agentConfig;
            return this;
        }

        public Builder debug(boolean debug) {
            context.debug = debug;
            return this;
        }

        public Builder channelId(String channelId) {
            context.channelId = channelId;
            return this;
        }

        public AgentInitContext build() {
            return context;
        }
    }
}

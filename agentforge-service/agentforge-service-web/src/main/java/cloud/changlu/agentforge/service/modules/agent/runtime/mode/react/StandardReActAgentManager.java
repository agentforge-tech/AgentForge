package cloud.changlu.agentforge.service.modules.agent.runtime.mode.react;

import cloud.changlu.agentforge.agent.ReActAgent;
import cloud.changlu.agentforge.agent.domain.AgentRequest;
import cloud.changlu.agentforge.agent.step.ChatResult;
import cloud.changlu.agentforge.agent.stream.TokenStream;
import cloud.changlu.agentforge.service.modules.agent.domain.config.StandardAgentConfig;
import cloud.changlu.agentforge.service.modules.agent.domain.req.AgentChatReq;
import cloud.changlu.agentforge.service.modules.agent.runtime.base.AbstractAgentManager;
import cloud.changlu.agentforge.service.modules.agent.runtime.context.AgentInitContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** ReActAgent 运行时管理器，按 aid 维护已初始化的 Agent。 */
@Component
public class StandardReActAgentManager
        extends AbstractAgentManager<ReActAgent, StandardAgentConfig> {

    private static final int STANDARD_AGENT_TYPE = 1;
    private final ReActAgentFactory reActAgentFactory;
    private final ObjectMapper objectMapper;

    public StandardReActAgentManager(
            ReActAgentFactory reActAgentFactory, ObjectMapper objectMapper) {
        this.reActAgentFactory = reActAgentFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    public Integer getType() {
        return STANDARD_AGENT_TYPE;
    }

    protected StandardAgentConfig parseAgentConf(String agentConfig) {
        return StandardAgentConfig.fromJson(agentConfig, objectMapper);
    }

    @Override
    protected ReActAgent createAgent(AgentInitContext context) {
        StandardAgentConfig agentConfig = parseAgentConf(context.getAgentConfig());
        return reActAgentFactory.createReActAgent(agentConfig);
    }

    @Override
    public ChatResult ask(ReActAgent agent, AgentChatReq req) {
        return agent.run(toAgentRequest(req));
    }

    @Override
    public TokenStream stream(ReActAgent agent, AgentChatReq req) {
        return agent.runStream(toAgentRequest(req));
    }

    @Override
    public void cancel(ReActAgent agent, String sessionId) {
        agent.cancel(sessionId);
    }

    private AgentRequest toAgentRequest(AgentChatReq req) {
        String sessionId = req.getSessionId();
        boolean isDebug = Boolean.TRUE.equals(req.getIsDebug());
        if (sessionId == null || sessionId.trim().isEmpty()) {
            sessionId = (isDebug ? "debug-" : "chat-") + req.getAid();
        }
        return AgentRequest.builder().memoryId(sessionId).question(req.getMessage().trim()).build();
    }
}

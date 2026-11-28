package cloud.changlu.agentforge.service.modules.agent.runtime.mode.react;

import cloud.changlu.agentforge.agent.ReActAgent;
import cloud.changlu.agentforge.agent.domain.AgentRequest;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptBatchResumeCommand;
import cloud.changlu.agentforge.agent.step.ChatResult;
import cloud.changlu.agentforge.agent.stream.TokenStream;
import cloud.changlu.agentforge.service.modules.agent.domain.config.StandardAgentConfig;
import cloud.changlu.agentforge.service.modules.agent.domain.req.AgentChatReq;
import cloud.changlu.agentforge.service.modules.agent.runtime.base.AbstractAgentManager;
import cloud.changlu.agentforge.service.modules.agent.runtime.context.AgentInitContext;
import cloud.changlu.agentforge.service.modules.agent.runtime.context.ServiceAgentContextAccessor;
import cloud.changlu.agentforge.service.modules.agent.runtime.context.ServiceAgentExecutionContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** ReActAgent 运行时管理器，按 aid 维护已初始化的 Agent。 */
@Component
public class StandardReActAgentManager
        extends AbstractAgentManager<ReActAgent, StandardAgentConfig> {

    private static final int STANDARD_AGENT_TYPE = 1;
    private final ReActAgentFactory reActAgentFactory;
    private final ObjectMapper objectMapper;
    private final ServiceAgentContextAccessor contextAccessor;

    public StandardReActAgentManager(
            ReActAgentFactory reActAgentFactory,
            ObjectMapper objectMapper,
            ServiceAgentContextAccessor contextAccessor) {
        this.reActAgentFactory = reActAgentFactory;
        this.objectMapper = objectMapper;
        this.contextAccessor = contextAccessor;
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
        String agentName = context.getAgentName();
        if (agentName == null || agentName.trim().isEmpty()) {
            agentName = context.getAid();
        }
        return reActAgentFactory.createReActAgent(agentName, agentConfig, context.isDebug());
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
    public TokenStream resumeInterruptStream(
            ReActAgent agent, InterruptBatchResumeCommand command) {
        return agent.resumeInterruptStream(command);
    }

    @Override
    public String createExecutionId(ReActAgent agent) {
        return agent.createExecutionId();
    }

    @Override
    public void cancel(ReActAgent agent, String sessionId, String executionId) {
        agent.cancel(sessionId, executionId);
    }

    private AgentRequest toAgentRequest(AgentChatReq req) {
        String sessionId = req.getSessionId();
        boolean isDebug = Boolean.TRUE.equals(req.getIsDebug());
        if (sessionId == null || sessionId.trim().isEmpty()) {
            sessionId = (isDebug ? "debug-" : "chat-") + req.getAid();
        }
        java.util.Map<String, Object> extensions = new java.util.HashMap<>();
        contextAccessor.write(
                extensions,
                new ServiceAgentExecutionContext(
                        req.getUserId(),
                        req.getAiMessageId(),
                        sessionId,
                        req.getAid(),
                        Boolean.valueOf(isDebug)));
        String question = req.getMessage() == null ? "" : req.getMessage().trim();
        boolean hasAttachments = req.getAttachments() != null && !req.getAttachments().isEmpty();
        if (question.isEmpty() && hasAttachments) {
            // 框架层 question 不允许为空；只发附件时注入占位指令。
            question = "我上传了附件，请结合附件内容回答。";
        }
        return AgentRequest.builder()
                .memoryId(sessionId)
                .executionId(req.getExecutionId())
                .question(question)
                .attachments(req.getAttachments())
                .extensions(extensions)
                .build();
    }
}

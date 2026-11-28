package cloud.changlu.agentforge.service.modules.agent.runtime.context;

/** AgentForge Service 一次 Agent 执行的受信任业务上下文。 */
public final class ServiceAgentExecutionContext {
    private final Long userId;
    private final Long aiMessageId;
    private final String sessionId;
    private final String aid;
    private final Boolean debug;

    public ServiceAgentExecutionContext(
            Long userId, Long aiMessageId, String sessionId, String aid, Boolean debug) {
        this.userId = userId;
        this.aiMessageId = aiMessageId;
        this.sessionId = sessionId;
        this.aid = aid;
        this.debug = debug;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getAiMessageId() {
        return aiMessageId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getAid() {
        return aid;
    }

    public Boolean getDebug() {
        return debug;
    }
}

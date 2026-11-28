package cloud.changlu.agentforge.service.modules.agent.recovery;

/**
 * 一轮恢复所需的全部信息。
 *
 * <p>由 {@link AgentRecoveryService} 从候选记录构造，交给 {@link AgentRecoveryExecutor} 执行。
 */
public class AgentResumeTask {

    /** 会话标识，框架里统一叫 memoryId（= {@code ai_agent_session.session_id}）。 */
    private String memoryId;

    private String aid;

    /** 该会话最后一条 AI 消息主键，恢复期间复用，不新增消息。 */
    private Long aiMessageId;

    private Long userId;

    private Boolean debug;

    /** 消息上记录的来源执行，作为 resumeStream 的入参。 */
    private String sourceExecutionId;

    /** 已初始化（或复用）的 Agent 实例。 */
    private Object agent;

    public static Builder builder() {
        return new Builder();
    }

    public String getMemoryId() {
        return memoryId;
    }

    public String getAid() {
        return aid;
    }

    public Long getAiMessageId() {
        return aiMessageId;
    }

    public Long getUserId() {
        return userId;
    }

    public Boolean getDebug() {
        return debug;
    }

    public String getSourceExecutionId() {
        return sourceExecutionId;
    }

    public Object getAgent() {
        return agent;
    }

    /** 链式构造器，保持与项目内其他上下文对象一致的写法。 */
    public static class Builder {
        private final AgentResumeTask task = new AgentResumeTask();

        public Builder memoryId(String memoryId) {
            task.memoryId = memoryId;
            return this;
        }

        public Builder aid(String aid) {
            task.aid = aid;
            return this;
        }

        public Builder aiMessageId(Long aiMessageId) {
            task.aiMessageId = aiMessageId;
            return this;
        }

        public Builder userId(Long userId) {
            task.userId = userId;
            return this;
        }

        public Builder debug(Boolean debug) {
            task.debug = debug;
            return this;
        }

        public Builder sourceExecutionId(String sourceExecutionId) {
            task.sourceExecutionId = sourceExecutionId;
            return this;
        }

        public Builder agent(Object agent) {
            task.agent = agent;
            return this;
        }

        public AgentResumeTask build() {
            return task;
        }
    }
}

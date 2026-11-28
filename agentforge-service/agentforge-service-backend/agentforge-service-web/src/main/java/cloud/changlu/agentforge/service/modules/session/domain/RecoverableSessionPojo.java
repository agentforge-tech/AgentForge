package cloud.changlu.agentforge.service.modules.session.domain;

/**
 * 启动恢复候选：一条"会话运行中 + 尾部 AI 消息非终态 + 有来源执行"的记录。
 *
 * <p>对应 {@code AgentSessionMapper#selectRecoverableSessions} 的查询结果。
 */
public class RecoverableSessionPojo {

    /** {@code ai_agent_session.session_id}，同时作为 Agent 的 memoryId。 */
    private String sessionId;

    private String aid;

    /** 是否调试会话：0 正式、1 调试。 */
    private Integer isDebug;

    /** 会话创建者，恢复期间写入上下文。 */
    private Long userId;

    /** 该会话最后一条 AI 消息主键。 */
    private Long aiMessageId;

    /** 该 AI 消息状态：1 创建、2 处理中。 */
    private Integer messageStatus;

    /** 该 AI 消息记录的执行标识，恢复时的来源执行。 */
    private String executionId;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getAid() {
        return aid;
    }

    public void setAid(String aid) {
        this.aid = aid;
    }

    public Integer getIsDebug() {
        return isDebug;
    }

    public void setIsDebug(Integer isDebug) {
        this.isDebug = isDebug;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getAiMessageId() {
        return aiMessageId;
    }

    public void setAiMessageId(Long aiMessageId) {
        this.aiMessageId = aiMessageId;
    }

    public Integer getMessageStatus() {
        return messageStatus;
    }

    public void setMessageStatus(Integer messageStatus) {
        this.messageStatus = messageStatus;
    }

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }
}

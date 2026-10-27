package cloud.changlu.agentforge.service.modules.agent.domain.req;

/** Agent 正式对话与调试对话的统一请求。 */
public class AgentChatReq {

    private String aid;
    private String sessionId;
    private String message;
    private Boolean isDebug;

    /** 本次请求初始化得到的 AI 消息主键，用于会话生命周期中间件收口。 */
    private Long aiMessageId;

    /** 会话表主键。 */
    private Long sessionPk;

    /** 会话标题，仅首次创建时有值。 */
    private String sessionTitle;

    public String getAid() {
        return aid;
    }

    public void setAid(String aid) {
        this.aid = aid;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Boolean getIsDebug() {
        return isDebug;
    }

    public void setIsDebug(Boolean isDebug) {
        this.isDebug = isDebug;
    }

    public Long getAiMessageId() {
        return aiMessageId;
    }

    public void setAiMessageId(Long aiMessageId) {
        this.aiMessageId = aiMessageId;
    }

    public Long getSessionPk() {
        return sessionPk;
    }

    public void setSessionPk(Long sessionPk) {
        this.sessionPk = sessionPk;
    }

    public String getSessionTitle() {
        return sessionTitle;
    }

    public void setSessionTitle(String sessionTitle) {
        this.sessionTitle = sessionTitle;
    }
}

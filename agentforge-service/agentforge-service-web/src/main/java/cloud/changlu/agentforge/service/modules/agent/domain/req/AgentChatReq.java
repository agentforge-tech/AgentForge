package cloud.changlu.agentforge.service.modules.agent.domain.req;

/** Agent 正式对话与调试对话的统一请求。 */
public class AgentChatReq {

    private String aid;
    private String sessionId;
    private String message;
    private Boolean isDebug;
    private Integer mode;

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

    public Integer getMode() {
        return mode;
    }

    public void setMode(Integer mode) {
        this.mode = mode;
    }
}

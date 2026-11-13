package cloud.changlu.agentforge.service.domain.req;

public class ChatStreamRequest {
    private String aid;
    private String sessionId;
    private String message;
    private Boolean isDebug;

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
}

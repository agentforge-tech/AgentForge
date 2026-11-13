package cloud.changlu.agentforge.service.modules.agent.domain.vo;

public class ChatTaskSubmitVo {
    private String sessionId;
    private Long sessionPk;
    private Long userMessageId;
    private Long messageId;
    private Integer status;
    private String title;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Long getSessionPk() {
        return sessionPk;
    }

    public void setSessionPk(Long sessionPk) {
        this.sessionPk = sessionPk;
    }

    public Long getUserMessageId() {
        return userMessageId;
    }

    public void setUserMessageId(Long userMessageId) {
        this.userMessageId = userMessageId;
    }

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}

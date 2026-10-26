package cloud.changlu.agentforge.service.modules.session.domain.req;

/** 添加或取消置顶请求。 */
public class SessionSetTopReq {

    private Long sessionId;
    private Boolean isTop;

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public Boolean getIsTop() {
        return isTop;
    }

    public void setIsTop(Boolean isTop) {
        this.isTop = isTop;
    }
}

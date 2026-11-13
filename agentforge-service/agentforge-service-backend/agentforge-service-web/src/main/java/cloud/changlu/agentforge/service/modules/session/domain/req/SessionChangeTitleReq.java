package cloud.changlu.agentforge.service.modules.session.domain.req;

/** 修改会话标题请求。 */
public class SessionChangeTitleReq {

    private Long sessionId;
    private String title;

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}

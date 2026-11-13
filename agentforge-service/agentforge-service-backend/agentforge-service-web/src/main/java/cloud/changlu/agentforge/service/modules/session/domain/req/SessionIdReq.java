package cloud.changlu.agentforge.service.modules.session.domain.req;

/** 会话 ID 请求，用于删除与消息明细查询。 */
public class SessionIdReq {

    private Long sessionId;

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }
}

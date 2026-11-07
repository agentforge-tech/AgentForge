package cloud.changlu.agentforge.service.modules.session.domain.req;

/** 置顶会话查询请求。 */
public class SessionTopListReq {

    private String aid;

    /** 是否调试态会话：true 只查调试会话，false 只查发布态会话，null 不区分。 */
    private Boolean debug;

    public String getAid() {
        return aid;
    }

    public void setAid(String aid) {
        this.aid = aid;
    }

    public Boolean getDebug() {
        return debug;
    }

    public void setDebug(Boolean debug) {
        this.debug = debug;
    }
}

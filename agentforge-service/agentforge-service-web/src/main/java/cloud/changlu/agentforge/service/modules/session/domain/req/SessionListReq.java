package cloud.changlu.agentforge.service.modules.session.domain.req;

/** 普通历史会话查询请求。 */
public class SessionListReq {

    private String aid;
    private Integer limit;

    public String getAid() {
        return aid;
    }

    public void setAid(String aid) {
        this.aid = aid;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }
}

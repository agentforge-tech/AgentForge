package cloud.changlu.agentforge.service.modules.agent.domain.req;

/** Agent 配置分页查询请求。 */
public class AgentConfigQueryReq {

    private String keyword;
    private Integer page = 1;
    private Integer pageSize = 12;

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public Integer getPage() {
        return page;
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    public int getStart() {
        int safePage = page == null || page < 1 ? 1 : page;
        int safePageSize = pageSize == null || pageSize < 1 ? 12 : Math.min(pageSize, 100);
        return (safePage - 1) * safePageSize;
    }
}

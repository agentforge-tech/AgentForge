package cloud.changlu.agentforge.service.modules.plugins.mcp.domain.req;

/**
 * MCP Server 分页查询请求，所有字段均可为空，为空时该条件不参与过滤。
 *
 * @author changlu
 * @since 2026-10-27
 */
public class McpServerPageQueryReq {

    private Long pluginId;
    private String serverName;
    private Integer transportType;
    private Integer status;
    private Integer page = 1;
    private Integer pageSize = 10;

    public Long getPluginId() {
        return pluginId;
    }

    public void setPluginId(Long pluginId) {
        this.pluginId = pluginId;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public Integer getTransportType() {
        return transportType;
    }

    public void setTransportType(Integer transportType) {
        this.transportType = transportType;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
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

    /** 计算 SQL 偏移量。 */
    public int getStart() {
        int safePage = page == null || page < 1 ? 1 : page;
        int safePageSize = pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, 100);
        return (safePage - 1) * safePageSize;
    }
}

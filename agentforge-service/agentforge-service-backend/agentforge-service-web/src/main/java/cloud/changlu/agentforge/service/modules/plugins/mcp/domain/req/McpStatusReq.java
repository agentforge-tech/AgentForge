package cloud.changlu.agentforge.service.modules.plugins.mcp.domain.req;

/**
 * MCP 插件/Server 启停请求。
 *
 * @author changlu
 * @since 2026-10-27
 */
public class McpStatusReq {

    private Integer status;

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}

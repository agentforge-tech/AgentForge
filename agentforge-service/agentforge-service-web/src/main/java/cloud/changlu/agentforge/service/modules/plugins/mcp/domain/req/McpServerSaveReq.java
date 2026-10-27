package cloud.changlu.agentforge.service.modules.plugins.mcp.domain.req;

import java.util.Map;

/**
 * MCP Server 新增/修改请求，{@code id} 为空时新增，否则更新。
 *
 * @author changlu
 * @since 2026-10-27
 */
public class McpServerSaveReq {

    private Long id;
    private Long pluginId;
    private String serverName;
    private String serverUrl;
    private Integer transportType;
    private Map<String, String> headers;
    private Integer status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public Integer getTransportType() {
        return transportType;
    }

    public void setTransportType(Integer transportType) {
        this.transportType = transportType;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}

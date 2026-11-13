package cloud.changlu.agentforge.service.modules.plugins.mcp.domain.req;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 从标准 {@code mcpServers} JSON 一键导入 HTTP MCP 服务的请求。
 *
 * @author changlu
 * @since 2026-10-27
 */
public class McpImportReq {

    private String name;
    private String description;
    private String icon;
    private JsonNode mcpServers;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public JsonNode getMcpServers() {
        return mcpServers;
    }

    public void setMcpServers(JsonNode mcpServers) {
        this.mcpServers = mcpServers;
    }
}

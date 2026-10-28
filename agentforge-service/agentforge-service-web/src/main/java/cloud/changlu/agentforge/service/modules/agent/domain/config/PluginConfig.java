package cloud.changlu.agentforge.service.modules.agent.domain.config;

import java.util.List;

/** Agent 技能配置，统一管理 HTTP 插件与 MCP 插件。 */
public class PluginConfig {

    private List<HttpToolConfig> httpPlugins;
    private List<McpToolConfig> mcpPlugins;

    public List<HttpToolConfig> getHttpPlugins() {
        return httpPlugins;
    }

    public void setHttpPlugins(List<HttpToolConfig> httpPlugins) {
        this.httpPlugins = httpPlugins;
    }

    public List<McpToolConfig> getMcpPlugins() {
        return mcpPlugins;
    }

    public void setMcpPlugins(List<McpToolConfig> mcpPlugins) {
        this.mcpPlugins = mcpPlugins;
    }
}

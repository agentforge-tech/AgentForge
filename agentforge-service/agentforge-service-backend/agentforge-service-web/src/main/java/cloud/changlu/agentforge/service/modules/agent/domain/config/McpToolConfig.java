package cloud.changlu.agentforge.service.modules.agent.domain.config;

import java.util.Arrays;

/**
 * Agent 中绑定的 MCP 插件及其勾选的 Server。
 *
 * <p>只保存选择关系，工具清单在 Agent 构建时通过 {@code tools/list} 动态发现。
 *
 * @author changlu
 * @since 2026-10-27
 */
public class McpToolConfig {

    private Long pluginId;
    private Long[] serverIds;

    public Long getPluginId() {
        return pluginId;
    }

    public void setPluginId(Long pluginId) {
        this.pluginId = pluginId;
    }

    public Long[] getServerIds() {
        return serverIds;
    }

    public void setServerIds(Long[] serverIds) {
        this.serverIds = serverIds;
    }

    @Override
    public String toString() {
        return "McpToolConfig{pluginId="
                + pluginId
                + ", serverIds="
                + Arrays.toString(serverIds)
                + '}';
    }
}

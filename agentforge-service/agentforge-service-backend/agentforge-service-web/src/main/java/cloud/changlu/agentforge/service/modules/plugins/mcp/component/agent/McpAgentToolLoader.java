package cloud.changlu.agentforge.service.modules.plugins.mcp.component.agent;

import cloud.changlu.agentforge.agent.tool.mcp.DefaultMcpClient;
import cloud.changlu.agentforge.agent.tool.mcp.McpClient;
import cloud.changlu.agentforge.agent.tool.mcp.McpToolFactory;
import cloud.changlu.agentforge.agent.tool.mcp.transport.StreamableHttpMcpTransport;
import cloud.changlu.agentforge.model.tool.ToolExecutor;
import cloud.changlu.agentforge.model.tool.spec.ToolSpecification;
import cloud.changlu.agentforge.service.modules.agent.domain.config.McpToolConfig;
import cloud.changlu.agentforge.service.modules.plugins.mcp.dao.McpPluginMapper;
import cloud.changlu.agentforge.service.modules.plugins.mcp.dao.McpPluginServerMapper;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.pojo.McpPluginPojo;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.pojo.McpPluginServerPojo;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.transfer.McpPluginTransfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按 Agent 配置中绑定的 MCP 插件与 Server 构建 Agent 工具。
 *
 * <p>单个 Server 的握手或工具发现失败只影响该 Server：记录 WARN 后跳过，不阻断 Agent 构建。
 *
 * @author changlu
 * @since 2026-10-27
 */
@Component
public class McpAgentToolLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpAgentToolLoader.class);

    private static final int STATUS_ENABLED = 1;
    private static final int NOT_DELETED = 0;

    private final McpPluginMapper pluginMapper;
    private final McpPluginServerMapper serverMapper;
    private final ObjectMapper objectMapper;

    public McpAgentToolLoader(
            McpPluginMapper pluginMapper,
            McpPluginServerMapper serverMapper,
            ObjectMapper objectMapper) {
        this.pluginMapper = pluginMapper;
        this.serverMapper = serverMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 加载 Agent 绑定的全部 MCP 工具。
     *
     * <p>Streamable HTTP 传输是无状态的，Client 不持有长连接，成功加载后由 Agent 运行态隐式持有。
     *
     * @param configs Agent 配置中的 MCP 选择关系
     * @return 工具表，可直接注册到 {@code ToolService}
     */
    public Map<ToolSpecification, ToolExecutor> load(List<McpToolConfig> configs) {
        return loadWithThought(configs, false);
    }

    /**
     * 加载 Agent 绑定的全部 MCP 工具，可选择向工具 Schema 注入思考参数。
     *
     * @param configs Agent 配置中的 MCP 选择关系
     * @param withThought 是否追加必填的思考参数
     * @return 工具表，可直接注册到 {@code ToolService}
     */
    public Map<ToolSpecification, ToolExecutor> loadWithThought(
            List<McpToolConfig> configs, boolean withThought) {
        if (configs == null || configs.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<ToolSpecification, ToolExecutor> tools =
                new LinkedHashMap<ToolSpecification, ToolExecutor>();
        for (McpToolConfig config : configs) {
            if (config == null || config.getPluginId() == null) {
                continue;
            }
            Set<Long> serverIds = normalizeServerIds(config.getServerIds());
            if (serverIds.isEmpty()) {
                continue;
            }
            McpPluginPojo plugin = pluginMapper.selectById(config.getPluginId());
            if (!isPluginAvailable(plugin)) {
                LOGGER.warn(
                        "[MCP-Agent] skip unavailable plugin | pluginId={}", config.getPluginId());
                continue;
            }
            List<McpPluginServerPojo> servers = loadServers(config.getPluginId(), serverIds);
            for (McpPluginServerPojo server : servers) {
                loadServer(tools, plugin, server, withThought);
            }
        }
        return tools;
    }

    private void loadServer(
            Map<ToolSpecification, ToolExecutor> tools,
            McpPluginPojo plugin,
            McpPluginServerPojo server,
            boolean withThought) {
        String alias = buildAlias(plugin.getId(), server.getServerName());
        McpClient client = null;
        boolean loaded = false;
        try {
            client =
                    DefaultMcpClient.builder()
                            .serverAlias(alias)
                            .transport(
                                    StreamableHttpMcpTransport.builder()
                                            .endpoint(server.getServerUrl())
                                            .headers(
                                                    McpPluginTransfer.readHeaders(
                                                            server.getHeaders(), objectMapper))
                                            .build())
                            .build();
            Map<ToolSpecification, ToolExecutor> serverTools =
                    McpToolFactory.buildToolsWithThought(client, alias, withThought);
            tools.putAll(serverTools);
            loaded = true;
            LOGGER.info(
                    "[MCP-Agent] loaded server tools | pluginId={}, serverId={}, serverName={}, toolCount={}",
                    plugin.getId(),
                    server.getId(),
                    server.getServerName(),
                    serverTools.size());
        } catch (RuntimeException e) {
            LOGGER.warn(
                    "[MCP-Agent] failed to initialize server, skip it | pluginId={}, serverId={}, serverName={}, error={}",
                    plugin.getId(),
                    server.getId(),
                    server.getServerName(),
                    e.getMessage(),
                    e);
        } finally {
            // 加载成功的 Client 需继续为 ToolExecutor 服务，仅在失败时回收。
            if (!loaded && client != null) {
                client.close();
            }
        }
    }

    private List<McpPluginServerPojo> loadServers(Long pluginId, Set<Long> serverIds) {
        List<McpPluginServerPojo> selected =
                serverMapper.selectByIds(new ArrayList<Long>(serverIds));
        if (selected.isEmpty()) {
            return Collections.emptyList();
        }
        List<McpPluginServerPojo> result = new ArrayList<McpPluginServerPojo>(selected.size());
        for (McpPluginServerPojo server : selected) {
            if (!pluginId.equals(server.getPluginId())) {
                continue;
            }
            if (server.getStatus() == null || server.getStatus() != STATUS_ENABLED) {
                continue;
            }
            if (server.getIsDeleted() != null && server.getIsDeleted() != NOT_DELETED) {
                continue;
            }
            result.add(server);
        }
        return result;
    }

    private boolean isPluginAvailable(McpPluginPojo plugin) {
        return plugin != null
                && plugin.getStatus() != null
                && plugin.getStatus() == STATUS_ENABLED
                && (plugin.getIsDeleted() == null || plugin.getIsDeleted() == NOT_DELETED);
    }

    private Set<Long> normalizeServerIds(Long[] serverIds) {
        if (serverIds == null || serverIds.length == 0) {
            return Collections.emptySet();
        }
        Set<Long> result = new LinkedHashSet<Long>();
        for (Long serverId : serverIds) {
            if (serverId != null) {
                result.add(serverId);
            }
        }
        return result;
    }

    /** 工具名前缀：{@code mcp_{pluginId}_{normalizedServerName}}。 */
    static String buildAlias(Long pluginId, String serverName) {
        return "mcp_" + pluginId + "_" + normalizeAlias(serverName);
    }

    private static String normalizeAlias(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "server";
        }
        return value.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}

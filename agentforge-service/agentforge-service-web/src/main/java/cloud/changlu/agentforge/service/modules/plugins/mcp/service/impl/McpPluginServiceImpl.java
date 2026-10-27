package cloud.changlu.agentforge.service.modules.plugins.mcp.service.impl;

import cloud.changlu.agentforge.agent.tool.mcp.DefaultMcpClient;
import cloud.changlu.agentforge.agent.tool.mcp.McpClient;
import cloud.changlu.agentforge.agent.tool.mcp.domain.McpTool;
import cloud.changlu.agentforge.agent.tool.mcp.transport.StreamableHttpMcpTransport;
import cloud.changlu.agentforge.service.common.pager.PageResult;
import cloud.changlu.agentforge.service.modules.plugins.mcp.dao.McpPluginMapper;
import cloud.changlu.agentforge.service.modules.plugins.mcp.dao.McpPluginServerMapper;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.pojo.McpPluginPojo;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.pojo.McpPluginServerPojo;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.req.McpImportReq;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.req.McpPluginSaveReq;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.req.McpServerPageQueryReq;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.req.McpServerSaveReq;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.transfer.McpPluginTransfer;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.vo.McpImportResultVo;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.vo.McpPluginVo;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.vo.McpServerDetailVo;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.vo.McpServerVo;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.vo.McpToolTestVo;
import cloud.changlu.agentforge.service.modules.plugins.mcp.enums.McpTransportTypeEnum;
import cloud.changlu.agentforge.service.modules.plugins.mcp.service.IMcpPluginService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 插件与 Server 管理 Service 实现。
 *
 * @author changlu
 * @since 2026-10-27
 */
@Service
public class McpPluginServiceImpl implements IMcpPluginService {

    private static final long DEFAULT_USER_ID = 1L;
    private static final int NOT_DELETED = 0;
    private static final int STATUS_ENABLED = 1;

    private final McpPluginMapper pluginMapper;
    private final McpPluginServerMapper serverMapper;
    private final ObjectMapper objectMapper;

    public McpPluginServiceImpl(
            McpPluginMapper pluginMapper,
            McpPluginServerMapper serverMapper,
            ObjectMapper objectMapper) {
        this.pluginMapper = pluginMapper;
        this.serverMapper = serverMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<McpPluginVo> list(String keyword, boolean onlyEnabled) {
        List<McpPluginPojo> plugins =
                pluginMapper.selectList(trimToNull(keyword), null, onlyEnabled);
        if (plugins.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> pluginIds = new ArrayList<Long>(plugins.size());
        for (McpPluginPojo plugin : plugins) {
            pluginIds.add(plugin.getId());
        }
        Map<Long, List<McpServerVo>> grouped =
                groupServers(serverMapper.selectByPluginIds(pluginIds, onlyEnabled));
        List<McpPluginVo> result = new ArrayList<McpPluginVo>(plugins.size());
        for (McpPluginPojo plugin : plugins) {
            List<McpServerVo> servers = grouped.get(plugin.getId());
            result.add(
                    McpPluginTransfer.toPluginVo(
                            plugin,
                            servers == null ? Collections.<McpServerVo>emptyList() : servers));
        }
        return result;
    }

    @Override
    public McpPluginVo detail(Long id) {
        McpPluginPojo plugin = requirePlugin(id);
        List<McpServerVo> servers =
                McpPluginTransfer.toServerVoList(
                        serverMapper.selectByPluginId(id, false), objectMapper);
        return McpPluginTransfer.toPluginVo(plugin, servers);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public McpPluginVo savePlugin(McpPluginSaveReq req) {
        validatePlugin(req);
        McpPluginPojo pojo = McpPluginTransfer.toPluginPojo(req);
        Date now = new Date();
        if (pojo.getId() == null) {
            assertPluginNameUnique(pojo.getName(), null);
            pojo.setCreateUserId(DEFAULT_USER_ID);
            pojo.setIsDeleted(NOT_DELETED);
            pojo.setStatus(normalizeStatus(req.getStatus(), STATUS_ENABLED));
            pojo.setCreateTime(now);
            pojo.setModifyTime(now);
            pluginMapper.insert(pojo);
        } else {
            McpPluginPojo exists = requirePlugin(pojo.getId());
            assertPluginNameUnique(pojo.getName(), pojo.getId());
            pojo.setStatus(normalizeStatus(req.getStatus(), exists.getStatus()));
            pojo.setModifyTime(now);
            pluginMapper.update(pojo);
        }
        return detail(pojo.getId());
    }

    @Override
    public void updatePluginStatus(Long id, Integer status) {
        requirePlugin(id);
        pluginMapper.updateStatus(id, normalizeStatus(status, null));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePlugin(Long id) {
        requirePlugin(id);
        pluginMapper.deleteById(id);
        serverMapper.deleteByPluginId(id);
    }

    @Override
    public PageResult<List<McpServerVo>> pageServers(McpServerPageQueryReq query) {
        McpServerPageQueryReq actual = query == null ? new McpServerPageQueryReq() : query;
        int currentPage = actual.getPage() == null || actual.getPage() < 1 ? 1 : actual.getPage();
        int pageSize =
                actual.getPageSize() == null || actual.getPageSize() < 1
                        ? 10
                        : Math.min(actual.getPageSize(), 100);
        actual.setPage(currentPage);
        actual.setPageSize(pageSize);
        if (actual.getStatus() != null) {
            normalizeStatus(actual.getStatus(), null);
        }
        List<Long> pluginIds = null;
        if (actual.getPluginId() != null) {
            requirePlugin(actual.getPluginId());
            pluginIds = Collections.singletonList(actual.getPluginId());
        }
        long totalCount = serverMapper.selectCount(actual, pluginIds);
        if (totalCount == 0) {
            return new PageResult<List<McpServerVo>>(
                    currentPage, pageSize, 0, Collections.<McpServerVo>emptyList());
        }
        List<McpServerVo> data =
                McpPluginTransfer.toServerVoList(
                        serverMapper.selectPage(actual, pluginIds), objectMapper);
        return new PageResult<List<McpServerVo>>(currentPage, pageSize, totalCount, data);
    }

    @Override
    public McpServerDetailVo getServer(Long id) {
        return McpPluginTransfer.toServerDetailVo(requireServer(id), objectMapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public McpServerVo saveServer(McpServerSaveReq req) {
        validateServer(req);
        McpPluginPojo plugin = requirePlugin(req.getPluginId());
        McpPluginServerPojo pojo = McpPluginTransfer.toServerPojo(req, objectMapper);
        pojo.setTransportType(normalizeTransport(req.getTransportType()));
        Date now = new Date();
        if (pojo.getId() == null) {
            assertServerNameUnique(req.getPluginId(), pojo.getServerName(), null);
            pojo.setCreateUserId(DEFAULT_USER_ID);
            pojo.setIsDeleted(NOT_DELETED);
            pojo.setStatus(normalizeStatus(req.getStatus(), STATUS_ENABLED));
            pojo.setCreateTime(now);
            pojo.setModifyTime(now);
            serverMapper.insert(pojo);
        } else {
            McpPluginServerPojo exists = requireServer(pojo.getId());
            if (!plugin.getId().equals(exists.getPluginId())) {
                throw new IllegalArgumentException("不能修改 MCP Server 所属插件");
            }
            assertServerNameUnique(req.getPluginId(), pojo.getServerName(), pojo.getId());
            pojo.setStatus(normalizeStatus(req.getStatus(), exists.getStatus()));
            pojo.setModifyTime(now);
            serverMapper.update(pojo);
        }
        return McpPluginTransfer.toServerVo(requireServer(pojo.getId()), objectMapper);
    }

    @Override
    public void updateServerStatus(Long id, Integer status) {
        requireServer(id);
        serverMapper.updateStatus(id, normalizeStatus(status, null));
    }

    @Override
    public void deleteServer(Long id) {
        requireServer(id);
        serverMapper.deleteById(id);
    }

    @Override
    public McpToolTestVo testServerTools(Long id) {
        McpPluginServerPojo server = requireServer(id);
        long start = System.currentTimeMillis();
        McpClient client = null;
        try {
            client = buildClient(server);
            List<McpTool> tools = client.listTools();
            return McpToolTestVo.success(id, tools, System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            return McpToolTestVo.failure(id, e.getMessage(), System.currentTimeMillis() - start);
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public McpImportResultVo importServers(McpImportReq req) {
        if (req == null || isBlank(req.getName())) {
            throw new IllegalArgumentException("插件名称不能为空");
        }
        Map<String, JsonNode> httpServers = new LinkedHashMap<String, JsonNode>();
        collectHttpServers(req.getMcpServers(), httpServers);
        if (httpServers.isEmpty()) {
            throw new IllegalArgumentException("未找到可导入的 HTTP MCP Server（配置项必须包含 url）");
        }

        McpPluginSaveReq pluginReq = new McpPluginSaveReq();
        pluginReq.setName(req.getName());
        pluginReq.setDescription(req.getDescription());
        pluginReq.setIcon(req.getIcon());
        pluginReq.setStatus(STATUS_ENABLED);
        McpPluginVo plugin = savePlugin(pluginReq);

        McpImportResultVo result = new McpImportResultVo();
        result.setPluginId(plugin.getId());
        List<String> errors = new ArrayList<String>();
        int imported = 0;
        for (Map.Entry<String, JsonNode> entry : httpServers.entrySet()) {
            JsonNode config = entry.getValue();
            McpServerSaveReq serverReq = new McpServerSaveReq();
            serverReq.setPluginId(plugin.getId());
            serverReq.setServerName(entry.getKey());
            serverReq.setServerUrl(text(config, "url"));
            // 导入片段的 transportType 为字符串（如 "streamable_http"），统一转成数字枚举编码。
            serverReq.setTransportType(resolveImportTransport(config.get("transportType")));
            serverReq.setStatus(STATUS_ENABLED);
            serverReq.setHeaders(parseHeaders(entry.getKey(), config.get("headers"), errors));
            try {
                saveServer(serverReq);
                imported++;
            } catch (RuntimeException e) {
                errors.add(entry.getKey() + ": " + e.getMessage());
            }
        }
        result.setImported(imported);
        result.setSkipped(httpServers.size() - imported);
        result.setErrors(errors);
        return result;
    }

    @Override
    public String exportServers(Long id) {
        McpPluginPojo plugin = requirePlugin(id);
        List<McpPluginServerPojo> servers = serverMapper.selectByPluginId(plugin.getId(), false);
        Map<String, Object> mcpServers = new LinkedHashMap<String, Object>();
        for (McpPluginServerPojo server : servers) {
            Map<String, Object> config = new LinkedHashMap<String, Object>();
            config.put("url", server.getServerUrl());
            config.put("transportType", server.getTransportType());
            Map<String, String> headers =
                    McpPluginTransfer.maskHeaders(
                            McpPluginTransfer.readHeaders(server.getHeaders(), objectMapper));
            if (!headers.isEmpty()) {
                config.put("headers", headers);
            }
            mcpServers.put(server.getServerName(), config);
        }
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("mcpServers", mcpServers);
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalArgumentException("MCP Server 配置序列化失败", e);
        }
    }

    private McpClient buildClient(McpPluginServerPojo server) {
        return DefaultMcpClient.builder()
                .serverAlias(server.getServerName())
                .transport(
                        StreamableHttpMcpTransport.builder()
                                .endpoint(server.getServerUrl())
                                .headers(
                                        McpPluginTransfer.readHeaders(
                                                server.getHeaders(), objectMapper))
                                .build())
                .build();
    }

    private void collectHttpServers(JsonNode node, Map<String, JsonNode> servers) {
        if (node == null || !node.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode config = entry.getValue();
            if (config == null || !config.isObject()) {
                continue;
            }
            if (!isBlank(text(config, "url"))) {
                servers.put(entry.getKey(), config);
            } else {
                // 部分平台会把 Server 配置再包一层同名对象，这里递归解包。
                collectHttpServers(config, servers);
            }
        }
    }

    private Map<String, String> parseHeaders(
            String serverName, JsonNode headers, List<String> errors) {
        if (headers == null || headers.isNull()) {
            return null;
        }
        if (!headers.isObject()) {
            errors.add(serverName + ": headers 必须是 JSON 对象");
            return null;
        }
        Map<String, String> result = new LinkedHashMap<String, String>();
        Iterator<Map.Entry<String, JsonNode>> fields = headers.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (entry.getValue() != null && !entry.getValue().isNull()) {
                result.put(entry.getKey(), entry.getValue().asText());
            }
        }
        return result;
    }

    private Map<Long, List<McpServerVo>> groupServers(List<McpPluginServerPojo> servers) {
        Map<Long, List<McpServerVo>> grouped = new LinkedHashMap<Long, List<McpServerVo>>();
        for (McpPluginServerPojo server : servers) {
            List<McpServerVo> list = grouped.get(server.getPluginId());
            if (list == null) {
                list = new ArrayList<McpServerVo>();
                grouped.put(server.getPluginId(), list);
            }
            list.add(McpPluginTransfer.toServerVo(server, objectMapper));
        }
        return grouped;
    }

    private McpPluginPojo requirePlugin(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("MCP 插件 ID 不能为空");
        }
        McpPluginPojo plugin = pluginMapper.selectById(id);
        if (plugin == null) {
            throw new IllegalArgumentException("MCP 插件不存在, id: " + id);
        }
        return plugin;
    }

    private McpPluginServerPojo requireServer(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("MCP Server ID 不能为空");
        }
        McpPluginServerPojo server = serverMapper.selectById(id);
        if (server == null) {
            throw new IllegalArgumentException("MCP Server 不存在, id: " + id);
        }
        return server;
    }

    private void validatePlugin(McpPluginSaveReq req) {
        if (req == null || isBlank(req.getName())) {
            throw new IllegalArgumentException("插件名称不能为空");
        }
        if (req.getName().trim().length() > 128) {
            throw new IllegalArgumentException("插件名称不能超过 128 个字符");
        }
        if (req.getDescription() != null && req.getDescription().length() > 1024) {
            throw new IllegalArgumentException("插件描述不能超过 1024 个字符");
        }
        if (req.getIcon() != null && req.getIcon().length() > 512) {
            throw new IllegalArgumentException("插件图标地址不能超过 512 个字符");
        }
        if (req.getStatus() != null) {
            normalizeStatus(req.getStatus(), null);
        }
    }

    private void validateServer(McpServerSaveReq req) {
        if (req == null || req.getPluginId() == null) {
            throw new IllegalArgumentException("插件 ID 不能为空");
        }
        if (isBlank(req.getServerName())) {
            throw new IllegalArgumentException("Server 名称不能为空");
        }
        if (req.getServerName().trim().length() > 128) {
            throw new IllegalArgumentException("Server 名称不能超过 128 个字符");
        }
        if (isBlank(req.getServerUrl())) {
            throw new IllegalArgumentException("Server URL 不能为空");
        }
        String url = req.getServerUrl().trim();
        if (url.length() > 2048) {
            throw new IllegalArgumentException("Server URL 不能超过 2048 个字符");
        }
        String lower = url.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IllegalArgumentException("当前仅支持 HTTP/HTTPS MCP 服务");
        }
        if (req.getStatus() != null) {
            normalizeStatus(req.getStatus(), null);
        }
        normalizeTransport(req.getTransportType());
    }

    private void assertPluginNameUnique(String name, Long excludeId) {
        long count = pluginMapper.countByName(name, DEFAULT_USER_ID, excludeId);
        if (count > 0) {
            throw new IllegalArgumentException("MCP 插件名称已存在: " + name);
        }
    }

    private void assertServerNameUnique(Long pluginId, String serverName, Long excludeId) {
        long count = serverMapper.countByServerName(pluginId, serverName, excludeId);
        if (count > 0) {
            throw new IllegalArgumentException("同一插件下 MCP Server 名称不能重复: " + serverName);
        }
    }

    private Integer normalizeTransport(Integer transport) {
        if (transport == null) {
            return McpTransportTypeEnum.defaultCode();
        }
        return McpTransportTypeEnum.requireValid(transport).getCode();
    }

    /** 导入片段中的 transportType 可能是字符串（"streamable_http"/"sse"）或数字，统一转成枚举编码。 */
    private Integer resolveImportTransport(JsonNode node) {
        if (node == null || node.isNull()) {
            return McpTransportTypeEnum.defaultCode();
        }
        if (node.isNumber()) {
            return normalizeTransport(node.asInt());
        }
        String text = node.asText();
        if (isBlank(text)) {
            return McpTransportTypeEnum.defaultCode();
        }
        McpTransportTypeEnum type = McpTransportTypeEnum.ofText(text);
        if (type == null) {
            throw new IllegalArgumentException("暂不支持 MCP 传输协议: " + text);
        }
        return type.getCode();
    }

    private Integer normalizeStatus(Integer status, Integer defaultValue) {
        Integer value = status == null ? defaultValue : status;
        if (value == null) {
            throw new IllegalArgumentException("状态值不能为空");
        }
        if (value != 0 && value != 1) {
            throw new IllegalArgumentException("状态值只能为 0 或 1");
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }
}

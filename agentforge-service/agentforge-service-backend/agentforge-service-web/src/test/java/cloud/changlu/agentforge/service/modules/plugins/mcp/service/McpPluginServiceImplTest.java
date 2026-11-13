package cloud.changlu.agentforge.service.modules.plugins.mcp.service;

import cloud.changlu.agentforge.agent.tool.mcp.domain.McpTool;
import cloud.changlu.agentforge.service.common.pager.PageResult;
import cloud.changlu.agentforge.service.modules.plugins.mcp.dao.McpPluginMapper;
import cloud.changlu.agentforge.service.modules.plugins.mcp.dao.McpPluginServerMapper;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.pojo.McpPluginPojo;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.pojo.McpPluginServerPojo;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.req.McpServerPageQueryReq;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.vo.McpServerVo;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.vo.McpToolTestVo;
import cloud.changlu.agentforge.service.modules.plugins.mcp.service.impl.McpPluginServiceImpl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * MCP 插件核心接口验证：快捷复制（export）、Server 列表分页、工具列表（tools）结果结构。
 *
 * <p>本测试不依赖 Spring 容器与数据库，Mapper 通过动态代理桩实现，重点验证接口返回内容的正确性。
 */
public class McpPluginServiceImplTest {

    private static final long PLUGIN_ID = 7L;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    public void exportServersKeepsMcpServersShapeAndMasksSecrets() throws Exception {
        McpPluginServerPojo wiki = server(1L, "deepwiki", "https://mcp.deepwiki.com/mcp", 1, null);
        McpPluginServerPojo privateServer =
                server(
                        2L,
                        "private-tools",
                        "https://mcp.example.com/mcp",
                        2,
                        "{\"Authorization\":\"Bearer 1234567890abcdef\",\"X-Trace\":\"on\"}");
        McpPluginServiceImpl service =
                service(plugin(PLUGIN_ID, "wiki"), Arrays.asList(wiki, privateServer));

        String json = service.exportServers(PLUGIN_ID);

        JsonNode root = OBJECT_MAPPER.readTree(json);
        JsonNode servers = root.get("mcpServers");
        assertNotNull("导出结果必须包含 mcpServers 节点", servers);
        assertTrue(servers.has("deepwiki"));
        assertTrue(servers.has("private-tools"));

        assertEquals("https://mcp.deepwiki.com/mcp", servers.get("deepwiki").get("url").asText());
        assertEquals(1, servers.get("deepwiki").get("transportType").asInt());
        assertFalse("未配置 headers 时不输出 headers 节点", servers.get("deepwiki").has("headers"));

        JsonNode headers = servers.get("private-tools").get("headers");
        assertEquals("敏感 Header 必须脱敏", "Bear****cdef", headers.get("Authorization").asText());
        assertEquals("非敏感 Header 原样输出", "on", headers.get("X-Trace").asText());
        assertFalse("导出内容不能出现明文密钥", json.contains("1234567890abcdef"));
    }

    @Test
    public void pageServersFiltersByPluginAndClampsPageSize() {
        McpPluginServerPojo server =
                server(1L, "deepwiki", "https://mcp.deepwiki.com/mcp", 1, null);
        final List<McpServerPageQueryReq> captured = new ArrayList<McpServerPageQueryReq>();
        final List<List<Long>> capturedPluginIds = new ArrayList<List<Long>>();
        McpPluginServerMapper serverMapper =
                (McpPluginServerMapper)
                        proxy(
                                McpPluginServerMapper.class,
                                (method, args) -> {
                                    if ("selectCount".equals(method)) {
                                        captured.add((McpServerPageQueryReq) args[0]);
                                        capturedPluginIds.add(castPluginIds(args[1]));
                                        return 1L;
                                    }
                                    if ("selectPage".equals(method)) {
                                        return Collections.singletonList(server);
                                    }
                                    return null;
                                });
        McpPluginServiceImpl service =
                new McpPluginServiceImpl(
                        pluginMapperStub(plugin(PLUGIN_ID, "wiki")), serverMapper, OBJECT_MAPPER);

        McpServerPageQueryReq query = new McpServerPageQueryReq();
        query.setPluginId(PLUGIN_ID);
        query.setServerName("deep");
        query.setPage(0);
        query.setPageSize(500);
        PageResult<List<McpServerVo>> result = service.pageServers(query);

        assertEquals("页码小于 1 时归一到 1", 1, result.getCurrentPage());
        assertEquals("单页上限 100", 100, result.getPageSize());
        assertEquals(1L, result.getTotalCount());
        assertEquals(1, result.getData().size());
        assertEquals("deepwiki", result.getData().get(0).getServerName());
        assertEquals("传输协议与 URL 必须回传给前端", 1, result.getData().get(0).getTransportType().intValue());
        assertEquals(
                "插件过滤条件必须透传到 Mapper",
                Collections.singletonList(PLUGIN_ID),
                capturedPluginIds.get(0));
        assertEquals("deep", captured.get(0).getServerName());
    }

    @Test
    public void testServerToolsReturnsFailureDetailInsteadOfThrowing() {
        // 端口 1 必然拒绝连接，用于验证工具探测失败时返回可读信息而不是抛异常
        McpPluginServerPojo broken = server(9L, "broken", "http://127.0.0.1:1/mcp", 1, null);
        McpPluginServiceImpl service =
                service(plugin(PLUGIN_ID, "wiki"), Collections.singletonList(broken));

        McpToolTestVo result = service.testServerTools(9L);

        assertEquals(Long.valueOf(9L), result.getServerId());
        assertFalse(result.isSuccess());
        assertNotNull("失败原因必须可读", result.getMessage());
        assertTrue(result.getTools().isEmpty());
    }

    @Test
    public void toolTestPayloadSerializesToolNameDescriptionAndSchema() throws Exception {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", Collections.singletonMap("question", "string"));
        McpToolTestVo vo =
                McpToolTestVo.success(
                        1L,
                        Collections.singletonList(new McpTool("read_wiki", "读取 wiki 内容", schema)),
                        12L);

        JsonNode json = OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsString(vo));
        JsonNode tool = json.get("tools").get(0);

        assertTrue("工具必须输出 name 字段", tool.hasNonNull("name"));
        assertEquals("read_wiki", tool.get("name").asText());
        assertEquals("读取 wiki 内容", tool.get("description").asText());
        assertEquals("object", tool.get("inputSchema").get("type").asText());
        assertTrue(tool.get("inputSchema").has("properties"));
    }

    // ---------------- 测试辅助 ----------------

    private static List<Long> castPluginIds(Object raw) {
        if (raw == null) {
            return null;
        }
        List<Long> ids = new ArrayList<Long>();
        for (Object item : (List<?>) raw) {
            ids.add((Long) item);
        }
        return ids;
    }

    private static McpPluginServiceImpl service(
            McpPluginPojo plugin, List<McpPluginServerPojo> servers) {
        McpPluginServerMapper serverMapper =
                (McpPluginServerMapper)
                        proxy(
                                McpPluginServerMapper.class,
                                (method, args) -> {
                                    if ("selectByPluginId".equals(method)) {
                                        return servers;
                                    }
                                    if ("selectById".equals(method)) {
                                        for (McpPluginServerPojo server : servers) {
                                            if (server.getId().equals(args[0])) {
                                                return server;
                                            }
                                        }
                                        return null;
                                    }
                                    return null;
                                });
        return new McpPluginServiceImpl(pluginMapperStub(plugin), serverMapper, OBJECT_MAPPER);
    }

    private static McpPluginMapper pluginMapperStub(McpPluginPojo plugin) {
        return (McpPluginMapper)
                proxy(
                        McpPluginMapper.class,
                        (method, args) -> "selectById".equals(method) ? plugin : null);
    }

    private interface StubHandler {
        Object handle(String method, Object[] args);
    }

    private static Object proxy(Class<?> type, StubHandler handler) {
        return Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                (target, method, args) -> handler.handle(method.getName(), args));
    }

    private static McpPluginPojo plugin(Long id, String name) {
        McpPluginPojo pojo = new McpPluginPojo();
        pojo.setId(id);
        pojo.setName(name);
        pojo.setStatus(1);
        pojo.setCreateUserId(1L);
        return pojo;
    }

    private static McpPluginServerPojo server(
            Long id, String name, String url, Integer transportType, String headers) {
        McpPluginServerPojo pojo = new McpPluginServerPojo();
        pojo.setId(id);
        pojo.setPluginId(PLUGIN_ID);
        pojo.setServerName(name);
        pojo.setServerUrl(url);
        pojo.setTransportType(transportType);
        pojo.setHeaders(headers);
        pojo.setStatus(1);
        pojo.setCreateUserId(1L);
        return pojo;
    }
}

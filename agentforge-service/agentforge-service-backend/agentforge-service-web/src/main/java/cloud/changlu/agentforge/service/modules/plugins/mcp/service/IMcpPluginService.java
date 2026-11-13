package cloud.changlu.agentforge.service.modules.plugins.mcp.service;

import cloud.changlu.agentforge.service.common.pager.PageResult;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.req.McpImportReq;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.req.McpPluginSaveReq;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.req.McpServerPageQueryReq;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.req.McpServerSaveReq;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.vo.McpImportResultVo;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.vo.McpPluginVo;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.vo.McpServerDetailVo;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.vo.McpServerVo;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.vo.McpToolTestVo;

import java.util.List;

/**
 * MCP 插件与 Server 管理 Service。
 *
 * @author changlu
 * @since 2026-10-27
 */
public interface IMcpPluginService {

    /** 查询插件列表，内联其下 Server。{@code onlyEnabled} 为 true 时只返回启用项。 */
    List<McpPluginVo> list(String keyword, boolean onlyEnabled);

    /** 查询插件详情及其 Server。 */
    McpPluginVo detail(Long id);

    /** 新增或更新插件。 */
    McpPluginVo savePlugin(McpPluginSaveReq req);

    /** 启用或禁用插件。 */
    void updatePluginStatus(Long id, Integer status);

    /** 删除插件，并级联逻辑删除其下 Server。 */
    void deletePlugin(Long id);

    /** 分页查询 Server。 */
    PageResult<List<McpServerVo>> pageServers(McpServerPageQueryReq query);

    /** 查询 Server 详情（含明文 Headers，仅编辑回显使用）。 */
    McpServerDetailVo getServer(Long id);

    /** 新增或更新 Server。 */
    McpServerVo saveServer(McpServerSaveReq req);

    /** 启用或禁用 Server。 */
    void updateServerStatus(Long id, Integer status);

    /** 删除 Server。 */
    void deleteServer(Long id);

    /** 临时连接 Server 并执行 {@code tools/list}。 */
    McpToolTestVo testServerTools(Long id);

    /** 从标准 {@code mcpServers} JSON 一键导入 HTTP MCP 服务。 */
    McpImportResultVo importServers(McpImportReq req);

    /** 导出插件下全部 Server 为标准 {@code mcpServers} JSON，Headers 已脱敏。 */
    String exportServers(Long id);
}

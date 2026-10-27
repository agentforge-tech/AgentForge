package cloud.changlu.agentforge.service.modules.plugins.mcp.controller;

import cloud.changlu.agentforge.service.common.pager.PageResult;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.req.McpImportReq;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.req.McpPluginSaveReq;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.req.McpServerPageQueryReq;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.req.McpServerSaveReq;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.req.McpStatusReq;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.vo.McpImportResultVo;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.vo.McpPluginVo;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.vo.McpServerDetailVo;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.vo.McpServerVo;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.vo.McpToolTestVo;
import cloud.changlu.agentforge.service.modules.plugins.mcp.service.IMcpPluginService;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * MCP 插件与 Server 管理 Controller。
 *
 * @author changlu
 * @since 2026-10-27
 */
@RestController
@RequestMapping("/api/mcp-plugin")
@CrossOrigin
public class McpPluginController {

    private final IMcpPluginService mcpPluginService;

    public McpPluginController(IMcpPluginService mcpPluginService) {
        this.mcpPluginService = mcpPluginService;
    }

    @GetMapping("/list")
    public List<McpPluginVo> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "onlyEnabled", required = false, defaultValue = "false")
                    boolean onlyEnabled) {
        return mcpPluginService.list(keyword, onlyEnabled);
    }

    @GetMapping("/{id}")
    public McpPluginVo detail(@PathVariable("id") Long id) {
        return mcpPluginService.detail(id);
    }

    @PostMapping("/upsert")
    public McpPluginVo savePlugin(@RequestBody McpPluginSaveReq req) {
        return mcpPluginService.savePlugin(req);
    }

    @PostMapping("/{id}/status")
    public void updatePluginStatus(
            @PathVariable("id") Long id, @RequestBody(required = false) McpStatusReq req) {
        mcpPluginService.updatePluginStatus(id, req == null ? null : req.getStatus());
    }

    @DeleteMapping("/{id}")
    public void deletePlugin(@PathVariable("id") Long id) {
        mcpPluginService.deletePlugin(id);
    }

    @GetMapping("/{id}/export")
    public String exportServers(@PathVariable("id") Long id) {
        return mcpPluginService.exportServers(id);
    }

    @PostMapping("/import")
    public McpImportResultVo importServers(@RequestBody McpImportReq req) {
        return mcpPluginService.importServers(req);
    }

    @PostMapping("/server/page")
    public PageResult<List<McpServerVo>> pageServers(
            @RequestBody(required = false) McpServerPageQueryReq query) {
        return mcpPluginService.pageServers(query);
    }

    @GetMapping("/server/{id}")
    public McpServerDetailVo getServer(@PathVariable("id") Long id) {
        return mcpPluginService.getServer(id);
    }

    @PostMapping("/server/upsert")
    public McpServerVo saveServer(@RequestBody McpServerSaveReq req) {
        return mcpPluginService.saveServer(req);
    }

    @PostMapping("/server/{id}/status")
    public void updateServerStatus(
            @PathVariable("id") Long id, @RequestBody(required = false) McpStatusReq req) {
        mcpPluginService.updateServerStatus(id, req == null ? null : req.getStatus());
    }

    @DeleteMapping("/server/{id}")
    public void deleteServer(@PathVariable("id") Long id) {
        mcpPluginService.deleteServer(id);
    }

    @GetMapping("/server/{id}/tools")
    public McpToolTestVo testServerTools(@PathVariable("id") Long id) {
        return mcpPluginService.testServerTools(id);
    }
}

package cloud.changlu.agentforge.service.modules.plugins.mcp.domain.vo;

import cloud.changlu.agentforge.agent.tool.mcp.domain.McpTool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MCP Server 工具探测结果。
 *
 * @author changlu
 * @since 2026-10-27
 */
public class McpToolTestVo {

    private Long serverId;
    private boolean success;
    private String message;
    private List<McpTool> tools = Collections.emptyList();
    private long elapsedMs;

    public static McpToolTestVo success(Long serverId, List<McpTool> tools, long elapsedMs) {
        McpToolTestVo vo = new McpToolTestVo();
        vo.setServerId(serverId);
        vo.setSuccess(true);
        vo.setMessage("success");
        vo.setTools(tools == null ? new ArrayList<McpTool>() : tools);
        vo.setElapsedMs(elapsedMs);
        return vo;
    }

    public static McpToolTestVo failure(Long serverId, String message, long elapsedMs) {
        McpToolTestVo vo = new McpToolTestVo();
        vo.setServerId(serverId);
        vo.setSuccess(false);
        vo.setMessage(message);
        vo.setElapsedMs(elapsedMs);
        return vo;
    }

    public Long getServerId() {
        return serverId;
    }

    public void setServerId(Long serverId) {
        this.serverId = serverId;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<McpTool> getTools() {
        return tools;
    }

    public void setTools(List<McpTool> tools) {
        this.tools = tools == null ? Collections.<McpTool>emptyList() : tools;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }
}

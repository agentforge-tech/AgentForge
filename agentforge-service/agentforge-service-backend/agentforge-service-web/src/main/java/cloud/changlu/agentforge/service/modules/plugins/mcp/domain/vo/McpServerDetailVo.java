package cloud.changlu.agentforge.service.modules.plugins.mcp.domain.vo;

import java.util.Map;

/**
 * MCP Server 详情，包含明文 Headers，仅供单条编辑回显使用。
 *
 * <p>列表与选择接口一律使用 {@link McpServerVo}，避免批量泄露密钥。
 *
 * @author changlu
 * @since 2026-10-27
 */
public class McpServerDetailVo extends McpServerVo {

    private Map<String, String> rawHeaders;

    public Map<String, String> getRawHeaders() {
        return rawHeaders;
    }

    public void setRawHeaders(Map<String, String> rawHeaders) {
        this.rawHeaders = rawHeaders;
    }
}

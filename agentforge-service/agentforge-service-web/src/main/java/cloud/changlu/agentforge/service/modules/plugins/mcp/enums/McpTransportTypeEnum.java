package cloud.changlu.agentforge.service.modules.plugins.mcp.enums;

/**
 * MCP 传输协议枚举。本期仅支持远程 HTTP 场景。
 *
 * @author changlu
 * @since 2026-10-27
 */
public enum McpTransportTypeEnum {

    /** Streamable HTTP，框架 {@code StreamableHttpMcpTransport} 默认协议。 */
    STREAMABLE_HTTP(1, "Streamable HTTP"),

    /** SSE，本期复用 Streamable HTTP 传输，专属传输能力后续补齐。 */
    SSE(2, "SSE");

    private final int code;
    private final String desc;

    McpTransportTypeEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /** 默认传输协议编码。 */
    public static int defaultCode() {
        return STREAMABLE_HTTP.code;
    }

    /** 按编码查找枚举；不存在时返回 {@code null}。 */
    public static McpTransportTypeEnum of(Integer code) {
        if (code == null) {
            return null;
        }
        for (McpTransportTypeEnum value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return null;
    }

    /** 校验编码是否合法，非法时抛出 {@link IllegalArgumentException}。 */
    public static McpTransportTypeEnum requireValid(Integer code) {
        McpTransportTypeEnum type = of(code);
        if (type == null) {
            throw new IllegalArgumentException("暂不支持 MCP 传输协议: " + code);
        }
        return type;
    }

    /** 按文本编码查找（兼容导入片段中的 "streamable_http"/"sse" 写法）；不存在时返回 {@code null}。 */
    public static McpTransportTypeEnum ofText(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.trim().toLowerCase();
        if ("streamable_http".equals(normalized) || "streamable-http".equals(normalized)) {
            return STREAMABLE_HTTP;
        }
        if ("sse".equals(normalized)) {
            return SSE;
        }
        return null;
    }
}

package cloud.changlu.agentforge.model.http;

import java.util.Map;

/**
 * 默认的客户端标识（User-Agent）。
 *
 * <p>JDK 的 {@link java.net.HttpURLConnection} 在调用方未设置 User-Agent 时会自动补上 {@code Java/1.8.0_x}
 * 这类签名。部分网关（尤其是 OpenAI 兼容中转站前面套了 Cloudflare Browser Integrity Check 的情况）会把这种非浏览器签名直接判定为爬虫并返回 HTTP
 * 403（error code: 1010），请求根本到不了业务层。因此 transport 层必须始终提供一个产品化的 User-Agent。
 *
 * <p>调用方显式传入的 User-Agent 优先级最高，transport 只在缺失时兜底。
 *
 * @author changlu
 * @date 2026/11/22
 */
public final class HttpUserAgent {

    /** 标准 User-Agent 请求头名称。 */
    public static final String HEADER = "User-Agent";

    private static final String PRODUCT = "AgentForge";
    private static final String FALLBACK_VERSION = "1.0";

    private HttpUserAgent() {
        // 工具类，禁止实例化
    }

    /** 返回内置默认 User-Agent，形如 {@code AgentForge/1.0 (Java/1.8.0_412; Mac OS X)}。 */
    public static String defaultUserAgent() {
        return productUserAgent(PRODUCT, version());
    }

    /** 基于产品名与版本构造 User-Agent，业务方可据此标记自己的应用。 */
    public static String productUserAgent(String product, String version) {
        String safeProduct = sanitize(product, PRODUCT);
        String safeVersion = sanitize(version, FALLBACK_VERSION);
        return safeProduct
                + "/"
                + safeVersion
                + " (Java/"
                + sanitize(System.getProperty("java.version"), "unknown")
                + "; "
                + sanitize(System.getProperty("os.name"), "unknown")
                + ")";
    }

    /** 判断 header map 中是否已存在 User-Agent（大小写不敏感）。 */
    public static boolean containsUserAgent(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return false;
        }
        for (String name : headers.keySet()) {
            if (HEADER.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static String version() {
        Package pkg = HttpUserAgent.class.getPackage();
        String version = pkg == null ? null : pkg.getImplementationVersion();
        return version == null || version.trim().isEmpty() ? FALLBACK_VERSION : version;
    }

    private static String sanitize(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String sanitized = value.replace('\r', '_').replace('\n', '_').trim();
        return sanitized.isEmpty() ? fallback : sanitized;
    }
}

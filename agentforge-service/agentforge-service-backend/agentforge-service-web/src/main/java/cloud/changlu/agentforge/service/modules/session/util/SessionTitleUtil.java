package cloud.changlu.agentforge.service.modules.session.util;

/**
 * 会话标题统一规则：去首尾空白、连续空白合并为单个空格，按字符（code point）取前 {@link #TITLE_MAX_LENGTH} 个字符。
 *
 * <p>前端使用同一规则做本地占位，服务端结果为准。
 */
public final class SessionTitleUtil {

    /** 标题最大字符数，前后端保持一致。 */
    public static final int TITLE_MAX_LENGTH = 20;

    /** 兜底标题。 */
    public static final String DEFAULT_TITLE = "新会话";

    private SessionTitleUtil() {}

    public static String resolve(String message) {
        if (message == null) {
            return DEFAULT_TITLE;
        }
        String normalized = message.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            return DEFAULT_TITLE;
        }
        if (normalized.codePointCount(0, normalized.length()) <= TITLE_MAX_LENGTH) {
            return normalized;
        }
        int end = normalized.offsetByCodePoints(0, TITLE_MAX_LENGTH);
        return normalized.substring(0, end);
    }
}

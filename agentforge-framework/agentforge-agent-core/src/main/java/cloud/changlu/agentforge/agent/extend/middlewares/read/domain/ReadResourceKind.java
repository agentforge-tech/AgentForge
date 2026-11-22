package cloud.changlu.agentforge.agent.extend.middlewares.read.domain;

import java.util.Locale;

/**
 * 读取资源的类型。
 *
 * <p>新增读取能力时只需扩展枚举值，并在消费方补充对应分支，避免散落的字符串比较与拼写错误。 JSON 契约中仍以枚举名（如 {@code IMAGE}）作为 {@code kind}
 * 字段，未知值统一降级为 {@link #UNKNOWN}。
 *
 * @author changlu
 * @since 2026-11-22
 */
public enum ReadResourceKind {
    IMAGE,
    TEXT,
    LINK,
    FILE,
    UNKNOWN;

    /**
     * 把外部字符串转换为枚举，未知或空白值降级为 {@link #UNKNOWN}。
     *
     * @param code JSON 中的 kind 字段
     * @return 对应枚举，绝不返回 null
     */
    public static ReadResourceKind fromCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            return UNKNOWN;
        }
        try {
            return valueOf(code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    /** JSON 契约中的 wire value，与枚举名保持一致。 */
    public String code() {
        return name();
    }

    public boolean isImage() {
        return this == IMAGE;
    }

    public boolean isLink() {
        return this == LINK;
    }
}

package cloud.changlu.agentforge.service.modules.model.enums;

/**
 * LLM 模型厂商枚举，对应 {@code ai_model_config.provider}。
 *
 * @author changlu
 * @since 2026-10-14
 */
public enum LlmProviderEnum {
    OPENAI(1, "OpenAI"),
    ANTHROPIC(2, "Anthropic");

    private final int code;
    private final String desc;

    LlmProviderEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static LlmProviderEnum of(Integer code) {
        if (code == null) {
            return null;
        }
        for (LlmProviderEnum item : values()) {
            if (item.code == code) {
                return item;
            }
        }
        return null;
    }
}

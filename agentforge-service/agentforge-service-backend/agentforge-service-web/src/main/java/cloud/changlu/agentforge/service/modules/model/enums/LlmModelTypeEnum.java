package cloud.changlu.agentforge.service.modules.model.enums;

/**
 * LLM 模型类别枚举，对应 {@code ai_model_config.model_type}。
 *
 * @author changlu
 * @since 2026-10-14
 */
public enum LlmModelTypeEnum {
    CHAT(0, "chat", "llm"),
    EMBEDDING(1, "embedding", "embedding");

    private final int code;
    private final String name;
    private final String desc;

    LlmModelTypeEnum(int code, String name, String desc) {
        this.code = code;
        this.name = name;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDesc() {
        return desc;
    }

    public static LlmModelTypeEnum of(Integer code) {
        if (code == null) {
            return null;
        }
        for (LlmModelTypeEnum item : values()) {
            if (item.code == code) {
                return item;
            }
        }
        return null;
    }
}

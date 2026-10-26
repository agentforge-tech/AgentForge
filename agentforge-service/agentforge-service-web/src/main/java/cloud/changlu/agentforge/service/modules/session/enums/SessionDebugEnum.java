package cloud.changlu.agentforge.service.modules.session.enums;

/** 会话类型，对应 {@code ai_agent_session.is_debug}。 */
public enum SessionDebugEnum {
    FORMAL(0, "正式会话"),
    DEBUG(1, "调试会话");

    private final Integer code;
    private final String description;

    SessionDebugEnum(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    public Integer getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static SessionDebugEnum getByCode(Integer code) {
        if (code == null) {
            return FORMAL;
        }
        for (SessionDebugEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return FORMAL;
    }
}

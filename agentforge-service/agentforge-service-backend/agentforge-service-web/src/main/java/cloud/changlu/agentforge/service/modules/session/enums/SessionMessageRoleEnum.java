package cloud.changlu.agentforge.service.modules.session.enums;

/** 消息角色，对应 {@code ai_agent_session_message.role_type}。 */
public enum SessionMessageRoleEnum {
    USER(1, "用户"),
    AI(2, "AI");

    private final Integer code;
    private final String description;

    SessionMessageRoleEnum(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    public Integer getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static SessionMessageRoleEnum getByCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (SessionMessageRoleEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}

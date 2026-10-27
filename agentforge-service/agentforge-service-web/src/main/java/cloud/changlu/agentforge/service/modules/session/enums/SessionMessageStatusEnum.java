package cloud.changlu.agentforge.service.modules.session.enums;

/** 消息状态，对应 {@code ai_agent_session_message.status}。 */
public enum SessionMessageStatusEnum {
    CREATED(1, "创建"),
    RUNNING(2, "处理中"),
    FAILED(3, "失败"),
    CANCELLED(4, "取消"),
    SUCCEEDED(5, "成功");

    private final Integer code;
    private final String description;

    SessionMessageStatusEnum(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    public Integer getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static SessionMessageStatusEnum getByCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (SessionMessageStatusEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}

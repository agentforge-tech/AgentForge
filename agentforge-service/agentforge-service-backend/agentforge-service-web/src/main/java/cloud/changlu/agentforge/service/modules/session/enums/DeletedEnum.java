package cloud.changlu.agentforge.service.modules.session.enums;

/** 逻辑删除状态，对应会话与消息表的 {@code is_deleted}。 */
public enum DeletedEnum {
    NOT_DELETED(0, "未删除"),
    DELETED(1, "已删除");

    private final Integer code;
    private final String description;

    DeletedEnum(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    public Integer getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}

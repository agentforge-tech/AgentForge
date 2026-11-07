package cloud.changlu.agentforge.agent.extend.middlewares.todoplan.model;

/** 任务计划状态及数字映射。 */
public enum TodoStatus {
    PENDING(1, "pending"),
    IN_PROGRESS(2, "in_progress"),
    COMPLETED(3, "completed"),
    CANCELLED(4, "cancelled");

    private final int code;
    private final String value;

    TodoStatus(int code, String value) {
        this.code = code;
        this.value = value;
    }

    public int getCode() {
        return code;
    }

    public String getValue() {
        return value;
    }

    public static TodoStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (TodoStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }

    public static TodoStatus fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (TodoStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        return null;
    }
}

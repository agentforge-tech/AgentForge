package cloud.changlu.agentforge.agent.extend.middlewares.todoplan.model;

/** 任务计划优先级及数字映射。 */
public enum TodoPriority {
    HIGH(1, "high"),
    MEDIUM(2, "medium"),
    LOW(3, "low");

    private final int code;
    private final String value;

    TodoPriority(int code, String value) {
        this.code = code;
        this.value = value;
    }

    public int getCode() {
        return code;
    }

    public String getValue() {
        return value;
    }

    public static TodoPriority fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (TodoPriority priority : values()) {
            if (priority.code == code) {
                return priority;
            }
        }
        return null;
    }

    public static TodoPriority fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (TodoPriority priority : values()) {
            if (priority.value.equalsIgnoreCase(value)) {
                return priority;
            }
        }
        return null;
    }
}

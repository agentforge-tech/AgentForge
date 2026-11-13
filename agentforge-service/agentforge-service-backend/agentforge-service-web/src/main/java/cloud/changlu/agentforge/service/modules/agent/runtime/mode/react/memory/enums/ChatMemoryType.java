package cloud.changlu.agentforge.service.modules.agent.runtime.mode.react.memory.enums;

/** 对话记忆类型。 */
public enum ChatMemoryType {
    WINDOW(1, "窗口会话记忆");

    private final int value;
    private final String description;

    ChatMemoryType(int value, String description) {
        this.value = value;
        this.description = description;
    }

    public int getValue() {
        return value;
    }

    public String getDescription() {
        return description;
    }
}

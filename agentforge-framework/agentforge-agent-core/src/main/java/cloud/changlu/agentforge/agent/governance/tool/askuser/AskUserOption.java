package cloud.changlu.agentforge.agent.governance.tool.askuser;

import cloud.changlu.agentforge.model.tool.P;

/**
 * AskUser 选择题的可选项。
 *
 * <p>字段保持非 final，供 Tool Schema 反射生成嵌套对象定义。
 */
public final class AskUserOption {
    @P(name = "id", description = "Stable option id returned in the answer")
    private String id;

    @P(name = "label", description = "Short display text for the option")
    private String label;

    @P(name = "description", description = "Optional explanation of this option", required = false)
    private String description;

    public AskUserOption() {}

    public AskUserOption(String id, String label) {
        this(id, label, null);
    }

    public AskUserOption(String id, String label, String description) {
        this.id = id;
        this.label = label;
        this.description = description;
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label == null || label.trim().isEmpty() ? id : label;
    }

    public String getDescription() {
        return description;
    }
}

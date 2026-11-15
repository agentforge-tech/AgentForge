package cloud.changlu.agentforge.agent.governance.tool.askuser;

/** AskUser 问题类型。 */
public enum AskUserAnswerType {
    SINGLE_CHOICE,
    MULTIPLE_CHOICE,
    TEXT;

    /** 解析模型传入的类型，兼容单选/多选/文本等常见别名。 */
    public static AskUserAnswerType parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            return TEXT;
        }
        String normalized = value.trim().toLowerCase();
        if ("single".equals(normalized)
                || "select".equals(normalized)
                || "choice".equals(normalized)
                || "radio".equals(normalized)
                || "single_choice".equals(normalized)) {
            return SINGLE_CHOICE;
        }
        if ("multiple".equals(normalized)
                || "multi_select".equals(normalized)
                || "checkbox".equals(normalized)
                || "multiple_choice".equals(normalized)) {
            return MULTIPLE_CHOICE;
        }
        if ("input".equals(normalized)
                || "string".equals(normalized)
                || "free_text".equals(normalized)
                || "text".equals(normalized)) {
            return TEXT;
        }
        throw new IllegalArgumentException("unsupported ask_user question type: " + value);
    }

    public boolean isChoice() {
        return this == SINGLE_CHOICE || this == MULTIPLE_CHOICE;
    }
}

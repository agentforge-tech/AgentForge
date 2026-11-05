package cloud.changlu.agentforge.agent.extend.middlewares.compact.domain;

/** 上下文压缩触发来源。 */
public enum CompactTrigger {
    MODEL_CALL("model_call", "模型调用前压缩"),
    TOOL_RESULT("tool_result", "单工具结果截断"),
    CONCURRENT_TOOL_RESULT("concurrent_tool_result", "并发工具结果截断");

    private final String code;
    private final String description;

    CompactTrigger(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}

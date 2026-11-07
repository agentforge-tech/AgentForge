package cloud.changlu.agentforge.agent.component.state.domain;

/** 对外暴露的只读工具执行状态快照。 */
public final class RunningToolState {
    private final String requestId;
    private final String toolName;
    private final ToolExecutionStatus status;
    private final long startedAtMillis;
    private final Long finishedAtMillis;

    public RunningToolState(
            String requestId,
            String toolName,
            ToolExecutionStatus status,
            long startedAtMillis,
            Long finishedAtMillis) {
        this.requestId = requestId;
        this.toolName = toolName;
        this.status = status;
        this.startedAtMillis = startedAtMillis;
        this.finishedAtMillis = finishedAtMillis;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getToolName() {
        return toolName;
    }

    public ToolExecutionStatus getStatus() {
        return status;
    }

    public long getStartedAtMillis() {
        return startedAtMillis;
    }

    public Long getFinishedAtMillis() {
        return finishedAtMillis;
    }
}

package cloud.changlu.agentforge.agent.component.state.domain;

/** 单个工具请求的运行状态。 */
public enum ToolExecutionStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    CANCEL_REQUESTED,
    CANCELLED,
    FAILED
}

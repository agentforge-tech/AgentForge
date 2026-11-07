package cloud.changlu.agentforge.agent.component.state.domain;

import cloud.changlu.agentforge.agent.domain.AgentRunState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Agent 单次执行的不可变状态快照。 */
public final class AgentExecutionState {
    private final String executionId;
    private final AgentExecutionStatus status;
    private final AgentExecutionPhase phase;
    private final int currentStep;
    private final List<RunningToolState> runningTools;
    private final AgentRunState resultState;
    private final String message;
    private final long updatedAtMillis;

    public AgentExecutionState(
            String executionId,
            AgentExecutionStatus status,
            AgentExecutionPhase phase,
            int currentStep,
            List<RunningToolState> runningTools,
            AgentRunState resultState,
            String message,
            long updatedAtMillis) {
        this.executionId = executionId;
        this.status = status;
        this.phase = phase;
        this.currentStep = currentStep;
        this.runningTools =
                Collections.unmodifiableList(
                        runningTools == null
                                ? new ArrayList<RunningToolState>()
                                : new ArrayList<RunningToolState>(runningTools));
        this.resultState = resultState;
        this.message = message;
        this.updatedAtMillis = updatedAtMillis;
    }

    public String getExecutionId() {
        return executionId;
    }

    public AgentExecutionStatus getStatus() {
        return status;
    }

    public AgentExecutionPhase getPhase() {
        return phase;
    }

    public int getCurrentStep() {
        return currentStep;
    }

    public List<RunningToolState> getRunningTools() {
        return runningTools;
    }

    public AgentRunState getResultState() {
        return resultState;
    }

    /** 取消原因或失败摘要；运行中为 null。 */
    public String getMessage() {
        return message;
    }

    public long getUpdatedAtMillis() {
        return updatedAtMillis;
    }
}

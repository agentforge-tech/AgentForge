package cloud.changlu.agentforge.agent.governance.interrupt.domain;

import java.util.List;

public final class AgentSuspendInfo {
    private final String batchId;
    private final List<InterruptRequest> interrupts;

    public AgentSuspendInfo(String batchId, List<InterruptRequest> interrupts) {
        this.batchId = batchId;
        this.interrupts = interrupts;
    }

    public String getBatchId() {
        return batchId;
    }

    public List<InterruptRequest> getInterrupts() {
        return interrupts;
    }
}

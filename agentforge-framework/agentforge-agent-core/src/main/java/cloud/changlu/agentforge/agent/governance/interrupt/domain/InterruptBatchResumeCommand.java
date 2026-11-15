package cloud.changlu.agentforge.agent.governance.interrupt.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class InterruptBatchResumeCommand {
    private final String batchId;
    private final List<InterruptResponse> responses;

    public InterruptBatchResumeCommand(String batchId, List<InterruptResponse> responses) {
        this.batchId = batchId;
        this.responses =
                responses == null
                        ? Collections.<InterruptResponse>emptyList()
                        : Collections.unmodifiableList(new ArrayList<InterruptResponse>(responses));
    }

    public String getBatchId() {
        return batchId;
    }

    public List<InterruptResponse> getResponses() {
        return responses;
    }
}

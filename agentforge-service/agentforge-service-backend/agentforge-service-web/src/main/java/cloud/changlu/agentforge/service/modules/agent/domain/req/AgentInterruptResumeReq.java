package cloud.changlu.agentforge.service.modules.agent.domain.req;

import java.util.List;

/** HITL 批次恢复请求。 */
public class AgentInterruptResumeReq {
    private String batchId;
    private List<AgentInterruptResponseReq> responses;

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public List<AgentInterruptResponseReq> getResponses() {
        return responses;
    }

    public void setResponses(List<AgentInterruptResponseReq> responses) {
        this.responses = responses;
    }
}

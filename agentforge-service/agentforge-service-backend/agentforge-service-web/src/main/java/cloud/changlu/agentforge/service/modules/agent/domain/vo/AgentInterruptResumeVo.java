package cloud.changlu.agentforge.service.modules.agent.domain.vo;

/** HITL 恢复受理结果。 */
public class AgentInterruptResumeVo {
    private String batchId;
    private Long messageId;
    private String status;

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}

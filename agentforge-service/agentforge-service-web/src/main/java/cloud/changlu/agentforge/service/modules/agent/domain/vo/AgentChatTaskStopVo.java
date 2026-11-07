package cloud.changlu.agentforge.service.modules.agent.domain.vo;

/** 停止接口返回：以数据库消息状态作为最终取消结果的权威来源。 */
public class AgentChatTaskStopVo {

    private Long messageId;
    private boolean cancelRequested;
    private Integer status;
    private String statusName;
    private boolean timedOut;

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public void setCancelRequested(boolean cancelRequested) {
        this.cancelRequested = cancelRequested;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getStatusName() {
        return statusName;
    }

    public void setStatusName(String statusName) {
        this.statusName = statusName;
    }

    public boolean isTimedOut() {
        return timedOut;
    }

    public void setTimedOut(boolean timedOut) {
        this.timedOut = timedOut;
    }
}

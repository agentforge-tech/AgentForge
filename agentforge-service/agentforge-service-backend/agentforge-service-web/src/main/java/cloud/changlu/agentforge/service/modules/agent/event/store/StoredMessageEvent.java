package cloud.changlu.agentforge.service.modules.agent.event.store;

import java.util.Date;

/** 一轮 Agent Run 中已经持久化的流式事件。 */
public class StoredMessageEvent {
    private Long id;
    private Long messageId;
    private Long eventSeq;
    private String eventType;
    private String payload;
    private Date createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public Long getEventSeq() {
        return eventSeq;
    }

    public void setEventSeq(Long eventSeq) {
        this.eventSeq = eventSeq;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }
}

package cloud.changlu.agentforge.service.modules.agent.memory.domain;

import java.util.Date;

/**
 * Agent 记忆持久化对象，对应表 {@code ai_agent_memrory}。
 *
 * @author changlu
 * @since 2026-10-25
 */
public class AgentMemoryPojo {

    private Long id;
    private String memoryId;
    private String content;
    private Date createTime;
    private Date modifyTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMemoryId() {
        return memoryId;
    }

    public void setMemoryId(String memoryId) {
        this.memoryId = memoryId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public Date getModifyTime() {
        return modifyTime;
    }

    public void setModifyTime(Date modifyTime) {
        this.modifyTime = modifyTime;
    }
}

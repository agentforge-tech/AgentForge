package cloud.changlu.agentforge.service.modules.agent.domain;

import java.util.Date;

/** Agent 当前发布快照持久化对象，对应表 {@code ai_agent_config_published}。 */
public class AgentConfigPublishedPojo {

    private Long id;
    private String name;
    private Integer type;
    private String agentConfig;
    private String experConfig;
    private Date lastPublishTime;
    private String aid;
    private String avatar;
    private String description;
    private Integer isDeleted;
    private Long createUserId;
    private Date createTime;
    private Date modifyTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getType() {
        return type;
    }

    public void setType(Integer type) {
        this.type = type;
    }

    public String getAgentConfig() {
        return agentConfig;
    }

    public void setAgentConfig(String agentConfig) {
        this.agentConfig = agentConfig;
    }

    public String getExperConfig() {
        return experConfig;
    }

    public void setExperConfig(String experConfig) {
        this.experConfig = experConfig;
    }

    public Date getLastPublishTime() {
        return lastPublishTime;
    }

    public void setLastPublishTime(Date lastPublishTime) {
        this.lastPublishTime = lastPublishTime;
    }

    public String getAid() {
        return aid;
    }

    public void setAid(String aid) {
        this.aid = aid;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getIsDeleted() {
        return isDeleted;
    }

    public void setIsDeleted(Integer isDeleted) {
        this.isDeleted = isDeleted;
    }

    public Long getCreateUserId() {
        return createUserId;
    }

    public void setCreateUserId(Long createUserId) {
        this.createUserId = createUserId;
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

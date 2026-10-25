package cloud.changlu.agentforge.service.modules.agent.domain.vo;

import java.util.Date;

/** Agent 配置返回对象。 */
public class AgentConfigVo {

    private Long id;
    private String aid;
    private String name;
    private Integer type;
    private Long modelId;
    private String modelName;
    private String systemPrompt;
    private Integer chatMemoryType;
    private Integer chatMemoryMaxMessages;
    private Integer publish;
    private Integer isPublic;
    private String avatar;
    private String description;
    private Date createTime;
    private Date modifyTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAid() {
        return aid;
    }

    public void setAid(String aid) {
        this.aid = aid;
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

    public Long getModelId() {
        return modelId;
    }

    public void setModelId(Long modelId) {
        this.modelId = modelId;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public Integer getChatMemoryType() {
        return chatMemoryType;
    }

    public void setChatMemoryType(Integer chatMemoryType) {
        this.chatMemoryType = chatMemoryType;
    }

    public Integer getChatMemoryMaxMessages() {
        return chatMemoryMaxMessages;
    }

    public void setChatMemoryMaxMessages(Integer chatMemoryMaxMessages) {
        this.chatMemoryMaxMessages = chatMemoryMaxMessages;
    }

    public Integer getPublish() {
        return publish;
    }

    public void setPublish(Integer publish) {
        this.publish = publish;
    }

    public Integer getIsPublic() {
        return isPublic;
    }

    public void setIsPublic(Integer isPublic) {
        this.isPublic = isPublic;
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

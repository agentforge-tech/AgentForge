package cloud.changlu.agentforge.service.modules.agent.domain.req;

import cloud.changlu.agentforge.service.modules.agent.domain.config.McpToolConfig;

import java.util.List;

/** Agent 新增/修改请求。 */
public class AgentConfigUpsertReq {

    private Long id;
    private String name;
    private Integer type;
    private String description;
    private Long modelId;
    private String systemPrompt;
    private Integer chatMemoryType;
    private Integer chatMemoryMaxMessages;
    private List<McpToolConfig> mcpPlugins;
    private Boolean submitVersion;
    private String versionRemark;

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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getModelId() {
        return modelId;
    }

    public void setModelId(Long modelId) {
        this.modelId = modelId;
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

    public List<McpToolConfig> getMcpPlugins() {
        return mcpPlugins;
    }

    public void setMcpPlugins(List<McpToolConfig> mcpPlugins) {
        this.mcpPlugins = mcpPlugins;
    }

    public Boolean getSubmitVersion() {
        return submitVersion;
    }

    public void setSubmitVersion(Boolean submitVersion) {
        this.submitVersion = submitVersion;
    }

    public String getVersionRemark() {
        return versionRemark;
    }

    public void setVersionRemark(String versionRemark) {
        this.versionRemark = versionRemark;
    }
}

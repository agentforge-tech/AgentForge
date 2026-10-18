package cloud.changlu.agentforge.service.modules.agent.domain.req;

/** Agent 新增/修改请求。 */
public class AgentConfigUpsertReq {

    private Long id;
    private String name;
    private Long modelId;
    private String systemPrompt;
    private Integer chatMemoryMaxMessages;

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

    public Integer getChatMemoryMaxMessages() {
        return chatMemoryMaxMessages;
    }

    public void setChatMemoryMaxMessages(Integer chatMemoryMaxMessages) {
        this.chatMemoryMaxMessages = chatMemoryMaxMessages;
    }
}

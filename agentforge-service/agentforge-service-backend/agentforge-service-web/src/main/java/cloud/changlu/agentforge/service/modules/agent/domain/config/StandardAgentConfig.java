package cloud.changlu.agentforge.service.modules.agent.domain.config;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 通用单 Agent 配置。
 *
 * <p>保留 ReActAgent 初始化必需的系统提示词、对话模型与记忆配置，以及可选的 MCP 插件绑定。
 */
public class StandardAgentConfig {

    private String systemPrompt;
    private AgentModelConfig chatModel;
    private ChatMemoryConfig chatMemoryConfig;
    private PluginConfig pluginConfig;
    private AgentCapabilityConfig capabilityConfig;

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public AgentModelConfig getChatModel() {
        return chatModel;
    }

    public void setChatModel(AgentModelConfig chatModel) {
        this.chatModel = chatModel;
    }

    public ChatMemoryConfig getChatMemoryConfig() {
        return chatMemoryConfig;
    }

    public void setChatMemoryConfig(ChatMemoryConfig chatMemoryConfig) {
        this.chatMemoryConfig = chatMemoryConfig;
    }

    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    public void setPluginConfig(PluginConfig pluginConfig) {
        this.pluginConfig = pluginConfig;
    }

    public AgentCapabilityConfig getCapabilityConfig() {
        return capabilityConfig;
    }

    public void setCapabilityConfig(AgentCapabilityConfig capabilityConfig) {
        this.capabilityConfig = capabilityConfig;
    }

    /** 是否开启任务计划。没有配置或未显式关闭时默认开启。 */
    public boolean isPlanEnabled() {
        return capabilityConfig == null
                || capabilityConfig.getPlanEnabled() == null
                || Boolean.TRUE.equals(capabilityConfig.getPlanEnabled());
    }

    public static String toJson(StandardAgentConfig config, ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            throw new IllegalArgumentException("StandardAgentConfig 序列化失败", e);
        }
    }

    public static StandardAgentConfig fromJson(String json, ObjectMapper objectMapper) {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("Agent 配置不能为空");
        }
        try {
            return objectMapper.readValue(json, StandardAgentConfig.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("StandardAgentConfig 解析失败", e);
        }
    }
}

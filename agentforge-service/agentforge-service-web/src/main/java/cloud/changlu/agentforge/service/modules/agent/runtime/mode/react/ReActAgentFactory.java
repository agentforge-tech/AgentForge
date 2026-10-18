package cloud.changlu.agentforge.service.modules.agent.runtime.mode.react;

import cloud.changlu.agentforge.agent.ReActAgent;
import cloud.changlu.agentforge.agent.memory.ChatMemoryProvider;
import cloud.changlu.agentforge.model.registry.LlmFactory;
import cloud.changlu.agentforge.model.tool.execution.ToolService;
import cloud.changlu.agentforge.service.modules.agent.domain.config.AgentModelConfig;
import cloud.changlu.agentforge.service.modules.agent.domain.config.StandardAgentConfig;
import cloud.changlu.agentforge.service.modules.model.dao.ModelConfigMapper;
import cloud.changlu.agentforge.service.modules.model.domain.ModelConfigPojo;
import cloud.changlu.agentforge.service.modules.model.domain.transfer.ModelConfigTransfer;

import org.springframework.stereotype.Component;

/** 初版 ReActAgent 工厂，当前仅装配 Agent 基本信息与对话模型。 */
@Component
public class ReActAgentFactory {

    private final ModelConfigMapper modelConfigMapper;

    public ReActAgentFactory(ModelConfigMapper modelConfigMapper) {
        this.modelConfigMapper = modelConfigMapper;
    }

    public StandardReActAgent createReActAgent(StandardAgentConfig standardAgentConfig) {
        if (standardAgentConfig == null || standardAgentConfig.getChatModel() == null) {
            throw new IllegalArgumentException("StandardAgentConfig.chatModel 不能为空");
        }
        AgentModelConfig chatModelConfig = standardAgentConfig.getChatModel();
        ModelConfigPojo modelConfig = modelConfigMapper.selectById(chatModelConfig.getLlmModelId());
        if (modelConfig == null) {
            throw new IllegalArgumentException("对话模型不存在或已被删除");
        }
        int maxMessages =
                standardAgentConfig.getChatMemoryConfig() == null
                                || standardAgentConfig
                                                .getChatMemoryConfig()
                                                .getChatMemoryMaxMessages()
                                        == null
                                || standardAgentConfig
                                                .getChatMemoryConfig()
                                                .getChatMemoryMaxMessages()
                                        < 1
                        ? 100
                        : standardAgentConfig.getChatMemoryConfig().getChatMemoryMaxMessages();
        ReActAgent.ReActAgentBuilder builder =
                ReActAgent.builder()
                        .agentName("StandardReActAgent")
                        .systemPrompt(standardAgentConfig.getSystemPrompt())
                        .chatModel(
                                LlmFactory.buildChatModel(
                                        ModelConfigTransfer.toBasicConfig(modelConfig)))
                        .chatMemoryProvider(
                                ChatMemoryProvider.windowChatMemoryProvider(maxMessages))
                        .toolService(new ToolService());
        return StandardReActAgent.from(builder);
    }
}

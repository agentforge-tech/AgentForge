package cloud.changlu.agentforge.service.modules.agent.runtime.mode.react;

import cloud.changlu.agentforge.agent.ReActAgent;
import cloud.changlu.agentforge.agent.extend.middlewares.LoggingIAgentMiddleware;
import cloud.changlu.agentforge.model.registry.LlmFactory;
import cloud.changlu.agentforge.model.registry.config.LlmBasicConfig;
import cloud.changlu.agentforge.model.registry.constant.ChatModelProperties;
import cloud.changlu.agentforge.model.tool.execution.ToolService;
import cloud.changlu.agentforge.service.modules.agent.domain.config.AgentModelConfig;
import cloud.changlu.agentforge.service.modules.agent.domain.config.StandardAgentConfig;
import cloud.changlu.agentforge.service.modules.agent.runtime.mode.react.memory.factory.ChatMemoryProviderFactory;
import cloud.changlu.agentforge.service.modules.model.dao.ModelConfigMapper;
import cloud.changlu.agentforge.service.modules.model.domain.ModelConfigPojo;
import cloud.changlu.agentforge.service.modules.model.domain.transfer.ModelConfigTransfer;
import cloud.changlu.agentforge.service.plugins.openreach.OpenReachTools;
import cloud.changlu.agentforge.service.stream.ServiceStreamingMiddleware;

import org.springframework.stereotype.Component;

/** Standard ReAct Agent 工厂，负责装配模型、记忆、工具及默认中间件。 */
@Component
public class ReActAgentFactory {

    private final ModelConfigMapper modelConfigMapper;
    private final OpenReachTools openReachTools;
    private final ServiceStreamingMiddleware streamingMiddleware;
    private final ChatMemoryProviderFactory chatMemoryProviderFactory;

    public ReActAgentFactory(
            ModelConfigMapper modelConfigMapper,
            OpenReachTools openReachTools,
            ServiceStreamingMiddleware streamingMiddleware,
            ChatMemoryProviderFactory chatMemoryProviderFactory) {
        this.modelConfigMapper = modelConfigMapper;
        this.openReachTools = openReachTools;
        this.streamingMiddleware = streamingMiddleware;
        this.chatMemoryProviderFactory = chatMemoryProviderFactory;
    }

    public ReActAgent createReActAgent(StandardAgentConfig standardAgentConfig) {
        if (standardAgentConfig == null || standardAgentConfig.getChatModel() == null) {
            throw new IllegalArgumentException("StandardAgentConfig.chatModel 不能为空");
        }
        AgentModelConfig chatModelConfig = standardAgentConfig.getChatModel();
        ModelConfigPojo modelConfig = modelConfigMapper.selectById(chatModelConfig.getLlmModelId());
        if (modelConfig == null) {
            throw new IllegalArgumentException("对话模型不存在或已被删除");
        }
        LlmBasicConfig llmConfig = ModelConfigTransfer.toBasicConfig(modelConfig);
        // 默认模型返回思考
        ChatModelProperties chatModelProperties =
                ChatModelProperties.builder()
                        .timeout(2 * 60 * 60)
                        .maxTokens(chatModelConfig.getMaxOutputToken())
                        .returnThinking(true)
                        .build();
        llmConfig.setProps(chatModelProperties.toProperties());
        ReActAgent.ReActAgentBuilder builder =
                ReActAgent.builder()
                        .agentName("StandardReActAgent")
                        .systemPrompt(standardAgentConfig.getSystemPrompt())
                        .chatModel(LlmFactory.buildChatModel(llmConfig))
                        .streamingChatModel(LlmFactory.buildStreamChatModel(llmConfig))
                        .chatMemoryProvider(
                                chatMemoryProviderFactory.getChatMemoryProvider(
                                        standardAgentConfig.getChatMemoryConfig()))
                        .middleware(new LoggingIAgentMiddleware())
                        .middleware(streamingMiddleware)
                        .toolService(buildToolService());
        return builder.build();
    }

    private ToolService buildToolService() {
        ToolService toolService = new ToolService();
        toolService.tools(openReachTools);
        return toolService;
    }
}

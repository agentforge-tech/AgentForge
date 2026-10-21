package cloud.changlu.agentforge.service.modules.agent.runtime.mode.react;

import cloud.changlu.agentforge.agent.ReActAgent;
import cloud.changlu.agentforge.agent.extend.middlewares.LoggingIAgentMiddleware;
import cloud.changlu.agentforge.agent.memory.MessageWindowChatMemoryProvider;
import cloud.changlu.agentforge.model.registry.LlmFactory;
import cloud.changlu.agentforge.model.registry.config.LlmBasicConfig;
import cloud.changlu.agentforge.model.tool.execution.ToolService;
import cloud.changlu.agentforge.service.modules.agent.domain.config.AgentModelConfig;
import cloud.changlu.agentforge.service.modules.agent.domain.config.StandardAgentConfig;
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

    public ReActAgentFactory(
            ModelConfigMapper modelConfigMapper,
            OpenReachTools openReachTools,
            ServiceStreamingMiddleware streamingMiddleware) {
        this.modelConfigMapper = modelConfigMapper;
        this.openReachTools = openReachTools;
        this.streamingMiddleware = streamingMiddleware;
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
        LlmBasicConfig llmConfig = ModelConfigTransfer.toBasicConfig(modelConfig);
        ReActAgent.ReActAgentBuilder builder =
                ReActAgent.builder()
                        .agentName("StandardReActAgent")
                        .systemPrompt(standardAgentConfig.getSystemPrompt())
                        .chatModel(LlmFactory.buildChatModel(llmConfig))
                        .streamingChatModel(LlmFactory.buildStreamChatModel(llmConfig))
                        .chatMemoryProvider(new MessageWindowChatMemoryProvider(maxMessages))
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

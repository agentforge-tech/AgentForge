package cloud.changlu.agentforge.service.modules.agent.runtime.mode.react;

import cloud.changlu.agentforge.agent.ReActAgent;
import cloud.changlu.agentforge.agent.extend.middlewares.LoggingIAgentMiddleware;
import cloud.changlu.agentforge.model.registry.LlmFactory;
import cloud.changlu.agentforge.model.registry.config.LlmBasicConfig;
import cloud.changlu.agentforge.model.registry.constant.ChatModelProperties;
import cloud.changlu.agentforge.model.tool.ToolExecutor;
import cloud.changlu.agentforge.model.tool.execution.ToolService;
import cloud.changlu.agentforge.model.tool.spec.ToolSpecification;
import cloud.changlu.agentforge.service.modules.agent.domain.config.AgentModelConfig;
import cloud.changlu.agentforge.service.modules.agent.domain.config.PluginConfig;
import cloud.changlu.agentforge.service.modules.agent.domain.config.StandardAgentConfig;
import cloud.changlu.agentforge.service.modules.agent.middleware.ServiceStreamingMiddleware;
import cloud.changlu.agentforge.service.modules.agent.middleware.SessionLifecycleMiddleware;
import cloud.changlu.agentforge.service.modules.agent.runtime.mode.react.memory.factory.ChatMemoryProviderFactory;
import cloud.changlu.agentforge.service.modules.model.dao.ModelConfigMapper;
import cloud.changlu.agentforge.service.modules.model.domain.ModelConfigPojo;
import cloud.changlu.agentforge.service.modules.model.domain.transfer.ModelConfigTransfer;
import cloud.changlu.agentforge.service.modules.plugins.http.component.agent.HttpAgentToolLoader;
import cloud.changlu.agentforge.service.modules.plugins.mcp.component.agent.McpAgentToolLoader;
import cloud.changlu.agentforge.service.modules.trace.middleware.AgentForgeTraceMiddleware;
import cloud.changlu.agentforge.service.plugins.openreach.OpenReachTools;

import org.springframework.stereotype.Component;

import java.util.Map;

/** Standard ReAct Agent 工厂，负责装配模型、记忆、工具及默认中间件。 */
@Component
public class ReActAgentFactory {

    private final ModelConfigMapper modelConfigMapper;
    private final OpenReachTools openReachTools;
    private final ServiceStreamingMiddleware streamingMiddleware;
    private final SessionLifecycleMiddleware sessionLifecycleMiddleware;
    private final ChatMemoryProviderFactory chatMemoryProviderFactory;
    private final McpAgentToolLoader mcpAgentToolLoader;
    private final HttpAgentToolLoader httpAgentToolLoader;
    private final AgentForgeTraceMiddleware agentTraceMiddleware;

    public ReActAgentFactory(
            ModelConfigMapper modelConfigMapper,
            OpenReachTools openReachTools,
            ServiceStreamingMiddleware streamingMiddleware,
            SessionLifecycleMiddleware sessionLifecycleMiddleware,
            ChatMemoryProviderFactory chatMemoryProviderFactory,
            McpAgentToolLoader mcpAgentToolLoader,
            HttpAgentToolLoader httpAgentToolLoader,
            AgentForgeTraceMiddleware agentTraceMiddleware) {
        this.modelConfigMapper = modelConfigMapper;
        this.openReachTools = openReachTools;
        this.streamingMiddleware = streamingMiddleware;
        this.sessionLifecycleMiddleware = sessionLifecycleMiddleware;
        this.chatMemoryProviderFactory = chatMemoryProviderFactory;
        this.mcpAgentToolLoader = mcpAgentToolLoader;
        this.httpAgentToolLoader = httpAgentToolLoader;
        this.agentTraceMiddleware = agentTraceMiddleware;
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
                        .middleware(sessionLifecycleMiddleware)
                        .middleware(streamingMiddleware)
                        .middleware(agentTraceMiddleware)
                        .toolService(buildToolService(standardAgentConfig));
        return builder.build();
    }

    private ToolService buildToolService(StandardAgentConfig standardAgentConfig) {
        ToolService toolService = new ToolService();
        // 本地内置插件工具
        //        toolService.tools(
        //                LocalToolFactory.buildLocalToolsWithThought(
        //                        Collections.<Object>singletonList(openReachTools), true));
        PluginConfig pluginConfig = standardAgentConfig.getPluginConfig();
        // HTTP 插件工具加载
        Map<ToolSpecification, ToolExecutor> httpTools =
                httpAgentToolLoader.loadWithThought(
                        pluginConfig == null ? null : pluginConfig.getHttpPlugins(), true);
        if (!httpTools.isEmpty()) {
            toolService.tools(httpTools);
        }
        // MCP 工具按 Server 粒度降级，单个 Server 失败不阻断 Agent 构建。
        Map<ToolSpecification, ToolExecutor> mcpTools =
                mcpAgentToolLoader.loadWithThought(
                        pluginConfig == null ? null : pluginConfig.getMcpPlugins(), true);
        if (!mcpTools.isEmpty()) {
            toolService.tools(mcpTools);
        }
        return toolService;
    }
}

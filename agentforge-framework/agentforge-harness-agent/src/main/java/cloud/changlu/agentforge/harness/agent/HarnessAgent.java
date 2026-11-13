package cloud.changlu.agentforge.harness.agent;

import cloud.changlu.agentforge.agent.IAgent;
import cloud.changlu.agentforge.agent.ReActAgent;
import cloud.changlu.agentforge.agent.component.middleware.IAgentMiddleware;
import cloud.changlu.agentforge.agent.component.state.domain.AgentExecutionState;
import cloud.changlu.agentforge.agent.domain.AgentRequest;
import cloud.changlu.agentforge.agent.domain.AgentSettings;
import cloud.changlu.agentforge.agent.memory.ChatMemoryProvider;
import cloud.changlu.agentforge.agent.step.ChatResult;
import cloud.changlu.agentforge.agent.stream.TokenStream;
import cloud.changlu.agentforge.agent.tool.local.LocalToolFactory;
import cloud.changlu.agentforge.harness.runtime.Runtime;
import cloud.changlu.agentforge.harness.runtime.RuntimeOptions;
import cloud.changlu.agentforge.harness.runtime.RuntimeTools;
import cloud.changlu.agentforge.harness.runtime.RuntimeType;
import cloud.changlu.agentforge.harness.runtime.local.workspace.Workspace;
import cloud.changlu.agentforge.model.chat.ChatModel;
import cloud.changlu.agentforge.model.chat.StreamingChatModel;
import cloud.changlu.agentforge.model.tool.ToolExecutor;
import cloud.changlu.agentforge.model.tool.execution.ToolService;
import cloud.changlu.agentforge.model.tool.spec.ToolSpecification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runtime-aware Agent facade built on top of the core {@link ReActAgent}.
 *
 * <p>A HarnessAgent keeps the public {@link IAgent} contract unchanged while adding three pieces of
 * execution context supplied by a required {@link Runtime}. It delegates reasoning, memory,
 * streaming, cancellation and execution state management to ReActAgent, and contributes the tools
 * supplied by that runtime.
 *
 * <p>The Agent intentionally contains no runtime-type factory or switch. Docker, E2B, remote
 * container or other isolated environments can be added as Runtime implementations and loaded via
 * the Builder without changing HarnessAgent.
 *
 * <p>Each instance is bound to exactly one Workspace. Runtime tools are responsible for enforcing
 * that boundary and for releasing commands, sessions and other runtime resources when this Agent is
 * closed.
 */
public final class HarnessAgent implements IAgent, AutoCloseable {

    private final Runtime runtime;
    private final ReActAgent delegate;
    private volatile boolean closed;

    private HarnessAgent(Builder builder) {
        if (builder.runtime == null) {
            throw new IllegalArgumentException("runtime must not be null");
        }
        if (builder.chatModel == null && builder.streamingChatModel == null) {
            throw new IllegalArgumentException("chatModel or streamingChatModel must be provided");
        }
        if (builder.chatMemoryProvider == null) {
            throw new IllegalArgumentException("chatMemoryProvider must not be null");
        }
        this.runtime = builder.runtime;

        ToolService toolService = new ToolService();
        toolService.tools(LocalToolFactory.buildLocalTools(runtime.tools().toolObjects()));
        for (Object tool : builder.additionalTools) {
            toolService.tools(
                    LocalToolFactory.buildLocalTools(Collections.<Object>singletonList(tool)));
        }
        for (Map<ToolSpecification, ToolExecutor> tools : builder.additionalToolMaps) {
            toolService.tools(tools);
        }

        ReActAgent.ReActAgentBuilder delegateBuilder =
                ReActAgent.builder()
                        .agentName(builder.agentName == null ? "HarnessAgent" : builder.agentName)
                        .description(builder.description)
                        .systemPrompt(composeSystemPrompt(builder.systemPrompt, runtime))
                        .chatModel(builder.chatModel)
                        .streamingChatModel(builder.streamingChatModel)
                        .chatMemoryProvider(builder.chatMemoryProvider)
                        .toolService(toolService)
                        .agentSettings(
                                builder.agentSettings == null
                                        ? AgentSettings.defaultSettings()
                                        : builder.agentSettings)
                        .middlewares(builder.middlewares);
        this.delegate = delegateBuilder.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Runtime runtime() {
        return runtime;
    }

    public Workspace workspace() {
        return runtime.workspace();
    }

    public RuntimeType runtimeType() {
        return runtime.type();
    }

    public RuntimeOptions runtimeOptions() {
        return runtime.options();
    }

    public RuntimeTools runtimeTools() {
        return runtime.tools();
    }

    public String systemPrompt() {
        return delegate.getSystemPrompt();
    }

    @Override
    public ChatResult run(AgentRequest request) {
        ensureOpen();
        return delegate.run(request);
    }

    @Override
    public TokenStream runStream(AgentRequest request) {
        ensureOpen();
        return delegate.runStream(request);
    }

    @Override
    public boolean cancel(Object memoryId, String executionId) {
        boolean cancelled = delegate.cancel(memoryId, executionId);
        if (cancelled) runtime.cancelAll();
        return cancelled;
    }

    @Override
    public String createExecutionId() {
        return delegate.createExecutionId();
    }

    @Override
    public Optional<AgentExecutionState> getExecutionState(Object memoryId, String executionId) {
        return delegate.getExecutionState(memoryId, executionId);
    }

    @Override
    public boolean clearContext(Object memoryId) {
        return delegate.clearContext(memoryId);
    }

    @Override
    public void close() {
        closed = true;
        runtime.close();
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("HarnessAgent is closed");
    }

    private static String composeSystemPrompt(String userPrompt, Runtime runtime) {
        return userPrompt == null || userPrompt.trim().isEmpty()
                ? runtime.defaultSystemPrompt()
                : userPrompt;
    }

    public static final class Builder {
        private Runtime runtime;
        private String agentName;
        private String description;
        private String systemPrompt;
        private ChatModel chatModel;
        private StreamingChatModel streamingChatModel;
        private ChatMemoryProvider chatMemoryProvider;
        private AgentSettings agentSettings;
        private final List<IAgentMiddleware> middlewares = new ArrayList<IAgentMiddleware>();
        private final List<Object> additionalTools = new ArrayList<Object>();
        private final List<Map<ToolSpecification, ToolExecutor>> additionalToolMaps =
                new ArrayList<Map<ToolSpecification, ToolExecutor>>();

        private Builder() {}

        public Builder runtime(Runtime runtime) {
            this.runtime = runtime;
            return this;
        }

        public Builder agentName(String value) {
            this.agentName = value;
            return this;
        }

        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder systemPrompt(String value) {
            this.systemPrompt = value;
            return this;
        }

        public Builder chatModel(ChatModel value) {
            this.chatModel = value;
            return this;
        }

        public Builder streamingChatModel(StreamingChatModel value) {
            this.streamingChatModel = value;
            return this;
        }

        public Builder chatMemoryProvider(ChatMemoryProvider value) {
            this.chatMemoryProvider = value;
            return this;
        }

        public Builder agentSettings(AgentSettings value) {
            this.agentSettings = value;
            return this;
        }

        public Builder middleware(IAgentMiddleware middleware) {
            if (middleware != null) middlewares.add(middleware);
            return this;
        }

        public Builder middlewares(Collection<IAgentMiddleware> values) {
            if (values != null) middlewares.addAll(values);
            return this;
        }

        public Builder additionalTool(Object tool) {
            if (tool != null) additionalTools.add(tool);
            return this;
        }

        public Builder additionalTools(Map<ToolSpecification, ToolExecutor> tools) {
            if (tools != null) additionalToolMaps.add(tools);
            return this;
        }

        public HarnessAgent build() {
            return new HarnessAgent(this);
        }
    }
}

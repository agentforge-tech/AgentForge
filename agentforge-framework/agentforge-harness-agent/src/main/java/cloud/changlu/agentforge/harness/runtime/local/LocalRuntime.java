package cloud.changlu.agentforge.harness.runtime.local;

import cloud.changlu.agentforge.harness.runtime.Runtime;
import cloud.changlu.agentforge.harness.runtime.RuntimeOptions;
import cloud.changlu.agentforge.harness.runtime.RuntimeTools;
import cloud.changlu.agentforge.harness.runtime.RuntimeType;
import cloud.changlu.agentforge.harness.runtime.local.config.LocalRuntimeOptions;
import cloud.changlu.agentforge.harness.runtime.local.tool.LocalRuntimeTools;
import cloud.changlu.agentforge.harness.runtime.local.workspace.Workspace;
import cloud.changlu.agentforge.harness.runtime.prompt.PromptTemplateRenderer;

import java.util.HashMap;
import java.util.Map;

/** Local host runtime assembled through its own builder. */
public final class LocalRuntime implements Runtime {

    private static final String DEFAULT_SYSTEM_PROMPT_TEMPLATE = "local-runtime-system-prompt.txt";

    private final Workspace workspace;
    private final LocalRuntimeOptions options;
    private final LocalRuntimeTools tools;
    private final String defaultSystemPrompt;

    private LocalRuntime(Builder builder) {
        if (builder.workspace == null) {
            throw new IllegalArgumentException("workspace must not be null");
        }
        this.workspace = builder.workspace;
        this.options = builder.options == null ? LocalRuntimeOptions.defaults() : builder.options;
        this.defaultSystemPrompt = renderDefaultSystemPrompt(workspace);
        this.tools = new LocalRuntimeTools(workspace, options);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public RuntimeType type() {
        return RuntimeType.LOCAL;
    }

    @Override
    public RuntimeOptions options() {
        return options;
    }

    @Override
    public Workspace workspace() {
        return workspace;
    }

    @Override
    public RuntimeTools tools() {
        return tools;
    }

    @Override
    public String defaultSystemPrompt() {
        return defaultSystemPrompt;
    }

    private static String renderDefaultSystemPrompt(Workspace workspace) {
        Map<String, Object> variables = new HashMap<String, Object>();
        variables.put("workspaceId", workspace.id());
        variables.put("workspaceRoot", workspace.root());
        return PromptTemplateRenderer.renderResource(
                LocalRuntime.class, DEFAULT_SYSTEM_PROMPT_TEMPLATE, variables);
    }

    @Override
    public void cancelAll() {
        tools.cancelAll();
    }

    @Override
    public void close() {
        tools.close();
    }

    public static final class Builder {
        private Workspace workspace;
        private LocalRuntimeOptions options;

        private Builder() {}

        public Builder workspace(Workspace workspace) {
            this.workspace = workspace;
            return this;
        }

        public Builder options(LocalRuntimeOptions options) {
            this.options = options;
            return this;
        }

        public LocalRuntime build() {
            return new LocalRuntime(this);
        }
    }
}

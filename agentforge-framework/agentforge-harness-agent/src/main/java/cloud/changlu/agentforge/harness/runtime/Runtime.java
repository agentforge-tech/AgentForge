package cloud.changlu.agentforge.harness.runtime;

import cloud.changlu.agentforge.harness.runtime.local.workspace.Workspace;

/**
 * Complete execution environment consumed by a HarnessAgent.
 *
 * <p>A runtime owns its workspace semantics, configuration, tools, model-facing instructions and
 * resources. HarnessAgent deliberately does not branch on runtime type.
 */
public interface Runtime extends AutoCloseable {

    RuntimeType type();

    RuntimeOptions options();

    Workspace workspace();

    RuntimeTools tools();

    /** Complete Runtime-specific HarnessAgent prompt used when the caller does not provide one. */
    String defaultSystemPrompt();

    /** Cancels all active work owned by this runtime instance. */
    void cancelAll();

    @Override
    void close();
}

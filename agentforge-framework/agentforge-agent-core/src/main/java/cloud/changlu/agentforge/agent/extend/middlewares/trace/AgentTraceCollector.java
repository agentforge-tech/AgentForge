package cloud.changlu.agentforge.agent.extend.middlewares.trace;

import cloud.changlu.agentforge.agent.extend.middlewares.trace.domain.AgentTraceSnapshot;

/** Receives immutable observations produced by an Agent run. */
public interface AgentTraceCollector {

    void onSnapshot(AgentTraceSnapshot snapshot);
}

package cloud.changlu.agentforge.agent.trace;

import cloud.changlu.agentforge.agent.trace.domain.AgentTraceSnapshot;

/** Receives immutable observations produced by an Agent run. */
public interface AgentTraceCollector {

    void onSnapshot(AgentTraceSnapshot snapshot);
}

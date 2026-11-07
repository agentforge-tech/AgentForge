package cloud.changlu.agentforge.agent.component.state.store;

import cloud.changlu.agentforge.agent.component.state.domain.AgentExecutionState;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 单 JVM 的 Agent 状态存储实现。 */
public final class InMemoryAgentExecutionStateStore implements AgentExecutionStateStore {
    private final Map<Object, Map<String, AgentExecutionState>> states =
            new ConcurrentHashMap<Object, Map<String, AgentExecutionState>>();

    @Override
    public void save(Object memoryId, AgentExecutionState state) {
        if (memoryId == null || state == null || state.getExecutionId() == null) return;
        Map<String, AgentExecutionState> byExecution = states.get(memoryId);
        if (byExecution == null) {
            Map<String, AgentExecutionState> created =
                    new ConcurrentHashMap<String, AgentExecutionState>();
            Map<String, AgentExecutionState> previous = states.putIfAbsent(memoryId, created);
            byExecution = previous == null ? created : previous;
        }
        byExecution.put(state.getExecutionId(), state);
    }

    @Override
    public Optional<AgentExecutionState> get(Object memoryId, String executionId) {
        Map<String, AgentExecutionState> byExecution = states.get(memoryId);
        return byExecution == null
                ? Optional.<AgentExecutionState>empty()
                : Optional.ofNullable(byExecution.get(executionId));
    }

    @Override
    public void delete(Object memoryId, String executionId) {
        Map<String, AgentExecutionState> byExecution = states.get(memoryId);
        if (byExecution == null) return;
        byExecution.remove(executionId);
        if (byExecution.isEmpty()) states.remove(memoryId, byExecution);
    }

    @Override
    public void deleteByMemoryId(Object memoryId) {
        states.remove(memoryId);
    }
}

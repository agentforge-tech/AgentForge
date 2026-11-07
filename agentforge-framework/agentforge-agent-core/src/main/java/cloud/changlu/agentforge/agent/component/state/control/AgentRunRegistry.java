package cloud.changlu.agentforge.agent.component.state.control;

import cloud.changlu.agentforge.agent.component.state.domain.AgentExecutionState;
import cloud.changlu.agentforge.agent.component.state.store.AgentExecutionStateStore;
import cloud.changlu.agentforge.agent.component.state.store.InMemoryAgentExecutionStateStore;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Agent 活跃执行注册表。 */
public final class AgentRunRegistry {
    private final Map<Object, AgentRunControl> active =
            new ConcurrentHashMap<Object, AgentRunControl>();
    private final AgentExecutionStateStore stateStore;

    public AgentRunRegistry() {
        this(new InMemoryAgentExecutionStateStore());
    }

    public AgentRunRegistry(AgentExecutionStateStore stateStore) {
        if (stateStore == null) throw new IllegalArgumentException("stateStore must not be null");
        this.stateStore = stateStore;
    }

    public AgentRunControl register(String executionId, Object memoryId) {
        AgentRunControl control = new AgentRunControl(executionId, memoryId);
        AgentRunControl previous = active.putIfAbsent(memoryId, control);
        if (previous != null && !previous.isTerminal()) {
            throw new IllegalStateException("memoryId 已存在活跃执行: " + memoryId);
        }
        if (previous != null) active.replace(memoryId, previous, control);
        stateStore.save(memoryId, control.snapshot());
        return control;
    }

    public AgentRunControl current(Object memoryId) {
        return active.get(memoryId);
    }

    public boolean isCurrent(Object memoryId, String executionId) {
        AgentRunControl control = active.get(memoryId);
        return control != null && control.getExecutionId().equals(executionId);
    }

    public Optional<AgentExecutionState> snapshot(Object memoryId, String executionId) {
        AgentRunControl control = active.get(memoryId);
        if (control != null && control.getExecutionId().equals(executionId)) {
            return Optional.of(control.snapshot());
        }
        return stateStore.get(memoryId, executionId);
    }

    public boolean cancel(Object memoryId, String executionId, String reason) {
        AgentRunControl control = active.get(memoryId);
        return control != null
                && control.getExecutionId().equals(executionId)
                && control.requestCancel(reason);
    }

    public void complete(AgentRunControl control) {
        stateStore.save(control.getMemoryId(), control.snapshot());
        active.remove(control.getMemoryId(), control);
    }

    public void clear(Object memoryId) {
        AgentRunControl control = active.remove(memoryId);
        if (control != null) control.requestCancel("会话上下文被清理");
        stateStore.deleteByMemoryId(memoryId);
    }
}

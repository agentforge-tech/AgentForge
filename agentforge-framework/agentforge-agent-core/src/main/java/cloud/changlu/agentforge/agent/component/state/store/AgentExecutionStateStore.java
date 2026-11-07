package cloud.changlu.agentforge.agent.component.state.store;

import cloud.changlu.agentforge.agent.component.state.domain.AgentExecutionState;

import java.util.Optional;

/** Agent 执行状态快照存储；键由 memoryId 与 executionId 共同组成。 */
public interface AgentExecutionStateStore {
    void save(Object memoryId, AgentExecutionState state);

    Optional<AgentExecutionState> get(Object memoryId, String executionId);

    void delete(Object memoryId, String executionId);

    void deleteByMemoryId(Object memoryId);
}

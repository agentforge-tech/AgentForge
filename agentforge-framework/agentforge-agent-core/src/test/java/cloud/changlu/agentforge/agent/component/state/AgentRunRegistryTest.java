package cloud.changlu.agentforge.agent.component.state;

import cloud.changlu.agentforge.agent.component.state.control.AgentRunControl;
import cloud.changlu.agentforge.agent.component.state.control.AgentRunRegistry;
import cloud.changlu.agentforge.agent.component.state.domain.AgentExecutionPhase;
import cloud.changlu.agentforge.agent.component.state.domain.AgentExecutionState;
import cloud.changlu.agentforge.agent.component.state.domain.AgentExecutionStatus;
import cloud.changlu.agentforge.agent.domain.AgentRunState;
import cloud.changlu.agentforge.model.cancel.CancellationHandle;

import org.junit.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentRunRegistryTest {

    @Test
    public void shouldStoreAndQueryByMemoryIdAndExecutionId() {
        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRunControl control = registry.register("exec-1", "session-1");
        control.modelCall(2);

        Optional<AgentExecutionState> state = registry.snapshot("session-1", "exec-1");

        assertTrue(state.isPresent());
        assertEquals("exec-1", state.get().getExecutionId());
        assertEquals(AgentExecutionStatus.RUNNING, state.get().getStatus());
        assertEquals(AgentExecutionPhase.MODEL_CALL, state.get().getPhase());
        assertEquals(2, state.get().getCurrentStep());
    }

    @Test
    public void shouldOnlyCancelMatchingExecutionAndInvokeActiveHandle() {
        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRunControl control = registry.register("exec-new", "session-1");
        final AtomicBoolean handleCancelled = new AtomicBoolean(false);
        control.registerModelHandle(
                new CancellationHandle() {
                    @Override
                    public boolean cancel() {
                        return handleCancelled.compareAndSet(false, true);
                    }

                    @Override
                    public boolean isCancelled() {
                        return handleCancelled.get();
                    }
                });

        assertFalse(registry.cancel("session-1", "exec-old", "late cancel"));
        assertFalse(handleCancelled.get());
        assertTrue(registry.cancel("session-1", "exec-new", "user cancel"));
        assertTrue(handleCancelled.get());
        assertEquals(
                AgentExecutionStatus.CANCEL_REQUESTED,
                registry.snapshot("session-1", "exec-new").get().getStatus());
    }

    @Test
    public void shouldKeepTerminalSnapshotInInMemoryStore() {
        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRunControl control = registry.register("exec-1", "session-1");
        control.finish(AgentExecutionStatus.COMPLETED, AgentRunState.SUCCESS, null);
        registry.complete(control);

        AgentExecutionState state = registry.snapshot("session-1", "exec-1").get();
        assertEquals(AgentExecutionStatus.COMPLETED, state.getStatus());
        assertEquals(AgentRunState.SUCCESS, state.getResultState());
    }
}

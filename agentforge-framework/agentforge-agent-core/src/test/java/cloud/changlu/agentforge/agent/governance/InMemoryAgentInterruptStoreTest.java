package cloud.changlu.agentforge.agent.governance;

import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentInterruptSnapshot;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptBatchStatus;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptRequest;
import cloud.changlu.agentforge.agent.governance.interrupt.support.InMemoryAgentInterruptStore;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class InMemoryAgentInterruptStoreTest {
    @Test
    public void shouldMoveBatchThroughStatusMachine() {
        InMemoryAgentInterruptStore store = new InMemoryAgentInterruptStore();
        store.save(snapshot("batch-1"), null);

        assertEquals(InterruptBatchStatus.PENDING, store.get("batch-1").getStatus());
        store.claim("batch-1");
        assertEquals(InterruptBatchStatus.RESUMING, store.get("batch-1").getStatus());

        store.complete("batch-1");
        assertEquals(InterruptBatchStatus.COMPLETED, store.get("batch-1").getStatus());
    }

    @Test(expected = IllegalStateException.class)
    public void shouldRejectSecondClaimWhileResuming() {
        InMemoryAgentInterruptStore store = new InMemoryAgentInterruptStore();
        store.save(snapshot("batch-1"), null);
        store.claim("batch-1");
        store.claim("batch-1");
    }

    @Test(expected = IllegalStateException.class)
    public void shouldRejectClaimAfterCompletion() {
        InMemoryAgentInterruptStore store = new InMemoryAgentInterruptStore();
        store.save(snapshot("batch-1"), null);
        store.claim("batch-1");
        store.complete("batch-1");
        store.claim("batch-1");
    }

    private static AgentInterruptSnapshot snapshot(String batchId) {
        ToolExecutionRequest tool =
                ToolExecutionRequest.builder()
                        .id("call-1")
                        .name("write_file")
                        .arguments("{}")
                        .build();
        return AgentInterruptSnapshot.initial(
                batchId,
                "memory-1",
                "exec-1",
                1,
                Collections.singletonList(tool),
                Collections.<InterruptRequest>emptyList());
    }
}

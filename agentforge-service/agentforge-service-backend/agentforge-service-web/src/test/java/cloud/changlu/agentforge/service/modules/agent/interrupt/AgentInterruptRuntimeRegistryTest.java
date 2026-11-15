package cloud.changlu.agentforge.service.modules.agent.interrupt;

import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentSuspendInfo;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptRequest;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptType;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

public class AgentInterruptRuntimeRegistryTest {

    @Test
    public void shouldClaimBatchOnlyOnce() {
        AgentInterruptRuntimeRegistry registry = new AgentInterruptRuntimeRegistry();
        registry.register("session-1", 11L, "agent-1", true, 7L, "execution-1", null, new Object());
        registry.suspend("session-1", new AgentSuspendInfo("batch-1", Collections.emptyList()));

        AgentInterruptRuntimeRegistry.BatchContext claimed = registry.claim("batch-1", 7L);

        assertEquals(AgentInterruptRuntimeRegistry.Status.RESUMING, claimed.getStatus());
        try {
            registry.claim("batch-1", 7L);
            fail("duplicate claim must fail");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test(expected = SecurityException.class)
    public void shouldRejectDifferentOwner() {
        AgentInterruptRuntimeRegistry registry = new AgentInterruptRuntimeRegistry();
        registry.register(
                "session-1", 11L, "agent-1", false, 7L, "execution-1", null, new Object());
        registry.suspend("session-1", new AgentSuspendInfo("batch-1", Collections.emptyList()));

        registry.requireOwned("batch-1", 8L);
    }

    @Test
    public void shouldCancelPendingBatchByMemoryId() {
        AgentInterruptRuntimeRegistry registry = new AgentInterruptRuntimeRegistry();
        registry.register(
                "session-1", 11L, "agent-1", false, 7L, "execution-1", null, new Object());
        registry.suspend("session-1", new AgentSuspendInfo("batch-1", Collections.emptyList()));

        AgentInterruptRuntimeRegistry.BatchContext cancelled =
                registry.cancelByMemoryId("session-1");

        assertNotNull(cancelled);
        assertEquals(AgentInterruptRuntimeRegistry.Status.CANCELLED, cancelled.getStatus());
    }

    @Test
    public void shouldSerializeToolRequestInInterruptEvent() throws Exception {
        AgentInterruptRuntimeRegistry registry = new AgentInterruptRuntimeRegistry();
        registry.register("session-1", 11L, "agent-1", true, 7L, "execution-1", null, new Object());
        InterruptRequest interrupt =
                new InterruptRequest(
                        "interrupt-1",
                        InterruptType.PERMISSION_APPROVAL,
                        ToolExecutionRequest.from(
                                "tool-call-1", "workspace_write", "{\"path\":\"README.md\"}"));
        AgentInterruptRuntimeRegistry.BatchContext batch =
                registry.suspend(
                        "session-1",
                        new AgentSuspendInfo("batch-1", Collections.singletonList(interrupt)));

        String json = new ObjectMapper().writeValueAsString(batch.eventContent());
        JsonNode root = new ObjectMapper().readTree(json);

        assertEquals("interrupt-1", root.at("/interrupts/0/interruptId").asText());
        assertEquals("workspace_write", root.at("/interrupts/0/toolRequest/name").asText());
        assertEquals(
                "{\"path\":\"README.md\"}",
                root.at("/interrupts/0/toolRequest/arguments").asText());
    }
}

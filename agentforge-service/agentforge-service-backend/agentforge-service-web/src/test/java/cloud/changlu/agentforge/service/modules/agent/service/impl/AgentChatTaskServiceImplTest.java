package cloud.changlu.agentforge.service.modules.agent.service.impl;

import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentInterruptSnapshot;
import cloud.changlu.agentforge.service.modules.agent.runtime.context.ServiceAgentExecutionContext;
import cloud.changlu.agentforge.service.modules.session.domain.AgentSessionPojo;

import org.junit.Test;

import java.util.Collections;

public class AgentChatTaskServiceImplTest {

    @Test
    public void shouldCompareSnapshotMemoryIdWithExternalSessionId() {
        AgentSessionPojo session = new AgentSessionPojo();
        session.setId(88L);
        session.setSessionId("session-public-id");
        AgentInterruptSnapshot snapshot =
                AgentInterruptSnapshot.initial(
                        "batch-1",
                        "session-public-id",
                        "execution-1",
                        1,
                        Collections.emptyList(),
                        Collections.emptyList());
        ServiceAgentExecutionContext executionContext =
                new ServiceAgentExecutionContext(
                        10001L, 9012L, "session-public-id", "agent-demo", false);

        AgentChatTaskServiceImpl.verifyInterruptSession(session, snapshot, executionContext);
    }

    @Test(expected = SecurityException.class)
    public void shouldRejectDifferentExternalSessionId() {
        AgentSessionPojo session = new AgentSessionPojo();
        session.setId(88L);
        session.setSessionId("another-session");
        AgentInterruptSnapshot snapshot =
                AgentInterruptSnapshot.initial(
                        "batch-1",
                        "session-public-id",
                        "execution-1",
                        1,
                        Collections.emptyList(),
                        Collections.emptyList());
        ServiceAgentExecutionContext executionContext =
                new ServiceAgentExecutionContext(
                        10001L, 9012L, "session-public-id", "agent-demo", false);

        AgentChatTaskServiceImpl.verifyInterruptSession(session, snapshot, executionContext);
    }
}

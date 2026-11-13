package cloud.changlu.agentforge.harness.agent;

import cloud.changlu.agentforge.agent.step.ChatResultState;
import cloud.changlu.agentforge.agent.step.StepState;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Verifies that the harness module can consume the public Agent Core contracts. */
public class HarnessAgentModuleTest {

    @Test
    public void exposesAgentCoreRuntimeStates() {
        assertEquals(StepState.RUNNING, StepState.valueOf("RUNNING"));
        assertEquals(ChatResultState.FINISHED, ChatResultState.valueOf("FINISHED"));
    }
}

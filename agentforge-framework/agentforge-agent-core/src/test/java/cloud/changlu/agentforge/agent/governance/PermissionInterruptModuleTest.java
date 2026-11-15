package cloud.changlu.agentforge.agent.governance;

import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.domain.AgentRequest;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentSuspendInfo;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptType;
import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionMode;
import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionPreflightResult;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PermissionInterruptModuleTest {

    @Test
    public void shouldAllowExplicitSafeToolWithoutInterrupt() {
        PermissionInterruptModule module =
                PermissionInterruptModule.safeDefaults().allow("read_.*", ".*").build();

        PermissionPreflightResult result =
                module.preflight(
                        1,
                        Arrays.asList(tool("call-1", "read_file", "{\"path\":\"README.md\"}")),
                        context());

        assertTrue(result.isEmpty());
    }

    @Test
    public void shouldCreateOneMixedBatchForApprovalAndAskUser() {
        PermissionInterruptModule module =
                PermissionInterruptModule.safeDefaults()
                        .enableAskUser()
                        .ask("write_.*", ".*")
                        .build();

        PermissionPreflightResult result =
                module.preflight(
                        2,
                        Arrays.asList(
                                tool("call-1", "write_file", "{\"path\":\"a.txt\"}"),
                                tool("call-2", "ask_user", "{\"question\":\"是否继续？\"}")),
                        context());

        assertTrue(result.isSuspended());
        AgentSuspendInfo suspendInfo = result.getSuspendInfo();
        assertEquals(2, suspendInfo.getInterrupts().size());
        assertEquals(
                InterruptType.PERMISSION_APPROVAL, suspendInfo.getInterrupts().get(0).getType());
        assertEquals(InterruptType.ASK_USER, suspendInfo.getInterrupts().get(1).getType());
        assertEquals(
                2,
                module.getInterruptStore().get(suspendInfo.getBatchId()).getToolRequests().size());
    }

    @Test
    public void shouldReturnDenialWhenDontAskModeDeniesUnknownTool() {
        PermissionInterruptModule module =
                PermissionInterruptModule.builder()
                        .activePermissionMode(PermissionMode.DONT_ASK)
                        .build();
        PermissionPreflightResult result =
                module.preflight(1, Arrays.asList(tool("call-1", "shell", "{}")), context());

        assertFalse(result.isSuspended());
        assertTrue(result.hasDenials());
        assertEquals(1, result.getDenials().size());
        assertTrue(result.getDenials().get(0).getReason().contains("denied"));
    }

    private static ToolExecutionRequest tool(String id, String name, String arguments) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build();
    }

    private static AgentChatContext context() {
        AgentRequest request = new AgentRequest();
        request.setMemoryId("memory-1");
        request.setExecutionId("exec-1");
        request.setQuestion("test");
        return AgentChatContext.builder().executionId("exec-1").request(request).build();
    }
}

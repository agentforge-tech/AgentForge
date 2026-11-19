package cloud.changlu.agentforge.agent.governance;

import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.domain.AgentRequest;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentSuspendInfo;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.ApprovalAction;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptBatchResumeCommand;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptRequest;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptResponse;
import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionPreflightResult;
import cloud.changlu.agentforge.agent.governance.permission.spi.PermissionApprovalStore;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PermissionApprovalStoreTest {

    @Test
    public void shouldKeepApproveAlwaysWithinSameMemoryId() {
        PermissionInterruptModule module =
                PermissionInterruptModule.safeDefaults().ask("shell", ".*").build();
        ToolExecutionRequest request = tool("call-1", "shell", "{\"command\":\"npm test\"}");
        AgentChatContext sessionA = context("memory-a", "exec-a");

        apply(module, request, sessionA, ApprovalAction.APPROVE_ALWAYS);

        PermissionPreflightResult sameSession =
                module.preflight(
                        2,
                        Collections.singletonList(
                                tool("call-2", "shell", "{\"command\":\"npm test\"}")),
                        context("memory-a", "exec-a2"));
        PermissionPreflightResult otherSession =
                module.preflight(
                        2,
                        Collections.singletonList(
                                tool("call-3", "shell", "{\"command\":\"npm test\"}")),
                        context("memory-b", "exec-b"));

        assertTrue(sameSession.isEmpty());
        assertTrue(otherSession.isSuspended());
    }

    @Test
    public void shouldNotPersistApproveOnceOrReject() {
        for (ApprovalAction action :
                Arrays.asList(ApprovalAction.APPROVE_ONCE, ApprovalAction.REJECT)) {
            PermissionInterruptModule module =
                    PermissionInterruptModule.safeDefaults().ask("shell", ".*").build();
            AgentChatContext context = context("memory-" + action.name(), "exec-1");
            apply(module, tool("call-1", "shell", "{\"command\":\"npm test\"}"), context, action);

            PermissionPreflightResult next =
                    module.preflight(
                            2,
                            Collections.singletonList(
                                    tool("call-2", "shell", "{\"command\":\"npm test\"}")),
                            context("memory-" + action.name(), "exec-2"));

            assertTrue(action.name(), next.isSuspended());
        }
    }

    @Test
    public void shouldPassContextAndAllApprovalActionsToCustomStore() {
        RecordingApprovalStore store = new RecordingApprovalStore();
        PermissionInterruptModule module =
                PermissionInterruptModule.safeDefaults()
                        .ask("shell", ".*")
                        .permissionApprovalStore(store)
                        .build();
        List<AgentChatContext> contexts = new ArrayList<AgentChatContext>();

        int index = 0;
        for (ApprovalAction action : ApprovalAction.values()) {
            AgentChatContext context =
                    context("memory-custom", "exec-" + index)
                            .putExtension("approval-index", Integer.valueOf(index));
            contexts.add(context);
            apply(
                    module,
                    tool("call-" + index, "shell", "{\"command\":\"command-" + index + "\"}"),
                    context,
                    action);
            index++;
        }

        assertEquals(Arrays.asList(ApprovalAction.values()), store.actions);
        assertEquals(ApprovalAction.values().length, store.approvalContexts.size());
        for (int i = 0; i < contexts.size(); i++) {
            assertSame(contexts.get(i), store.approvalContexts.get(i));
            assertEquals(
                    Integer.valueOf(i),
                    store.approvalContexts.get(i).getExtension("approval-index"));
        }
        assertFalse(store.preflightContexts.isEmpty());
        assertSame(contexts.get(0), store.preflightContexts.get(0));
    }

    private static void apply(
            PermissionInterruptModule module,
            ToolExecutionRequest request,
            AgentChatContext context,
            ApprovalAction action) {
        PermissionPreflightResult preflight =
                module.preflight(1, Collections.singletonList(request), context);
        assertTrue(preflight.isSuspended());
        AgentSuspendInfo suspendInfo = preflight.getSuspendInfo();
        InterruptRequest interrupt = suspendInfo.getInterrupts().get(0);
        PermissionInterruptModule.ResolvedInterruptBatch resolved =
                module.resolve(
                        new InterruptBatchResumeCommand(
                                suspendInfo.getBatchId(),
                                Collections.singletonList(
                                        new InterruptResponse(
                                                interrupt.getInterruptId(), action, null))));
        module.applyApprovals(resolved, context);
        module.complete(resolved);
    }

    private static ToolExecutionRequest tool(String id, String name, String arguments) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build();
    }

    private static AgentChatContext context(String memoryId, String executionId) {
        AgentRequest request = new AgentRequest();
        request.setMemoryId(memoryId);
        request.setExecutionId(executionId);
        request.setQuestion("test");
        return AgentChatContext.builder().executionId(executionId).request(request).build();
    }

    private static final class RecordingApprovalStore implements PermissionApprovalStore {
        private final List<AgentChatContext> preflightContexts = new ArrayList<AgentChatContext>();
        private final List<AgentChatContext> approvalContexts = new ArrayList<AgentChatContext>();
        private final List<ApprovalAction> actions = new ArrayList<ApprovalAction>();

        @Override
        public boolean isApproved(AgentChatContext context, ToolExecutionRequest request) {
            preflightContexts.add(context);
            return false;
        }

        @Override
        public void addApproval(
                AgentChatContext context, ToolExecutionRequest request, ApprovalAction action) {
            approvalContexts.add(context);
            actions.add(action);
        }
    }
}

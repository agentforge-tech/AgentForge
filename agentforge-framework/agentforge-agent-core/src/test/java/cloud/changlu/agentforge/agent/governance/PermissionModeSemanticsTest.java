package cloud.changlu.agentforge.agent.governance;

import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.domain.AgentRequest;
import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionDecision;
import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionMode;
import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionPreflightResult;
import cloud.changlu.agentforge.agent.governance.permission.engine.PermissionContext;
import cloud.changlu.agentforge.agent.governance.permission.spi.PermissionModePolicy;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 验证 Mode 与规则搭配后的对称语义。 */
public class PermissionModeSemanticsTest {

    @Test
    public void shouldAllowUnmatchedToolUnderDefaultMode() {
        PermissionPreflightResult result =
                preflight(PermissionInterruptModule.safeDefaults().build());

        assertTrue(result.isEmpty());
    }

    @Test
    public void shouldInterruptWhenAskRuleMatchesUnderDefaultMode() {
        PermissionPreflightResult result =
                preflight(PermissionInterruptModule.safeDefaults().ask("write_.*", ".*").build());

        assertTrue(result.isSuspended());
        assertFalse(result.hasDenials());
    }

    @Test
    public void shouldAllowExplicitRuleUnderDefaultMode() {
        PermissionPreflightResult result =
                preflight(PermissionInterruptModule.safeDefaults().allow("write_.*", ".*").build());

        assertTrue(result.isEmpty());
    }

    @Test
    public void shouldAllowUnmatchedToolUnderBypassMode() {
        PermissionPreflightResult result =
                preflight(
                        PermissionInterruptModule.builder()
                                .activePermissionMode(PermissionMode.BYPASS)
                                .build());

        assertTrue(result.isEmpty());
    }

    @Test
    public void shouldTurnExplicitAskRuleIntoAllowUnderBypassMode() {
        PermissionPreflightResult result =
                preflight(
                        PermissionInterruptModule.builder()
                                .activePermissionMode(PermissionMode.BYPASS)
                                .ask("write_.*", ".*")
                                .build());

        assertTrue(result.isEmpty());
    }

    @Test
    public void shouldKeepExplicitDenyUnderBypassMode() {
        PermissionPreflightResult result =
                preflight(
                        PermissionInterruptModule.builder()
                                .activePermissionMode(PermissionMode.BYPASS)
                                .deny("write_.*", ".*")
                                .build());

        assertFalse(result.isSuspended());
        assertTrue(result.hasDenials());
    }

    @Test
    public void shouldDenyUnmatchedToolUnderDontAskMode() {
        PermissionPreflightResult result =
                preflight(
                        PermissionInterruptModule.builder()
                                .activePermissionMode(PermissionMode.DONT_ASK)
                                .build());

        assertFalse(result.isSuspended());
        assertTrue(result.hasDenials());
    }

    @Test
    public void shouldTurnExplicitAskRuleIntoDenyUnderDontAskMode() {
        PermissionPreflightResult result =
                preflight(
                        PermissionInterruptModule.builder()
                                .activePermissionMode(PermissionMode.DONT_ASK)
                                .ask("write_.*", ".*")
                                .build());

        assertFalse(result.isSuspended());
        assertTrue(result.hasDenials());
    }

    @Test
    public void shouldKeepExplicitAllowUnderDontAskMode() {
        PermissionPreflightResult result =
                preflight(
                        PermissionInterruptModule.builder()
                                .activePermissionMode(PermissionMode.DONT_ASK)
                                .allow("write_.*", ".*")
                                .build());

        assertTrue(result.isEmpty());
    }

    @Test
    public void shouldDenyAskUserUnderDontAskMode() {
        PermissionInterruptModule module =
                PermissionInterruptModule.builder()
                        .activePermissionMode(PermissionMode.DONT_ASK)
                        .enableAskUser()
                        .build();
        PermissionPreflightResult result =
                module.preflight(
                        1,
                        Collections.singletonList(
                                ToolExecutionRequest.from(
                                        "call-1", "ask_user", "{\"question\":\"是否继续？\"}")),
                        context());

        assertFalse(result.isSuspended());
        assertTrue(result.hasDenials());
        assertTrue(result.getDenials().get(0).getReason().contains("DONT_ASK"));
    }

    @Test
    public void shouldPassRuleCandidateToCustomModePolicy() {
        PermissionModePolicy policy =
                new PermissionModePolicy() {
                    @Override
                    public PermissionDecision decide(PermissionContext context) {
                        return PermissionDecision.ASK;
                    }

                    @Override
                    public PermissionDecision decide(
                            PermissionContext context, PermissionDecision candidate) {
                        return candidate == PermissionDecision.ASK
                                ? PermissionDecision.ALLOW
                                : candidate;
                    }
                };
        PermissionPreflightResult result =
                preflight(
                        PermissionInterruptModule.safeDefaults()
                                .ask("write_.*", ".*")
                                .permissionModePolicy(policy)
                                .build());

        assertTrue(result.isEmpty());
    }

    private static PermissionPreflightResult preflight(PermissionInterruptModule module) {
        return module.preflight(
                1,
                Collections.singletonList(ToolExecutionRequest.from("call-1", "write_file", "{}")),
                context());
    }

    private static AgentChatContext context() {
        AgentRequest request = new AgentRequest();
        request.setMemoryId("memory-1");
        request.setExecutionId("exec-1");
        request.setQuestion("test");
        return AgentChatContext.builder().executionId("exec-1").request(request).build();
    }
}

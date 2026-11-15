package cloud.changlu.agentforge.agent.governance;

import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.domain.AgentRequest;
import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionDecision;
import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionPreflightResult;
import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionRule;
import cloud.changlu.agentforge.agent.governance.permission.engine.PermissionContext;
import cloud.changlu.agentforge.agent.governance.permission.spi.PermissionRuleHandler;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 验证内置 regex 之外的自定义规则类型与处理器扩展。 */
public class PermissionRuleExtensionTest {

    private static final String PREFIX_TYPE = "tool_prefix";

    @Test
    public void shouldUseCustomRuleHandlerBeforeModeFallback() {
        PermissionInterruptModule module =
                PermissionInterruptModule.safeDefaults()
                        .ruleHandler(new PrefixRuleHandler())
                        .rule(
                                new PrefixRule(
                                        "rule-prefix",
                                        PREFIX_TYPE,
                                        "deny_",
                                        PermissionDecision.DENY))
                        .allow(".*", ".*")
                        .build();

        PermissionPreflightResult denied =
                module.preflight(1, Collections.singletonList(tool("deny_shell", "{}")), context());
        assertFalse(denied.isSuspended());
        assertTrue(denied.hasDenials());
        assertTrue(denied.getDenials().get(0).getReason().contains("deny_shell"));

        PermissionPreflightResult allowed =
                module.preflight(1, Collections.singletonList(tool("read_file", "{}")), context());
        assertTrue(allowed.isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldFailClosedWhenRuleTypeHasNoHandler() {
        PermissionInterruptModule.safeDefaults()
                .rule(new PrefixRule("rule-unknown", "unknown_type", "x", PermissionDecision.ALLOW))
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectDuplicateRuleHandlerType() {
        PermissionInterruptModule.safeDefaults()
                .ruleHandler(new PrefixRuleHandler())
                .ruleHandler(new PrefixRuleHandler())
                .build();
    }

    private static ToolExecutionRequest tool(String name, String arguments) {
        return ToolExecutionRequest.builder().id("call-1").name(name).arguments(arguments).build();
    }

    private static AgentChatContext context() {
        AgentRequest request = new AgentRequest();
        request.setMemoryId("memory-1");
        request.setExecutionId("exec-1");
        request.setQuestion("test");
        return AgentChatContext.builder().executionId("exec-1").request(request).build();
    }

    private static final class PrefixRule implements PermissionRule {
        private final String id;
        private final String type;
        private final String prefix;
        private final PermissionDecision decision;

        private PrefixRule(String id, String type, String prefix, PermissionDecision decision) {
            this.id = id;
            this.type = type;
            this.prefix = prefix;
            this.decision = decision;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getType() {
            return type;
        }

        @Override
        public PermissionDecision getDecision() {
            return decision;
        }

        private String getPrefix() {
            return prefix;
        }
    }

    private static final class PrefixRuleHandler implements PermissionRuleHandler {
        @Override
        public String type() {
            return PREFIX_TYPE;
        }

        @Override
        public boolean matches(PermissionRule rule, PermissionContext context) {
            if (!(rule instanceof PrefixRule)) return false;
            String prefix = ((PrefixRule) rule).getPrefix();
            return context.getRequest().name().startsWith(prefix);
        }
    }
}

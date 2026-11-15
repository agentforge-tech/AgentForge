package cloud.changlu.agentforge.agent.governance.permission.support;

import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionDecision;
import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionMode;
import cloud.changlu.agentforge.agent.governance.permission.engine.PermissionContext;
import cloud.changlu.agentforge.agent.governance.permission.spi.PermissionModePolicy;

/**
 * 内置 {@link PermissionMode} 的策略适配器。
 *
 * <p>让内置 Mode 和自定义 ModePolicy 走同一个 SPI：未注入自定义策略时，由本适配器把当前 Mode 的 内置语义转换成最终决策。
 */
public final class BuiltinPermissionModePolicy implements PermissionModePolicy {

    public static final BuiltinPermissionModePolicy INSTANCE = new BuiltinPermissionModePolicy();

    private BuiltinPermissionModePolicy() {}

    @Override
    public PermissionDecision decide(PermissionContext context) {
        return decide(context, null);
    }

    @Override
    public PermissionDecision decide(PermissionContext context, PermissionDecision candidate) {
        PermissionMode mode = mode(context);
        if (mode == PermissionMode.BYPASS) {
            return candidate == PermissionDecision.DENY
                    ? PermissionDecision.DENY
                    : PermissionDecision.ALLOW;
        }
        if (mode == PermissionMode.DONT_ASK) {
            return candidate == PermissionDecision.ALLOW
                    ? PermissionDecision.ALLOW
                    : PermissionDecision.DENY;
        }
        return candidate == null ? PermissionDecision.ASK : candidate;
    }

    private PermissionMode mode(PermissionContext context) {
        return context == null || context.getMode() == null
                ? PermissionMode.DEFAULT
                : context.getMode();
    }
}

package cloud.changlu.agentforge.agent.governance.permission.spi;

import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionDecision;
import cloud.changlu.agentforge.agent.governance.permission.engine.PermissionContext;

/** 深度定制模式的扩展点。 */
public interface PermissionModePolicy {

    /** 无规则命中时的模式决策；保留给已有实现。 */
    PermissionDecision decide(PermissionContext context);

    /**
     * 规则阶段产出候选决策后，由 ModePolicy 结合候选给出最终决策。
     *
     * <p>默认实现保持“规则优先”：有候选决策时原样返回，无命中时才调用 {@link #decide(PermissionContext)}。 需要把 Mode
     * 与规则搭配使用的自定义策略可覆盖本方法。
     *
     * @param context 权限评估上下文
     * @param candidate 规则命中产生的候选决策；无规则命中时为 null
     * @return 最终权限决策
     */
    default PermissionDecision decide(PermissionContext context, PermissionDecision candidate) {
        return candidate == null ? decide(context) : candidate;
    }
}

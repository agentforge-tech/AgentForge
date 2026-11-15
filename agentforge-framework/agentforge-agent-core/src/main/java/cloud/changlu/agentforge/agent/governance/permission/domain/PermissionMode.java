package cloud.changlu.agentforge.agent.governance.permission.domain;

/**
 * 内置权限运行模式：只决定“规则结果如何落地”，不参与规则匹配。
 *
 * <p>判定顺序固定为：{@code PermissionRule + PermissionRuleHandler} 先产出候选决策；命中规则时候选非空， 未命中时候选为空。再由 {@code
 * BuiltinPermissionModePolicy} 按模式变换候选：
 *
 * <ul>
 *   <li>{@link #DEFAULT}：规则驱动。命中规则时按规则决策执行；未命中时直接放行。
 *   <li>{@link #BYPASS}：只保留显式 DENY。命中 ASK/ALLOW 或未命中时统一放行。
 *   <li>{@link #DONT_ASK}：只保留显式 ALLOW。命中 ASK/DENY 或未命中时统一拒绝，不产生中断。
 * </ul>
 *
 * <p>三个模式是“规则为准 / 仅 DENY 拦截 / 仅 ALLOW 放行”的对称策略，而不是同一个兜底值。 配置自定义 {@code PermissionModePolicy}
 * 时，由策略通过 {@code decide(context, candidate)} 接管最终决策；未配置时使用 {@code BuiltinPermissionModePolicy}
 * 的内置语义。
 */
public enum PermissionMode {

    /** 规则驱动：命中规则按其决策执行；未命中规则时直接放行。用于需要规则精细控制审核范围的交互场景。 */
    DEFAULT("仅按规则判断，未命中规则直接放行"),

    /** 仅保留显式 DENY：命中 ASK/ALLOW 或未命中规则时统一放行，不产生权限审核中断。用于可信沙箱。 */
    BYPASS("仅显式 DENY 拦截，其余放行"),

    /** 仅保留显式 ALLOW：命中 ASK/DENY 或未命中规则时统一拒绝，不产生任何中断。用于无人值守任务。 */
    DONT_ASK("仅显式 ALLOW 放行，其余拒绝");

    private final String description;

    PermissionMode(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}

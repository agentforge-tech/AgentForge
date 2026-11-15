package cloud.changlu.agentforge.agent.governance.permission.domain;

/**
 * 内置权限运行模式，对“规则阶段产出的候选决策”做最终变换。
 *
 * <p>判定顺序固定为：先执行 {@code PermissionRule + PermissionRuleHandler} 得到候选决策；没有规则命中时 候选决策为空。随后由 {@code
 * BuiltinPermissionModePolicy} 按 Mode 变换候选决策：
 *
 * <ul>
 *   <li>{@link #DEFAULT}：候选决策原样生效；无候选时 ASK，进入权限审核中断；场景：正常场景识别会出现审核中断。
 *   <li>{@link #BYPASS}：只保留显式 DENY，其余情况（含 ASK 规则和无规则）全部 ALLOW； 场景：各种ask情况会直接放行执行。
 *   <li>{@link #DONT_ASK}：只保留显式 ALLOW，其余情况（含 ASK/DENY 规则和无规则）全部 DENY。场景：各种ask情况会直接deny拒绝掉，执行。
 * </ul>
 *
 * <p>因此三个模式是“保守询问 / 仅 DENY 拦截 / 仅 ALLOW 放行”的对称策略，而不是同一个兜底值。配置自定义 {@code PermissionModePolicy}
 * 时，由策略通过 {@code decide(context, candidate)} 接管最终决策；未配置时由 {@code BuiltinPermissionModePolicy}
 * 应用上述内置语义。
 */
public enum PermissionMode {

    /** 规则优先；无规则命中时 ASK，允许进入权限审核中断。 */
    DEFAULT("规则优先，无规则时询问"),

    /** 只保留显式 DENY；其余情况放行，不产生权限审核中断。 */
    BYPASS("仅显式 DENY 拦截，其余放行"),

    /** 只保留显式 ALLOW；其余情况拒绝，不产生权限审核中断。 */
    DONT_ASK("仅显式 ALLOW 放行，其余拒绝");

    private final String description;

    PermissionMode(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}

package cloud.changlu.agentforge.agent.governance.permission.domain;

/**
 * 一条权限规则。
 *
 * <p>规则只描述“是什么类型、命中后给什么决策”，具体匹配逻辑由 {@code PermissionRuleHandler} 负责。
 *
 * <p>内置类型由 {@link PermissionRuleType} 统一维护，当前为 {@link PermissionRuleType#REGEX}。
 *
 * <p>自定义类型通过实现本接口并注册对应的处理器扩展。
 */
public interface PermissionRule {

    /** 规则唯一标识，用于日志、审计和排查。 */
    String getId();

    /** 规则类型；同一类型必须有且只有一个规则处理器。 */
    String getType();

    /** 规则命中后返回的权限决策。 */
    PermissionDecision getDecision();
}

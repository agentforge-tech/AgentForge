package cloud.changlu.agentforge.agent.governance.permission.spi;

import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionRule;
import cloud.changlu.agentforge.agent.governance.permission.engine.PermissionContext;

/**
 * 权限规则类型处理器扩展点。
 *
 * <p>一种规则类型对应一个处理器。引擎按规则顺序逐条询问处理器是否命中，第一个命中规则决定最终 ALLOW / DENY / ASK。自定义类型不能让未知规则静默匹配。
 */
public interface PermissionRuleHandler {

    /** 支持的规则类型，必须与 {@link PermissionRule#getType()} 一致。 */
    String type();

    /**
     * 判断规则是否命中当前工具调用。
     *
     * @param rule 当前规则
     * @param context 权限评估上下文
     * @return true 表示命中
     */
    boolean matches(PermissionRule rule, PermissionContext context);
}

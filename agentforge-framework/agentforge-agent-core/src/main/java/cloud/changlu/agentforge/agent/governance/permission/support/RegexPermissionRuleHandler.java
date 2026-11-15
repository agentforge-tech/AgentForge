package cloud.changlu.agentforge.agent.governance.permission.support;

import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionRule;
import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionRuleType;
import cloud.changlu.agentforge.agent.governance.permission.domain.RegexPermissionRule;
import cloud.changlu.agentforge.agent.governance.permission.engine.PermissionContext;
import cloud.changlu.agentforge.agent.governance.permission.spi.PermissionRuleHandler;

/** 内置正则规则处理器。 */
public final class RegexPermissionRuleHandler implements PermissionRuleHandler {

    @Override
    public String type() {
        return PermissionRuleType.REGEX.getValue();
    }

    @Override
    public boolean matches(PermissionRule rule, PermissionContext context) {
        if (!(rule instanceof RegexPermissionRule)) return false;
        RegexPermissionRule regexRule = (RegexPermissionRule) rule;
        return regexRule.matches(context.getRequest().name(), context.getRequest().arguments());
    }
}

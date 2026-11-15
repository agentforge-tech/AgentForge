package cloud.changlu.agentforge.agent.governance.permission.domain;

/**
 * 内置权限规则类型。
 *
 * <p>内置类型集中由枚举维护，避免在引擎、规则和处理器中散落字符串常量。自定义类型仍使用字符串扩展， 因此枚举不会限制第三方规则类型。
 */
public enum PermissionRuleType {
    REGEX("regex");

    private final String value;

    PermissionRuleType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}

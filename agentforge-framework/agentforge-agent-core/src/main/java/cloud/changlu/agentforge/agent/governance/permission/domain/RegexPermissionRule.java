package cloud.changlu.agentforge.agent.governance.permission.domain;

import java.util.regex.Pattern;

/** 内置正则规则：工具名全匹配，原始 JSON 参数包含匹配。 */
public final class RegexPermissionRule implements PermissionRule {

    private final String id;
    private final Pattern toolPattern;
    private final Pattern argumentPattern;
    private final PermissionDecision decision;

    public RegexPermissionRule(
            String id, String toolRegex, String argumentRegex, PermissionDecision decision) {
        if (decision == null) throw new IllegalArgumentException("decision must not be null");
        this.id = id;
        this.toolPattern = Pattern.compile(blankToDefault(toolRegex, ".*"));
        this.argumentPattern = Pattern.compile(blankToDefault(argumentRegex, ".*"), Pattern.DOTALL);
        this.decision = decision;
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.trim().isEmpty() ? defaultValue : value;
    }

    public boolean matches(String toolName, String arguments) {
        return toolPattern.matcher(toolName == null ? "" : toolName).matches()
                && argumentPattern.matcher(arguments == null ? "" : arguments).find();
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getType() {
        return PermissionRuleType.REGEX.getValue();
    }

    @Override
    public PermissionDecision getDecision() {
        return decision;
    }
}

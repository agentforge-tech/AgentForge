package cloud.changlu.agentforge.agent.governance.permission.engine;

import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionDecision;
import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionRule;
import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionRuleType;
import cloud.changlu.agentforge.agent.governance.permission.spi.PermissionModePolicy;
import cloud.changlu.agentforge.agent.governance.permission.spi.PermissionRuleHandler;
import cloud.changlu.agentforge.agent.governance.permission.support.BuiltinPermissionModePolicy;
import cloud.changlu.agentforge.agent.governance.permission.support.RegexPermissionRuleHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限判定执行器，顺序固定为“规则优先、Mode 变换在后”。
 *
 * <ol>
 *   <li>规则阶段：按注册顺序调用 {@code PermissionRuleHandler}，第一个命中的规则产生候选决策；
 *   <li>模式阶段：自定义 {@code PermissionModePolicy} 接收候选决策并给出最终决策；无自定义策略时， 由 {@link
 *       BuiltinPermissionModePolicy} 结合候选决策按 Mode 做场景级变换；
 *   <li>Mode 与规则是搭配关系：规则提供细粒度覆盖，Mode 决定该场景下如何解释候选和空候选。
 * </ol>
 */
public final class PermissionEngine {
    private final List<PermissionRule> rules;
    private final Map<String, PermissionRuleHandler> ruleHandlers;
    private final PermissionModePolicy effectiveModePolicy;

    public PermissionEngine(List<PermissionRule> rules, PermissionModePolicy modePolicy) {
        this(rules, Collections.<PermissionRuleHandler>emptyList(), modePolicy);
    }

    public PermissionEngine(
            List<PermissionRule> rules,
            List<PermissionRuleHandler> ruleHandlers,
            PermissionModePolicy modePolicy) {
        this.rules =
                rules == null
                        ? Collections.<PermissionRule>emptyList()
                        : Collections.unmodifiableList(new ArrayList<PermissionRule>(rules));
        Map<String, PermissionRuleHandler> handlers =
                new LinkedHashMap<String, PermissionRuleHandler>();
        handlers.put(PermissionRuleType.REGEX.getValue(), new RegexPermissionRuleHandler());
        if (ruleHandlers != null) {
            for (PermissionRuleHandler handler : ruleHandlers) {
                if (handler == null || handler.type() == null || handler.type().trim().isEmpty()) {
                    throw new IllegalArgumentException(
                            "permission rule handler type must not be blank");
                }
                String type = handler.type().trim();
                if (handlers.put(type, handler) != null) {
                    throw new IllegalArgumentException(
                            "duplicate permission rule handler type: " + type);
                }
            }
        }
        for (PermissionRule rule : this.rules) {
            if (rule == null || rule.getType() == null || rule.getType().trim().isEmpty()) {
                throw new IllegalArgumentException("permission rule type must not be blank");
            }
            if (rule.getDecision() == null) {
                throw new IllegalArgumentException(
                        "permission rule decision must not be null: " + rule.getId());
            }
            if (!handlers.containsKey(rule.getType())) {
                throw new IllegalArgumentException(
                        "no permission rule handler registered for type: " + rule.getType());
            }
        }
        this.ruleHandlers =
                Collections.unmodifiableMap(
                        new LinkedHashMap<String, PermissionRuleHandler>(handlers));
        this.effectiveModePolicy =
                modePolicy == null ? BuiltinPermissionModePolicy.INSTANCE : modePolicy;
    }

    /** 执行权限判定：规则先生成候选决策，再由自定义 ModePolicy 或内置 Mode 结合候选做最终决策。 */
    public PermissionDecision decide(PermissionContext context) {
        PermissionDecision candidate = ruleDecision(context);
        // 规则先产出候选；自定义策略和内置 Mode 都通过同一个 SPI 完成最终决策。
        return effectiveModePolicy.decide(context, candidate);
    }

    /** 规则阶段：第一个命中规则的决策；没有命中时返回 null。 */
    private PermissionDecision ruleDecision(PermissionContext context) {
        for (PermissionRule rule : rules) {
            PermissionRuleHandler handler = ruleHandlers.get(rule.getType());
            if (handler == null) {
                throw new IllegalStateException(
                        "no permission rule handler registered for type: " + rule.getType());
            }
            if (handler.matches(rule, context)) {
                return rule.getDecision();
            }
        }
        return null;
    }
}

package cloud.changlu.agentforge.agent.tool.support;

import cloud.changlu.agentforge.agent.constant.ToolConstants;
import cloud.changlu.agentforge.model.tool.spec.ToolParameters;
import cloud.changlu.agentforge.model.tool.spec.ToolSpecification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具 Schema 思考参数注入工具。
 *
 * <p>向任意来源（本地方法、HTTP 插件、MCP Server）的工具 Schema 追加必填的 {@code __thought}
 * 参数，使模型在调用工具前显式说明调用意图。该参数仅暴露给模型，执行器会忽略未声明的额外参数。
 *
 * @author changlu
 * @since 2026-10-29
 */
public final class ToolSpecificationThoughts {

    private ToolSpecificationThoughts() {}

    /**
     * 向工具 Schema 注入思考参数；已存在时原样返回。
     *
     * @param original 原始工具定义
     * @return 注入思考参数后的工具定义
     */
    @SuppressWarnings("unchecked")
    public static ToolSpecification withThought(ToolSpecification original) {
        if (original == null) {
            return null;
        }
        ToolParameters parameters = original.parameters();
        Map<String, Object> schema =
                parameters == null
                        ? new LinkedHashMap<String, Object>()
                        : new LinkedHashMap<String, Object>(parameters.toMap());
        schema.put("type", "object");

        Object rawProperties = schema.get("properties");
        Map<String, Object> properties =
                rawProperties instanceof Map
                        ? new LinkedHashMap<String, Object>((Map<String, Object>) rawProperties)
                        : new LinkedHashMap<String, Object>();
        if (properties.containsKey(ToolConstants.THOUGHT_PARAMETER_NAME)) {
            return original;
        }

        Map<String, Object> thoughtDefinition = new LinkedHashMap<String, Object>();
        thoughtDefinition.put("type", "string");
        thoughtDefinition.put("description", ToolConstants.THOUGHT_PARAMETER_DESCRIPTION);
        properties.put(ToolConstants.THOUGHT_PARAMETER_NAME, thoughtDefinition);
        schema.put("properties", properties);

        Object rawRequired = schema.get("required");
        List<String> required = new ArrayList<String>();
        if (rawRequired instanceof Collection) {
            for (Object item : (Collection<Object>) rawRequired) {
                required.add(String.valueOf(item));
            }
        }
        if (!required.contains(ToolConstants.THOUGHT_PARAMETER_NAME)) {
            required.add(ToolConstants.THOUGHT_PARAMETER_NAME);
        }
        schema.put("required", required);

        return original.toBuilder().parameters(ToolParameters.from(schema)).build();
    }
}

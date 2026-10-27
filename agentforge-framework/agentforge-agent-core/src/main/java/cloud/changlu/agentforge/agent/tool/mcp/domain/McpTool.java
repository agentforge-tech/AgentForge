package cloud.changlu.agentforge.agent.tool.mcp.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A tool advertised by an MCP server through {@code tools/list}.
 *
 * @author changlu
 * @date 2026/10/05
 */
public final class McpTool {

    private final String name;
    private final String description;
    private final Map<String, Object> inputSchema;

    public McpTool(String name, String description, Map<String, Object> inputSchema) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = description;
        this.inputSchema =
                inputSchema == null
                        ? Collections.<String, Object>emptyMap()
                        : Collections.unmodifiableMap(
                                new LinkedHashMap<String, Object>(inputSchema));
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    /**
     * @return the raw JSON Schema describing the tool arguments, never {@code null}
     */
    public Map<String, Object> inputSchema() {
        return inputSchema;
    }

    // ---- JavaBean 访问器：保证 JSON 序列化（Jackson）能识别出工具名称、描述与入参 Schema ----

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, Object> getInputSchema() {
        return inputSchema;
    }

    @Override
    public String toString() {
        return "McpTool{" + "name='" + name + '\'' + ", description='" + description + '\'' + '}';
    }
}

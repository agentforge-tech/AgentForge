# 插件工具统一 Function Call 标准：思考字段的注入、执行与降级设计

## 一、背景

AgentForge 的工具来源目前有三类：

| 来源 | 构建入口 | 工具定义来源 |
| --- | --- | --- |
| Local | `LocalToolFactory` | Java `@Tool` 方法 |
| HTTP 插件 | `HttpToolFactory` | 数据库中的插件方法配置 |
| MCP 插件 | `McpToolFactory` | 远端 `tools/list` 返回的 `inputSchema` |

在《Agent 工具调用思考参数与前端优雅交互设计》中，本地工具已经支持向 Function Call Schema 注入保留参数 `__thought`，让模型在调用工具时顺带给出面向用户的调用意图。但 HTTP 与 MCP 插件工具各自独立构建 Schema，长期存在两个问题：

1. **能力不一致**：同样的 ReAct 循环里，本地工具带调用意图，插件工具却没有，前端只能对部分工具展示“为什么调用”。
2. **扩展成本高**：每新增一种工具来源，都要重新实现一遍 Schema 增强逻辑，容易出现字段名、`required` 处理、幂等判断不一致。

因此，本文将插件工具统一到同一套 Function Call 标准上：**在工具定义构建阶段注入思考字段，在执行阶段按来源隔离，保证远端服务不感知该字段**。

## 二、设计目标

1. **统一注入**：Local、HTTP、MCP 共用同一个 Schema 增强器，避免多套实现漂移。
2. **非侵入业务**：不修改插件方法配置、不要求 MCP Server 声明额外参数，也不改变执行器签名。
3. **幂等**：同一份 Schema 被重复处理时，不重复追加 `__thought`。
4. **执行层隔离**：思考字段只对模型可见，HTTP 与 MCP 都不会把它透传给真实服务。
5. **向后兼容**：原有 `buildHttpTools(...)`、`buildTools(...)`、`load(...)` 方法保持默认行为，新增能力通过显式入口开启。

## 三、总体架构

### 3.1 分层职责

| 层级 | 组件 | 职责 |
| --- | --- | --- |
| 常量层 | `ToolConstants` | 统一维护 `__thought` 字段名与 Schema 描述 |
| 增强层 | `ToolSpecificationThoughts` | 唯一注入入口，负责拷贝并扩展 Schema |
| 构建层 | `LocalToolFactory` / `HttpToolFactory` / `McpToolFactory` | 在生成 `ToolSpecification` 时决定是否调用增强层 |
| 装配层 | `HttpAgentToolLoader` / `McpAgentToolLoader` | 按 Agent 配置加载插件工具，并把 `withThought` 透传下去 |
| 执行层 | `LocalToolExecutor` / `HttpToolExecutor` / `McpToolExecutor` | 只消费真实业务参数，隔离框架保留字段 |
| 接入层 | `ReActAgentFactory` | 组装 `ToolService`，决定插件工具是否启用思考字段 |

### 3.2 数据流

```text
ToolConstants
      │ 字段名 + 描述
      ▼
ToolSpecificationThoughts.withThought(spec)
      │
      ├── LocalToolFactory ──► LocalToolExecutor（反射只绑定已声明参数）
      ├── HttpToolFactory  ──► HttpToolExecutor （processArguments 只处理已配置参数）
      └── McpToolFactory   ──► McpToolExecutor  （sanitizeArguments 显式剔除）
      │
      ▼
ToolService ──► 模型 Function Call ──► SSE tool_call 事件 ──► 前端展示调用意图
```

关键点：**Schema 增强与执行隔离是一对必须同时存在的设计**。只注入不隔离，会让远端服务收到未知参数；只隔离不注入，模型不会主动产生调用意图。

## 四、统一注入器

### 4.1 实现位置

`cloud.changlu.agentforge.agent.tool.support.ToolSpecificationThoughts`

该组件位于 `agentforge-agent-core`，与三个工厂同模块，避免各来源重复实现。

### 4.2 核心实现

```java
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
```

### 4.3 处理步骤

1. **拷贝原 Schema**：将不可变的 `ToolParameters` 转成 `LinkedHashMap`，保证原始定义不被修改。
2. **补齐顶层结构**：即使原工具没有参数（如 MCP 的 `echo` 工具），也保证存在 `type: object`。
3. **幂等判断**：`properties` 中已含 `__thought` 时直接返回原对象，避免重复注入。
4. **追加属性定义**：写入字符串类型与统一描述。
5. **合并 `required`**：保留模型原有的必填列表，再追加 `__thought`，且去重。
6. **保留其它信息**：通过 `toBuilder()` 只替换 `parameters`，工具名、描述、`metadata`、`strict` 全部保留。

### 4.4 为什么采用 Copy-on-Write

`ToolParameters` 内部是 `Collections.unmodifiableMap`。注入器不直接修改原 Map，而是构造新的 `ToolParameters`，原因有三点：

- 同一份 `ToolSpecification` 可能被多个 Agent 共享（例如缓存后的 MCP 工具定义）。
- 避免调用方持有的 Schema 被意外改写，引发难以定位的行为差异。
- 增强操作可重复执行，天然支持 `withThought = false` 时返回原始对象。

## 五、HTTP 插件工具接入

### 5.1 工厂入口

```java
// 单个插件，使用默认传输实现
public static Map<ToolSpecification, ToolExecutor> buildHttpToolsWithThought(
        HttpPlugin httpPlugin, boolean withThought);

// 单个插件，可注入自定义传输
public static Map<ToolSpecification, ToolExecutor> buildHttpTools(
        HttpPlugin httpPlugin, HttpTransport httpTransport, boolean withThought);

// 多个插件
public static Map<ToolSpecification, ToolExecutor> buildHttpTools(
        List<HttpPlugin> httpPlugins, HttpTransport httpTransport, boolean withThought);
```

原有 `buildHttpTools(plugin)`、`buildHttpTools(plugins, transport)` 仍保持默认 `withThought = false`，不会改变已有调用方行为。

### 5.2 注入时机

```java
ToolSpecification toolSpecification =
        buildToolSpecification(
                httpPluginMethod.getMethodName(),
                httpPluginMethod.getMethodDescription(),
                httpPluginMethod.getParameters());
if (withThought) {
    toolSpecification = ToolSpecificationThoughts.withThought(toolSpecification);
}
```

注入发生在 `HttpPluginMethod` 转成 `ToolSpecification` 之后、与 `HttpToolExecutor` 建立映射之前，因此不会影响 HTTP 请求参数配置。

### 5.3 Schema 示例

HTTP 插件方法只配置了 `city` 一个路径参数，开启思考字段后模型看到的是：

```json
{
  "name": "getWeather",
  "description": "查询指定城市天气",
  "parameters": {
    "type": "object",
    "properties": {
      "city": {
        "type": "string",
        "description": "城市名称"
      },
      "__thought": {
        "type": "string",
        "description": "Explain why this tool is needed for the current task..."
      }
    },
    "required": ["city", "__thought"]
  }
}
```

### 5.4 执行侧：只处理已声明的参数

`HttpToolExecutor.processArguments(...)` 遍历的是 `parameterConfigs`，也就是插件方法真实配置的参数集合：

```java
for (Map.Entry<String, ParameterConfig> entry : parameterConfigs.entrySet()) {
    String paramName = entry.getKey();
    if (providedArguments.containsKey(paramName)) {
        value = providedArguments.get(paramName);
    }
    // ...
}
```

因此 `__thought` 不会进入 URL、Query、Header 或 Body，也无需额外过滤。HTTP 工具的“隔离”来自参数映射机制本身。

## 六、MCP 插件工具接入

### 6.1 工厂入口

```java
public static Map<ToolSpecification, ToolExecutor> buildToolsWithThought(
        McpClient client, String serverAlias, boolean withThought);

public static Map<ToolSpecification, ToolExecutor> buildTools(
        McpClient client,
        String serverAlias,
        Set<String> enabledTools,
        Set<String> disabledTools,
        boolean withThought);
```

工具名仍然按 `serverAlias__toolName` 命名。注入发生在命名、描述、`metadata` 都构建完成后：

```java
ToolSpecification specification =
        ToolSpecification.builder()
                .name(qualify(alias, tool.name()))
                .description(tool.description())
                .parameters(
                        McpToolSpecificationMapper.toToolParameters(tool.inputSchema()))
                .addMetadata("mcp.server", alias == null ? "" : alias)
                .addMetadata("mcp.tool", tool.name())
                .build();
if (withThought) {
    specification = ToolSpecificationThoughts.withThought(specification);
}
```

### 6.2 关键差异：远端 Server 可能严格校验 Schema

HTTP 执行器按自己的参数配置取值，天然忽略未知参数；MCP 不同——`callTool` 会把模型给出的参数原样发给远端 Server。部分 MCP Server 会校验 `additionalProperties` 或直接拒绝未知字段，导致加入 `__thought` 后调用失败。

因此 `McpToolExecutor` 在发起调用前统一清理：

```java
private Map<String, Object> sanitizeArguments(ToolExecutionRequest request) {
    Map<String, Object> arguments = McpJson.argumentsAsMap(request.arguments());
    if (!arguments.containsKey(ToolConstants.THOUGHT_PARAMETER_NAME)) {
        return arguments;
    }
    Map<String, Object> sanitized = new LinkedHashMap<String, Object>(arguments);
    sanitized.remove(ToolConstants.THOUGHT_PARAMETER_NAME);
    return sanitized;
}
```

`execute` 与 `executeWithResult` 都经过该方法，保证同步调用和结构化结果调用行为一致。

### 6.3 Schema 示例

以 `demo` Server 的 `get_weather` 工具为例：

```json
{
  "name": "demo__get_weather",
  "parameters": {
    "type": "object",
    "properties": {
      "location": { "type": "string" },
      "__thought": { "type": "string" }
    },
    "required": ["location", "__thought"]
  },
  "metadata": {
    "mcp.server": "demo",
    "mcp.tool": "get_weather"
  }
}
```

## 七、装配层：Loader 与 Agent 接入

### 7.1 统一的命名约定

两个 Loader 都采用 `loadWithThought(configs, withThought)`：

```java
// HTTP
public Map<ToolSpecification, ToolExecutor> loadWithThought(
        List<HttpToolConfig> configs, boolean withThought);

// MCP
public Map<ToolSpecification, ToolExecutor> loadWithThought(
        List<McpToolConfig> configs, boolean withThought);
```

原 `load(configs)` 继续保留，并委托到 `loadWithThought(configs, false)`，避免影响其它调用场景（例如仅做工具预览的接口）。

### 7.2 ReActAgent 装配

```java
PluginConfig pluginConfig = standardAgentConfig.getPluginConfig();

Map<ToolSpecification, ToolExecutor> httpTools =
        httpAgentToolLoader.loadWithThought(
                pluginConfig == null ? null : pluginConfig.getHttpPlugins(), true);
toolService.tools(httpTools);

Map<ToolSpecification, ToolExecutor> mcpTools =
        mcpAgentToolLoader.loadWithThought(
                pluginConfig == null ? null : pluginConfig.getMcpPlugins(), true);
toolService.tools(mcpTools);
```

装配层显式传 `true`，与本地工具启用思考字段的策略保持一致：**思考字段是框架默认能力，不再作为每个插件的独立配置项**。这样既避免配置分散，也避免出现“同类工具行为不一致”的体验割裂。

## 八、端到端时序

```text
Agent 构建阶段
  ReActAgentFactory
    ├─ HttpAgentToolLoader.loadWithThought(configs, true)
    │    └─ HttpToolFactory.buildHttpTools(plugin, transport, true)
    │         └─ ToolSpecificationThoughts.withThought(spec)
    └─ McpAgentToolLoader.loadWithThought(configs, true)
         └─ McpToolFactory.buildTools(client, alias, null, null, true)
              └─ ToolSpecificationThoughts.withThought(spec)

模型调用阶段
  模型输出 function call:
    { "city": "杭州", "__thought": "需要实时天气以回答今天的出行建议。" }
      │
      ├─ LocalToolExecutor：反射只绑定 city
      ├─ HttpToolExecutor ：processArguments 只处理 city
      └─ McpToolExecutor  ：sanitizeArguments 移除 __thought 后 callTool

可观测阶段
  ToolExecutionRequest.arguments 原样进入 tool_call 事件
      │
      ├─ SSE 实时展示调用意图
      └─ 会话 events 持久化，历史重放保持一致
```

## 九、边界与降级设计

### 9.1 模型未返回思考字段

`__thought` 虽然在 Schema 中标记为必填，但不同模型对 JSON Schema 的遵循程度不同。执行链路必须容忍缺失：

- 执行器不依赖该字段；
- 前端按可选字段渲染，缺失时只展示工具名与耗时。

### 9.2 字段冲突

`__thought` 是框架保留字段。注入器检测到原 Schema 已声明同名属性时直接返回原对象，不覆盖业务定义。业务侧应避免声明同名字段。

### 9.3 空参数工具

MCP 工具可能没有 `inputSchema`（例如 `echo`）。注入器会补齐 `type: object` 与 `properties`，保证最终 Schema 结构合法。

### 9.4 Token 成本

每个工具会多出一个属性定义与一条描述文案。对于工具数量较多的 Agent，这是一次性 Schema 开销，但每次工具调用的 `__thought` 内容会增加输出 token。描述文案因此保持简短，并明确要求“简洁、面向用户”。

### 9.5 安全边界

`__thought` 会随 `tool_call` 事件进入 SSE 与会话 `events`，属于用户可见、可持久化数据。Schema 描述中已明确要求不得包含密钥、隐私信息或完整内部提示词。

### 9.6 多来源工具重名

HTTP Loader 在合并多个插件时按工具名去重，重复项记录 WARN 后跳过；MCP 通过 `serverAlias__toolName` 命名空间隔离。思考字段注入不改变命名规则，因此不会引入新的冲突面。

## 十、测试覆盖

`agentforge-agent-core` 中新增/补充的用例：

| 测试类 | 验证点 |
| --- | --- |
| `HttpToolFactoryTest` | 开启时 Schema 含 `__thought` 且进入 `required`；默认不注入 |
| `McpToolFactoryTest` | 注入后保留原有 `required`（如 `location`），并追加 `__thought` |
| `McpToolExecutorTest` | `__thought` 不会透传给远端 MCP Server |
| `LocalToolFactoryTest` | 本地工具注入逻辑在抽取公共组件后行为不变 |

执行命令：

```bash
mvn -pl :agentforge-agent-core -Dtest=LocalToolFactoryTest,HttpToolFactoryTest,McpToolFactoryTest,McpToolExecutorTest test
mvn -pl :agentforge-service-web -am test
```

## 十一、扩展新工具源的检查清单

新增一类工具来源（例如 RPC、A2A）时，按以下清单接入即可复用统一标准：

1. **构建期注入**：在生成 `ToolSpecification` 后调用 `ToolSpecificationThoughts.withThought(spec)`。
2. **执行期隔离**：确认执行器只消费真实业务参数；若参数会原样透传给外部服务，必须显式剔除 `__thought`。
3. **入口兼容**：保留原有工厂/加载方法，新增 `withThought` 参数或 `buildXxxWithThought` 命名入口。
4. **幂等性**：不要绕过公共注入器自行拼 Schema，避免重复注入与 `required` 处理不一致。
5. **测试**：至少覆盖“开启注入”“默认不注入”“执行不透传”三类用例。

## 十二、总结

这套设计的核心不是“多了一个参数”，而是把工具 Schema 的构建标准统一起来：

- **一个注入器**：`ToolSpecificationThoughts` 成为所有工具来源的唯一增强入口；
- **两层隔离**：构建期只影响模型可见的 Function Call Schema，执行期按来源过滤，远端服务零感知；
- **三种来源一致**：Local、HTTP、MCP 在同一个 ReAct 循环里输出同构的调用意图，前端无需区分工具来源即可统一渲染；
- **可扩展**：新增工具来源只需遵循注入与隔离两个约束，即可获得与现有工具一致的交互体验。

最终效果是：用户在 Agent 执行过程中看到的每一条工具调用，都带有“为什么现在要调用它”的简短说明，而插件服务本身仍然按照原始协议工作。

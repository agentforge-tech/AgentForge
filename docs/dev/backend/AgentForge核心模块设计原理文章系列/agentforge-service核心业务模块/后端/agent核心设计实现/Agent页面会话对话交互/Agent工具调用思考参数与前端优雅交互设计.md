# Agent 工具调用思考参数与前端优雅交互设计

## 一、背景

Agent 在 ReAct 过程中可能连续调用多个工具。如果页面只显示工具名和执行结果，用户只能看到“调用了什么”，无法感知“为什么现在要调用它”。在执行时间较长或者存在多步工具编排时，页面容易表现为简单的加载等待，缺少 Agent 正在分析和推进任务的交互感。

AgentForge 为此在本地工具 Schema 中注入框架级保留参数 `__thought`，让模型在发起工具调用时，同时给出一段面向用户展示的“工具调用意图摘要”。该内容随工具入参进入流式事件，最终在正式发布页的优雅交互模式中展示。

> `__thought` 用于表达可展示的调用意图，不是要求模型暴露完整的内部思维链。描述应简洁、面向用户，且不包含密钥、隐私数据等敏感信息。

## 二、设计目标

1. **增强过程感知**：让用户看到工具名、调用意图和执行耗时，而不是只看到无上下文的加载状态。
2. **不侵入业务工具**：不修改 `@Tool` 方法签名，不要求每个工具手工增加思考参数。
3. **按需开启**：由 `withThought` 开关决定是否注入，不影响现有工具的默认行为。
4. **实时与历史一致**：同一份工具入参既用于 SSE 实时展示，也保存到消息 `events` 中用于历史重放。
5. **兼容降级**：模型未返回 `__thought` 时，工具仍正常执行，前端不渲染思考文字即可。

## 三、保留参数 `__thought`

### 3.1 常量定义

`ToolConstants` 集中管理工具 Schema 构建阶段使用的框架级常量：

```java
/** Constants shared by tool schema construction. */
public final class ToolConstants {

    /** Synthetic argument used by a model to explain why it is invoking a tool. */
    public static final String THOUGHT_PARAMETER_NAME = "__thought";

    /** Prompt attached to {@link #THOUGHT_PARAMETER_NAME} in the tool JSON schema. */
    public static final String THOUGHT_PARAMETER_DESCRIPTION =
            "Explain why this tool is needed for the current task, what information it should "
                    + "obtain, and how the result supports the next step.";

    private ToolConstants() {}
}
```

### 3.2 命名思路

- `thought` 表明该值是工具调用前的意图描述。
- `__` 前缀表明它是 AgentForge 框架注入的保留字段，不是业务方法的真实入参。
- 字段名较短，可减少每次工具调用的 token 开销。
- 常量化可防止 Schema 构建、事件处理和页面展示之间出现字段名漂移。

### 3.3 Schema 中的最终形态

开启思考参数后，原始工具 Schema 会额外包含：

```json
{
  "type": "object",
  "properties": {
    "query": {
      "type": "string",
      "description": "Search query"
    },
    "__thought": {
      "type": "string",
      "description": "Explain why this tool is needed for the current task, what information it should obtain, and how the result supports the next step."
    }
  },
  "required": ["query", "__thought"]
}
```

`__thought` 被加入 `required`，目的是在支持工具调用的模型中稳定产生调用意图。但框架和前端仍必须容忍字段缺失，因为不同模型对 JSON Schema 约束的遵循程度不同。

## 四、`buildLocalToolsWithThought` 使用方式

### 4.1 API 定义

```java
public static Map<ToolSpecification, ToolExecutor> buildLocalToolsWithThought(
        Collection<Object> objectsWithTools, boolean withThought) {
    return buildLocalTools(objectsWithTools, false, withThought);
}
```

| 参数 | 含义 |
| --- | --- |
| `objectsWithTools` | 包含 `@Tool` 方法的对象集合，必须传对象实例而不是 `Class` |
| `withThought` | `true` 时为每个工具注入 `__thought`；`false` 时保持原始 Schema |
| 返回值 | `ToolSpecification -> ToolExecutor` 映射，可直接注入 `ToolService` |

该方法默认不启用 `LoggingToolExecutor`。`withThought` 只控制 Schema 注入，与工具执行日志开关是两个独立的维度。

### 4.2 正式 Agent 接入示例

```java
private ToolService buildToolService() {
    ToolService toolService = new ToolService();
    toolService.tools(
            LocalToolFactory.buildLocalToolsWithThought(
                    Collections.<Object>singletonList(openReachTools), true));
    return toolService;
}
```

这段配置表示：

1. 扫描 `openReachTools` 对象上的 `@Tool` 方法。
2. 为每个工具生成 `ToolSpecification` 和 `LocalToolExecutor`。
3. 在每个 `ToolSpecification.parameters` 中注入 `__thought`。
4. 将工具映射注入 `ToolService`，供 ReAct Agent 调用。

### 4.3 关闭思考参数

```java
Map<ToolSpecification, ToolExecutor> tools =
        LocalToolFactory.buildLocalToolsWithThought(toolObjects, false);
```

或使用保持兼容的默认入口：

```java
Map<ToolSpecification, ToolExecutor> tools =
        LocalToolFactory.buildLocalTools(toolObjects);
```

`buildLocalTools(...)` 内部等价于 `buildLocalToolsWithThought(toolObjects, false)`，因此现有调用方不会被强制改变。

## 五、底层原理

### 5.1 构建阶段：拷贝并扩展 Schema

`LocalToolFactory` 先通过 `ToolSpecifications.toolSpecificationFrom(method)` 从 `@Tool` 方法生成原始工具定义。当 `withThought=true` 时，`withThoughtParameter(...)` 执行以下步骤：

1. 将原 `ToolParameters` 转换为 Map，并使用 `LinkedHashMap` 拷贝。
2. 保留原有 `type`、`properties` 和 `required`。
3. 向 `properties` 追加 `__thought` 字符串定义。
4. 向 `required` 追加 `__thought`，同时避免重复添加。
5. 通过 `original.toBuilder()` 生成新的 `ToolSpecification`，保留工具名、描述等其他元数据。

这里不直接修改原 Schema，可以避免可变 Map 在多个工具定义之间被意外共享。

### 5.2 执行阶段：保留参数不进入 Java 方法

模型发起工具调用时，参数可能为：

```json
{
  "query": "AgentForge latest release",
  "__thought": "需要检索最新发布信息，用于核对回答中的版本与发布时间。"
}
```

`LocalToolExecutor.prepareArguments(...)` 只遍历 Java 方法实际声明的参数，并按参数名从入参 Map 取值。由于 Java 方法没有声明 `__thought`，该字段不会被传给业务方法，也不会改变反射调用的参数数量。

因此，`__thought` 的生命周期是“模型工具协议参数”，而不是“业务工具入参”。

### 5.3 流式事件与历史重放

`ServiceStreamingMiddleware` 在工具执行前后都会构建 `ToolCallResult`，并把 `ToolExecutionRequest.arguments()` 原样写入 `inputParams`。因此 `__thought` 会自然进入 `tool_call` SSE 事件，无需再增加专用协议字段。

```text
Tool Schema 注入 __thought
  ↓
模型生成 ToolExecutionRequest.arguments
  ↓
LocalToolExecutor 只绑定真实 Java 入参
  ↓
ServiceStreamingMiddleware 将 arguments 写入 inputParams
  ↓
tool_call SSE 事件 + ai_agent_session_message.events
  ↓
前端 ElegantToolItem 提取 __thought 并展示
```

`SessionLifecycleMiddleware` 收集同一份 `tool_call` 事件并在本轮结束时写入 `ai_agent_session_message.events`。历史会话通过事件重放恢复 `inputParams`，所以实时交互和历史查看使用同一展示逻辑。

## 六、前端优雅交互效果

正式发布页的优雅交互模式由 `ElegantToolItem` 解析 `part.inputParams`：

1. `inputParams` 是 JSON 字符串时先执行 `JSON.parse`；已经是对象时直接使用。
2. 读取 `__thought` 并去除首尾空白。
3. 字段存在且为非空字符串时，展示在工具名与耗时之间。
4. 字段缺失、为空或入参无法解析时，不渲染思考内容，不影响工具卡片。
5. 文本过长时使用单行省略，悬停可查看完整内容，避免长文本破坏事件流的紧凑感。

有 `__thought` 时：

```text
🔧 web_search  需要检索最新发布信息，以核对版本和发布时间。（1.8s）  ⌄
```

没有 `__thought` 时：

```text
🔧 web_search（1.8s）  ⌄
```

### 6.1 界面截图

每个tool中会带有思考内容：

![image-20261026233825299](https://pictured-bed.oss-cn-beijing.aliyuncs.com/img/2024/202610262338469.png)  

## 七、设计边界与注意事项

### 7.1 保留字段冲突

`__thought` 是 AgentForge 保留字段。业务工具不应声明同名参数。当原 Schema 已存在该字段时，当前实现保留原定义并不再重复注入。

### 7.2 模型兼容性

即使 `__thought` 在 Schema 中标记为必填，也不应把前端渲染依赖于它必然存在。某些模型或兼容层可能忽略部分 Schema 约束，因此前端必须按可选字段处理。

### 7.3 安全与可观测性

`inputParams` 会进入 SSE 和会话消息 `events`，因此 `__thought` 也是用户可见、可持久化的数据。不应在其描述中包含认证信息、密钥、完整内部提示词或其他敏感数据。

### 7.4 职责边界

| 层级 | 职责 |
| --- | --- |
| `ToolConstants` | 统一管理保留字段名与 Schema 描述 |
| `LocalToolFactory` | 根据开关扩展工具 Schema，不改变工具方法 |
| `LocalToolExecutor` | 只绑定 Java 方法真实入参，自然忽略框架保留字段 |
| `ServiceStreamingMiddleware` | 将完整工具入参转换为 `tool_call` 事件 |
| `SessionLifecycleMiddleware` | 将事件持久化到消息 `events`，保证历史可重放 |
| `ElegantToolItem` | 提取 `__thought`，按存在性进行优雅展示 |

## 八、总结

`buildLocalToolsWithThought(...)` 通过 Schema 增强，在不侵入业务工具方法的前提下，让模型在工具调用中补充可展示的意图摘要。`__thought` 通过既有工具入参、SSE 事件和消息 `events` 链路自然透传，前端只需按需提取展示。

这套设计把“模型为什么调用工具”转化为了可观测、可持久、可重放的交互事件，从而让正式发布页在保持简洁的同时，具备更清晰的 Agent 执行过程感知。

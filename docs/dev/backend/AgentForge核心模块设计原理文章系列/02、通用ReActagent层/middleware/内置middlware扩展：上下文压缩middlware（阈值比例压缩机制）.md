# AgentForge 上下文压缩 Middleware 技术设计：阈值比例压缩机制

> 文档定位：说明 AgentForge 内置上下文压缩 Middleware 的设计原理、触发时机、压缩算法、Prompt 结构，以及业务层（Service 层）如何通过生命周期钩子进行扩展。
>
> 当前实现位置：`agentforge-framework/agentforge-agent-core/src/main/java/cloud/changlu/agentforge/agent/extend/middlewares/compact/`。
>
> 本文所有参数、公式、行为均以当前代码为准，已去除早期设计中未落地的内容（如独立并发锁、协议校验器、中英文双 Prompt 等），相关差异在“当前实现边界与后续演进”中统一说明。

---

## 一、背景与问题引入

### 1.1 长会话为什么会把上下文撑爆

ReAct Agent 每一轮都会把会话记忆作为模型输入。随着用户消息、模型回复、工具调用和工具结果持续累积，上下文会出现三个问题：

1. 请求 Token 数逼近模型上下文上限，最终导致模型请求失败。
2. 大体积工具结果快速挤占窗口，模型输出和后续工具调用缺少空间。
3. 简单按消息条数淘汰历史会丢失长期目标、关键结论，并可能拆散“工具调用—工具结果”协议消息。

### 1.2 MessageWindowChatMemory 不够用

AgentForge 的 `MessageWindowChatMemory` 以消息条数控制容量，适合解决无界消息增长，但不能感知不同消息的 Token 差异。一条工具结果可能比几十条短消息更大。

**重点**：消息条数与 Token 数之间没有稳定关系。消息窗口只能做“数量兜底”，无法做“体积预算”。因此需要在消息窗口之上增加 Token 维度的治理能力。

### 1.3 核心思路

采用“达到阈值后压缩历史，保留最近上下文”的机制：

```text
系统提示 + 历史消息 + 最近消息
                │
                │ Token 使用率达到阈值
                ▼
系统提示 + 历史摘要 + 最近完整消息组
```

---

## 二、核心概念与配置参数

### 2.1 产品设计三问

- **什么时候压缩**：上下文 Token 使用量达到 `compressThreshold` 时触发。
- **压缩哪些内容**：压缩较早的历史消息；超大工具结果在写入 Memory 前先截断。
- **保留什么**：原始系统提示永久保留，最近对话按 `keepRatio` 保留原文，较早历史转换为一条摘要。

压缩前后结构：

```text
压缩前：系统提示 + 较早历史 + 最近对话
压缩后：系统提示 + 历史摘要 + 最近对话
```

### 2.2 五个核心配置参数

配置载体为 `CompactSummarizationMiddleware.CompactConfig`，仅包含以下五个字段：

| 参数 | 默认值 | 取值范围 | 产品含义 |
| --- | ---: | --- | --- |
| `maxContextTokens` | `1000000` | `> 0` | Agent 可使用的最大上下文 Token，是所有比例计算的基数 |
| `compressThreshold` | `80` | `0 ~ 100` | 达到最大上下文的百分比时触发压缩 |
| `keepRatio` | `40` | `10 ~ 100` | 压缩时最多保留最近原始消息的百分比 |
| `maxSystemRatio` | `30` | `0 ~ 100` | 摘要输入中系统类内容及摘要输出的上限 |
| `maxOtherRatio` | `50` | `0 ~ 100` | AI 回复、工具结果等其他摘要内容的上限 |

配置通过 Builder 构建：

```java
CompactConfig config = CompactConfig.builder()
        .maxContextTokens(1_000_000)
        .compressThreshold(80)
        .keepRatio(40)
        .maxSystemRatio(30)
        .maxOtherRatio(50)
        .build();
```

### 2.3 核心计算公式

所有预算都基于 `maxContextTokens` 按比例换算：

```text
触发压缩 Token = maxContextTokens × compressThreshold / 100
最近消息预算   = maxContextTokens × keepRatio / 100
摘要预算       = maxContextTokens × maxSystemRatio / 100
其他消息预算   = maxContextTokens × maxOtherRatio / 100
工具截断目标   = maxContextTokens × compressThreshold / 100
```

- `compressThreshold` 决定“何时压缩”以及工具结果“截断到多少”。
- `keepRatio` 决定“最近原始消息保留多少”。
- `maxSystemRatio`、`maxOtherRatio` 限制摘要阶段的输入与输出规模。

### 2.4 默认配置的实际含义

```text
最大上下文：1000000 Token（100 万）
达到 800000 Token（80%）时触发压缩
最多保留最近 400000 Token（40%）原文
摘要输入/输出最多约 300000 Token（30%）
待摘要的其他消息最多使用约 500000 Token（50%）
```

默认参数的设计目标不只是“压到阈值以下”，而是“压到明显低于阈值”。80% 触发后，最近消息最多保留 40%，摘要最多占 30%，压缩后总规模通常远低于触发线。

**重点**：如果压缩后仍停在 78% 附近，下一轮稍有工具输出就会再次触发，形成压缩抖动。比例参数的隐藏目的，是为后续若干轮工具调用预留缓冲。

### 2.5 配置约束与构建期校验

`CompactConfig.Builder` 在 setter 阶段对比例参数做 clamp，在 `build()` 阶段做最终强校验：

```text
compressThreshold = clamp(value, 0, 100)
keepRatio         = clamp(value, 10, 100)
maxSystemRatio    = clamp(value, 0, 100)
maxOtherRatio     = clamp(value, 0, 100)

build():
  maxContextTokens > 0
  maxSystemRatio + maxOtherRatio <= 100
```

**注意**：比例参数越界时会被静默修正，而 `maxContextTokens <= 0` 或 `maxSystemRatio + maxOtherRatio > 100` 会抛出 `IllegalArgumentException`。

---

## 三、阈值触发逻辑与时机

压缩检查只放在两个进入点，其他位置不判断。

### 3.1 阈值判定规则

`TokenStatsUtil.needCompress` 的判定逻辑：

```text
总 Token = 所有消息估算 Token 之和
使用率   = 总 Token / maxContextTokens × 100
触发条件 = 使用率 >= compressThreshold
```

即达到阈值（含等于）就触发压缩。

### 3.2 两个检查时机与三种触发来源

| 时机 | 方法 | 输入 | 触发来源 `CompactTrigger` |
| --- | --- | --- | --- |
| 模型调用前 | `beforeModelCall` | 本轮 `ChatRequest.messages()` | `MODEL_CALL`（`model_call`） |
| 单工具结果写回前 | `afterToolExecution` | 当前 Memory + 本次工具结果 | `TOOL_RESULT`（`tool_result`） |
| 并发工具结果写回前 | `afterConcurrentToolExecution` | 当前 Memory + 本轮全部工具结果 | `CONCURRENT_TOOL_RESULT`（`concurrent_tool_result`） |

```text
模型调用前检查     → 解决历史累积
工具结果写回前检查 → 解决单次突增
```

两个时机的职责不同：

1. **模型调用前**：以本轮真正准备发送给模型的 `ChatRequest.messages()` 为输入估算 Token，比直接读 Memory 更准确。
2. **工具结果写回前**：计算“当前 Memory + 本次工具结果”，超预算时就地截断，避免大结果先污染 Memory。

**注意**：不要用模型返回的 `TokenUsage.inputTokens()` 做本次调用前判断。它是上一次请求完成后的真实值，只能用于监控和校准估算器，不能代表当前待发送的请求。

### 3.3 工具结果的截断目标

工具触发路径不只是“判断是否超标”，而是把结果压缩回阈值以内：

```text
目标总 Token   = maxContextTokens × compressThreshold / 100
需要减少的 Token = 当前总 Token - 目标总 Token
目标结果 Token  = max(原始结果 Token - 需要减少的 Token, MIN_TOOL_RESULT_TOKENS)
```

其中 `MIN_TOOL_RESULT_TOKENS = 100`，避免工具结果被截断到不可用的长度。

---

## 四、压缩算法实现

一旦触发压缩，`performCompression` 固定执行以下步骤。

### 4.1 分离系统消息与非系统消息

```text
systemMessages：所有 SystemMessage，原样永久保留
otherMessages ：UserMessage、AiMessage、ToolExecutionResultMessage
```

压缩只针对 `otherMessages`，摘要本身也以新的 `SystemMessage` 形式加入结果。

### 4.2 按工具组切分消息

`groupMessages` 会把消息划分为原子组：

```text
普通消息：单条为一组
AiMessage（含 toolExecutionRequests）：与其后连续的 ToolExecutionResultMessage 合为一组
```

**重点**：工具调用与其结果必须整组保留或整组压缩。按单条消息切割会保留工具结果却丢掉发起调用的 AiMessage，模型侧会直接报协议错误。

### 4.3 从尾部选择最近窗口

`selectRecentGroupsFromEnd` 从最后一组向前累加 Token：

1. 最后一组无条件保留（即使它本身已超过 `keepRatio` 预算）。
2. 继续向前，只要“已保留 Token + 当前组 Token <= keepRatio 预算”就保留。
3. 遇到第一个放不下的组即停止。
4. 如果所有组都能保留（起始索引为 0），则本次不产生摘要，原样返回。

```text
keepTokens = maxContextTokens × keepRatio / 100
```

**注意**：由于最后一组无条件保留，实际保留的最近窗口可能略高于 `keepRatio` 预算。这是为了保证当前轮次上下文完整，属于有意取舍。

### 4.4 摘要输入裁剪

待压缩历史先经过 `truncateByCategory`，按类型分配预算：

```text
系统消息预算   = maxContextTokens × maxSystemRatio / 100
其他消息预算   = maxContextTokens × maxOtherRatio / 100
```

规则：

- 系统消息在预算内保留，超出时截断。
- **UserMessage 始终保留，不占用“其他消息预算”的淘汰判定**（保证用户意图不丢失）。
- AI 与工具结果按“其他消息预算”依次保留，超出时截断并重建同类型消息。

### 4.5 生成摘要

`generateSummary` 的流程：

1. 用 `truncateByCategory` 裁剪待摘要历史。
2. 计算摘要预算 `maxSummaryTokens = maxContextTokens × maxSystemRatio / 100`。
3. 用 `TokenStatsUtil.formatConversation(truncated, maxSummaryTokens)` 拼接对话文本，超出预算的部分按句边界截断。
4. 调用 `chatContext.getChatModel().chat(prompt)` 生成摘要。
5. 摘要输出超过 `maxSummaryTokens` 时再次截断。
6. 包装为带固定标识的 `SystemMessage`。

**注意**：摘要直接调用 `ChatModel`，不再次经过当前 `ReActAgent`，否则会递归触发 Middleware。

### 4.6 重建并替换 Memory

压缩结果顺序固定：

```text
[SystemMessage: Agent 原始系统提示]      ← 原样保留
[SystemMessage: AgentForge 上下文摘要]   ← 由较早历史压缩而来
[最近完整消息组...]                       ← 按 Token 预算保留原文
```

`replaceMemory` 的替换方式：

```text
1. 先备份当前 Memory 消息
2. clear()
3. 逐条 add 压缩后的消息
4. 若中途抛异常，回滚为备份内容
```

压缩成功后：

- `beforeModelCall` 返回携带压缩消息的新 `ChatRequest`，并保留原 `parameters()`。
- 同时更新 Memory，避免下一轮从未压缩的 Memory 重新构建请求。

**重点**：只改 Request 会让下一轮重复压缩；只改 Memory 会让本轮已构建的 Request 仍携带旧消息。两者必须同步更新。

### 4.7 失败降级

摘要生成或替换过程抛异常时：

- 记录 WARNING 日志。
- 返回原始 `ChatRequest`，不阻断本轮模型调用。
- Memory 通过 `replaceMemory` 的回滚机制保持不变。

**重点**：压缩是辅助能力，不能让辅助能力阻断 Agent 主任务。失败时宁可“不压缩”，也不要“压缩坏了”。

### 4.8 当前实现的边界行为

为了行为可预测，当前实现有若干明确的边界约定：

- 若所有消息都能放入最近窗口，则不生成摘要（`KeepResult.keepAll`）。
- 压缩只执行一次，不做事后“仍超阈值”的循环再压缩。
- 没有对压缩后的消息序列做额外的协议校验。
- `afterToolExecution` / `afterConcurrentToolExecution` 只改变工具结果内容，不改变消息条数，因此事件中的前后消息数相同。

---

## 五、压缩 Prompt 与输出结构

### 5.1 实际使用的 Prompt

当前实现只内置英文 Prompt，定义在 `CompactSummarizationMiddleware.SUMMARY_PROMPT` 中：

```text
You are the context compressor for an Agent. Compress the given conversation
history into a structured working summary that the Agent can use to continue
the task in later steps.

Extract information along the following dimensions:
1. Goal: what the user wants to accomplish, the scope, and success criteria;
2. State: how far the task has progressed, what is done, and what comes next;
3. Facts: confirmed key entities, parameters, identifiers, and data locations;
4. Decisions: important decisions and their rationale, including rejected options;
5. Results: key conclusions and artifacts from tool calls; do not copy large raw outputs;
6. Open items: unresolved issues, blockers, risks, and items awaiting confirmation;
7. Constraints: explicit preferences, prohibitions, and change requests from the user.

Requirements:
- Use only the provided history; do not speculate or add information that is not present;
- Keep it concise for simple tasks; for complex tasks, preserve subtasks, status,
  and dependencies under "Current State";
- Omit greetings, repetitive content, and irrelevant details;
- If an old summary exists, merge it with older history instead of stacking it;
- Strictly use the following fixed structure, and write "None" for empty fields:

[AgentForge Context Summary]
Goal: ...
Current State: ...
Confirmed Facts: ...
Key Decisions: ...
Tool Conclusions: ...
Open Items: ...
Constraints: ...
Next Action: ...
```

最终发给模型的 Prompt 由 `buildSummaryPrompt` 拼接：

```text
{SUMMARY_PROMPT}

Conversation history to compress:
{conversation}
Return only the structured summary. Do not wrap it in code fences.
```

其中 `{conversation}` 是由 `TokenStatsUtil.formatConversation` 生成的 `role: content` 行文本。

### 5.2 摘要消息结构

摘要生成后包装为：

```text
[AgentForge Context Summary]
{模型返回的摘要正文}

---
Continue the task based on the summary above.
```

固定标识 `SUMMARY_MARKER = "[AgentForge Context Summary]"` 用于识别历史摘要。下一轮压缩时，旧摘要会作为待压缩历史的一部分重新参与合并，而不是简单叠加，从而保证最终只保留一条当前摘要。

### 5.3 Prompt 设计原则

- **固定骨架**：字段始终是同一组，便于识别与合并历史摘要。
- **按需展开**：简单任务字段可以很短，复杂任务在 `Current State` 中展开子任务、依赖和进度。
- **面向续跑**：判断摘要是否合格只看一点——只读摘要，Agent 能否知道做到哪、下一步做什么、有哪些约束。
- **约束明确**：要求只依据给定历史、不推测、不复制大段原始工具输出。

**注意**：摘要输出长度由 `maxSystemRatio` 统一约束；超长时通过 `TokenStatsUtil.truncateText` 按句边界截断并追加 `...[truncated]` 标记。

---

## 六、Token 估算设计

`TokenStatsUtil` 采用启发式估算，不依赖模型厂商 Tokenizer，核心规则如下。

### 6.1 文本估算

```text
宽字符（Unicode >= 0x2E80，覆盖中文及全角字符）：4 units / 字符
ASCII 字符：1 unit / 字符
Token = max(units / 4, 1)
```

即中文约 1 字符 = 1 Token，英文约 4 字符 = 1 Token。

### 6.2 消息固定开销

| 消息类型 | 固定开销 |
| --- | ---: |
| SystemMessage | 10 |
| UserMessage | 5 |
| AiMessage | 15 |
| 每个工具调用请求 | 额外 20 |
| ToolExecutionResultMessage | 20 |

### 6.3 主要工具方法

```java
estimateTextTokens(String text)
estimateMessageTokens(ChatMessage message)
estimateTotalTokens(List<ChatMessage> messages)
calculate(List<ChatMessage> messages, int maxContextTokens)   // 分类统计
needCompress(List<ChatMessage> messages, int maxContextTokens, int threshold)
truncateText(String text, int maxTokens)                     // 优先按句边界截断
formatConversation(List<ChatMessage> messages, int maxTokens)
createTruncatedMessage(ChatMessage original, String content) // 保留原消息类型
```

**重点**：启发式估算应偏保守，即宁可略高估也不要低估。模型响应中的真实 `inputTokens` 可用于监控和校准，但不能直接用于本次调用前判断。

---

## 七、工具结果治理

### 7.1 单工具结果

`afterToolExecution` 流程：

```text
构造 currentMessages = Memory 中已有消息 + 本次 ToolExecutionResultMessage
估算 currentMessages 总 Token
未达阈值：原样返回工具结果
达到阈值：
  ├─ 触发 beforeCompact(-1, TOOL_RESULT, ...)
  ├─ 计算目标结果长度并截断
  ├─ 触发 afterCompact(success)
  └─ 返回截断后的工具结果
异常：触发 afterCompact(failure)，返回原始结果
```

### 7.2 并发工具结果

`afterConcurrentToolExecution` 流程与单工具一致，区别在于：

- 一次性把本轮所有工具结果加入临时消息列表后判断阈值。
- 对每个结果分别调用 `truncateToolResult`。
- 返回的列表长度和顺序与入参严格一致。
- 结果事件中的 `afterTokens` 由“当前 Memory Token + 处理后的各工具结果 Token”估算得出。

### 7.3 截断输出格式

工具结果截断会附加明确标记，告知模型发生了裁剪：

```text
【工具执行结果已截断】
上下文使用率: {currentPercent}% (压缩阈值: {compressThreshold}%)，为避免超出上下文限制，工具 "{toolName}" 的结果已被截断。
原始估算: {resultTokens} tokens，截断后: {targetResultTokens} tokens。
建议：缩小查询范围、增加筛选条件或分页获取。

截断后的内容：
{truncatedContent}
```

**注意**：当前使用文本截断，可能截断结构化 JSON。后续可增加 JSON 感知截断器，优先保留顶层字段、错误信息、分页信息和前若干数据项。

---

## 八、业务层扩展实现

Core 层只负责压缩算法本身，业务层通过 `CompactLifecycleHook` 在不修改 Core 的前提下接入监控、SSE 推送、审计等能力。

### 8.1 扩展点：CompactLifecycleHook

```java
public interface CompactLifecycleHook {

    default void beforeCompact(
            int currentStep,
            CompactTrigger trigger,
            int beforeMessages,
            int beforeTokens,
            int maxContextTokens,
            AgentChatContext chatContext) {}

    default void afterCompact(CompactEvent event, AgentChatContext chatContext) {}
}
```

`CompactSummarizationMiddleware` 同时实现 `IAgentMiddleware` 与 `CompactLifecycleHook`，在压缩前后调用钩子，并对钩子异常做了保护：

```text
钩子抛出异常时仅记录 WARNING，不影响压缩主流程
```

因此业务层可以安全地实现自定义钩子，即使监控或推送失败也不会破坏 Agent 执行。

### 8.2 事件模型

`CompactEvent` 携带一次压缩的完整运行时信息：

| 字段 | 含义 |
| --- | --- |
| `memoryId` | 会话标识 |
| `step` | ReAct 步骤；工具触发时为 `-1` |
| `trigger` | `MODEL_CALL` / `TOOL_RESULT` / `CONCURRENT_TOOL_RESULT` |
| `success` | 是否成功 |
| `durationMs` | 压缩耗时 |
| `beforeMessages` / `afterMessages` | 压缩前后消息数 |
| `beforeTokens` / `afterTokens` | 压缩前后估算 Token |
| `maxContextTokens` | 最大上下文 Token |
| `errorMessage` | 失败原因 |
| `getBeforeUsagePercent()` / `getAfterUsagePercent()` | 计算得到的前后使用率 |

`CompactTrigger` 同时提供 `code` 和中文 `description`：

```text
MODEL_CALL              model_call              模型调用前压缩
TOOL_RESULT             tool_result             单工具结果截断
CONCURRENT_TOOL_RESULT  concurrent_tool_result  并发工具结果截断
```

### 8.3 Service 层适配示例

> Service 侧的完整交互设计（事件协议、参数示例、前端调试/发布展示）见：[Agent 上下文阈值压缩交互设计](../../agentforge-service核心业务模块/后端/agent核心设计实现/Agent页面会话对话交互/Agent%20上下文阈值压缩交互设计.md)。

Service 层通过继承 `CompactSummarizationMiddleware` 并重写两个钩子，把压缩事件转换为 SSE：

```java
public class ServiceCompactSummarizationMiddleware
        extends CompactSummarizationMiddleware {

    private final ServiceStreamingMiddleware streamingMiddleware;
    private final SessionLifecycleMiddleware sessionLifecycleMiddleware;
    private final boolean detailed;

    @Override
    public void beforeCompact(
            int currentStep,
            CompactTrigger trigger,
            int beforeMessages,
            int beforeTokens,
            int maxContextTokens,
            AgentChatContext chatContext) {
        CompactResult result = new CompactResult();
        result.setStatus("compacting");
        result.setTrigger(trigger == null ? null : trigger.getCode());
        if (detailed) {
            result.setCurrentStep(currentStep);
            result.setBeforeMessages(beforeMessages);
            result.setBeforeTokens(beforeTokens);
            result.setMaxContextTokens(maxContextTokens);
            result.setBeforeUsagePercent(...);
            result.setTriggerDescription(trigger.getDescription());
        }
        publish(chatContext, AiCompactProcessor.compacting(result));
    }

    @Override
    public void afterCompact(CompactEvent event, AgentChatContext chatContext) {
        CompactResult result = new CompactResult();
        result.setStatus(event.isSuccess() ? "compacted" : "compact_failed");
        result.setSuccess(event.isSuccess());
        result.setDuration(event.getDurationMs());
        result.setTrigger(event.getTrigger().getCode());
        if (detailed) {
            result.setCurrentStep(event.getStep());
            result.setBeforeMessages(event.getBeforeMessages());
            result.setAfterMessages(event.getAfterMessages());
            result.setBeforeTokens(event.getBeforeTokens());
            result.setAfterTokens(event.getAfterTokens());
            result.setMaxContextTokens(event.getMaxContextTokens());
            result.setBeforeUsagePercent(event.getBeforeUsagePercent());
            result.setAfterUsagePercent(event.getAfterUsagePercent());
            result.setSavedTokens(event.getBeforeTokens() - event.getAfterTokens());
            result.setSavedPercent(...);
            result.setTriggerDescription(event.getTrigger().getDescription());
        }
        publish(chatContext, event.isSuccess()
                ? AiCompactProcessor.compacted(result)
                : AiCompactProcessor.failed(result));
    }

    private void publish(AgentChatContext context, StreamEventResponse response) {
        Object memoryId = context == null ? null : context.getMemoryId();
        streamingMiddleware.publish(memoryId, response);
        if (sessionLifecycleMiddleware != null) {
            sessionLifecycleMiddleware.collectEvent(memoryId, response);
        }
    }
}
```

关键点：

- 业务层不重写 `beforeModelCall` / `afterToolExecution`，只实现钩子，保持 Core 算法稳定。
- `detailed` 由会话是否为调试态决定（`ReActAgentFactory` 中传入 `isDebug`）。
- 事件既推送给实时流（`ServiceStreamingMiddleware`），也交给会话生命周期收集（`SessionLifecycleMiddleware`），保证历史回放可用。

### 8.4 SSE 事件协议

`AiCompactProcessor` 负责把 `CompactResult` 转为统一 `StreamEventResponse`：

| 事件类型 | title | 触发时机 |
| --- | --- | --- |
| `compacting` | 正在压缩上下文 | 压缩开始前 |
| `compacted` | 上下文已压缩 | 压缩成功后 |
| `compact_failed` | 上下文压缩失败 | 压缩失败后 |

调试模式（`detailed = true`）示例：

```text
data:{"event":"compact","type":"compacting","title":"正在压缩上下文","content":{"success":null,"status":"compacting","currentStep":-1,"trigger":"concurrent_tool_result","duration":null,"beforeMessages":9,"afterMessages":null,"beforeTokens":9180,"afterTokens":null,"maxContextTokens":10000,"beforeUsagePercent":91.8,"triggerDescription":"并发工具结果截断","errorMessage":null},"isFinish":false}

data:{"event":"compact","type":"compacted","title":"上下文已压缩","content":{"success":true,"status":"compacted","currentStep":-1,"trigger":"concurrent_tool_result","duration":19,"beforeMessages":9,"afterMessages":9,"beforeTokens":9180,"afterTokens":7263,"maxContextTokens":10000,"beforeUsagePercent":91.8,"afterUsagePercent":72.63,"savedTokens":1917,"savedPercent":20.88,"triggerDescription":"并发工具结果截断","errorMessage":null},"isFinish":false}
```

发布模式（`detailed = false`）只填充状态、触发来源、耗时和失败原因，其余调试字段为 `null`：

```json
{
  "success": true,
  "status": "compacted",
  "currentStep": null,
  "trigger": "concurrent_tool_result",
  "duration": 19,
  "beforeMessages": null,
  "afterMessages": null,
  "beforeTokens": null,
  "afterTokens": null,
  "maxContextTokens": null,
  "beforeUsagePercent": null,
  "afterUsagePercent": null,
  "savedTokens": null,
  "savedPercent": null,
  "triggerDescription": null,
  "errorMessage": null
}
```

**注意**：调试模式额外包含步骤、前后消息数、前后 Token、使用率、节省 Token、触发来源描述；发布模式有意不填充这些字段，前端也不会展示，避免向终端用户泄露上下文规模等信息。若希望进一步精简网络载荷，可为 `CompactResult` 增加 `@JsonInclude(NON_NULL)`。

### 8.5 前端展示约定

- 调试模式：后端会产生 `compacting` 与 `compacted` 两条事件，前端保留为“压缩前上下文”“压缩后上下文”两条记录，Token 统一按 K/M 单位展示，并显示使用率与节省比例。
- 发布模式：前端把两条事件合并为一条状态记录，仅展示状态与耗时。
- 该行为由前端 `reduceCompactEvent(answer, event, detailed)` 的 `detailed` 参数控制。

### 8.6 装配与注册顺序

Service 层在 `ReActAgentFactory` 中构建压缩 Middleware，`maxContextTokens` 来自模型配置：

```java
CompactSummarizationMiddleware compact =
        new ServiceCompactSummarizationMiddleware(
                CompactSummarizationMiddleware.CompactConfig.builder()
                        .maxContextTokens(
                                toActualTokens(
                                        chatModelConfig.getMaxInputKTokens(),
                                        modelAdvancedConfig.getMaxInputTokens()))
                        .build(),
                streamingMiddleware,
                sessionLifecycleMiddleware,
                isDebug);

List<IAgentMiddleware> middlewares = Arrays.asList(
        userMessageAppendMiddleware,
        compact,
        new LoggingIAgentMiddleware(),
        sessionLifecycleMiddleware,
        streamingMiddleware,
        agentTraceMiddleware);
```

建议顺序：

1. 先执行会改变本轮输入的消息追加、动态提示等 Middleware（如 `UserMessageAppendMiddleware`）。
2. 再执行上下文压缩，让它看到最终候选消息。
3. 最后执行 Logging、流式和 Trace，使观测结果对应真正发送给模型的请求。

**注意**：如需同时记录压缩前后指标，不要让压缩 Middleware 依赖两个日志 Middleware；应像 Service 层一样通过 `CompactLifecycleHook` 发布结构化事件。

### 8.7 自定义扩展步骤

业务层接入新能力时，推荐按以下步骤：

1. 继承 `CompactSummarizationMiddleware`，重写 `beforeCompact` / `afterCompact`。
2. 在钩子中读取 `CompactEvent`，转换为业务事件（SSE、消息队列、审计日志、指标等）。
3. 通过构造参数控制输出详略（如 `detailed`），不要把调试细节硬编码进发布链路。
4. 不要吞掉异常后继续执行危险操作；Core 已保证钩子异常不影响压缩。
5. 不要在日志中打印完整用户内容、工具结果或摘要正文；完整内容只允许在明确开启的安全调试环境中输出。

---

## 九、失败策略与可观测性

### 9.1 失败降级

- 摘要生成或 Memory 替换异常：返回原 `ChatRequest`，Memory 回滚，记录 WARNING。
- 工具结果截断异常：返回原工具结果，记录 WARNING。
- 钩子异常：记录 WARNING，不阻断压缩。

降级行为固定在实现中，不进入 `CompactConfig`，确保配置类始终只有五个字段。

### 9.2 指标

每次压缩建议关注：

- `memoryId` 的脱敏值；
- 当前 ReAct step；
- 触发来源；
- 压缩前后消息数与估算 Token；
- 压缩前后使用率；
- 节省 Token 与节省比例（调试态）；
- 摘要模型耗时；
- 成功、降级或失败原因。

**注意**：不要在 INFO 日志打印完整用户内容、工具结果或摘要正文。

---

## 十、当前实现边界与后续演进

为避免文档与代码脱节，以下列出当前实现的已知边界：

| 边界 | 当前行为 | 后续建议 |
| --- | --- | --- |
| 并发控制 | 未实现会话级互斥或 CAS，`replaceMemory` 仅做本地回滚 | 为 `ChatMemory` 增加原子 `replaceAll` 或基于版本的 compare-and-set |
| 摘要模型 | 复用 `AgentChatContext.getChatModel()` | 支持独立摘要模型与独立超时配置 |
| Token 估算 | 启发式，中文按宽字符、英文按 4 字符 | 接入模型厂商 Tokenizer |
| Prompt 语言 | 仅内置英文版 | 按会话语言选择中英文 Prompt |
| 压缩轮次 | 单次压缩，不循环收敛 | 增加“仍超阈值”的二次收缩策略 |
| 协议校验 | 未对压缩结果做额外校验 | 增加工具调用—结果配对校验 |
| 工具结果截断 | 纯文本截断，可能破坏 JSON | 增加 JSON、表格、代码感知截断器 |
| 配置来源 | Core 只提供 Java 配置对象 | Service 层按页面/YAML 配置转换 |

**重点**：以上边界大多属于生产化增强项，不影响当前“阈值比例压缩”主流程的正确性。

---

## 十一、测试

当前已有针对钩子行为的单元测试，覆盖工具结果截断触发时：

- `beforeCompact` 被调用且收到正确的触发来源、消息数、Token、最大上下文；
- `afterCompact` 收到成功事件；
- 工具结果包含截断标记。

建议继续补充：

- `TokenStatsUtil`：空文本、中文、英文、混合文本、消息固定开销、截断边界。
- `CompactSummarizationMiddleware`：
  - 未达阈值不调用摘要模型；
  - 达到阈值保留系统提示与最近消息；
  - 旧摘要合并而非重复堆叠；
  - 重建请求完整保留 `parameters()`；
  - 摘要失败时 Memory 不被清空；
  - 单个超大工具组的处理行为；
  - 并发工具结果返回顺序与长度一致。
- 集成测试：
  - 同步与流式链路结果一致；
  - 与消息追加 Middleware 组合时顺序正确；
  - 历史回放时压缩事件可还原。

---

## 十二、分阶段落地计划

### 12.1 已落地

- `CompactSummarizationMiddleware` + `CompactConfig` 五项配置。
- `TokenStatsUtil` 启发式估算、分类统计、截断与格式化。
- `beforeModelCall` 历史摘要与请求重建。
- 单工具、并发工具结果截断。
- `CompactLifecycleHook`、`CompactEvent`、`CompactTrigger`。
- Service 层 `ServiceCompactSummarizationMiddleware` 与 SSE 事件。

### 12.2 第二阶段：一致性与生产化

- `ChatMemory` 原子批量替换与会话版本控制。
- 结构化指标接入 Trace。
- Service 层配置化开关与参数校验。

### 12.3 第三阶段：精确与智能治理

- 按模型注入精确 Tokenizer。
- 独立摘要模型与超时控制。
- JSON / 表格 / 代码感知的工具结果截断。
- 关键消息固定、摘要质量评估、长期记忆下沉。

---

## 十三、总结

AgentForge 的上下文压缩能力以 `CompactSummarizationMiddleware` 为核心：

- 使用 `beforeModelCall` 完成阈值检测、历史摘要、Memory 替换与 Request 重建。
- 使用 `afterToolExecution`、`afterConcurrentToolExecution` 治理单个及并发工具结果。
- 通过 `CompactLifecycleHook` 向业务层暴露压缩前后事件，Service 层无需修改 Core 即可推送 SSE、记录审计或接入监控。

最终上下文保持三层结构：

```text
不可压缩的系统提示
        +
可迭代更新的历史摘要
        +
按 Token 预算保留的最近完整消息组
```

该方案不侵入 ReAct 主循环，兼容 AgentForge 现有 Middleware 链、同步/流式执行与 ChatMemory 抽象，同时为精确 Tokenizer、独立摘要模型、持久化一致性与可观测性留出了清晰扩展点。

---

整理者：长路 创建时间：2026.11.4 更新时间：2026.11.6

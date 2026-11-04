# AgentForge 上下文压缩 Middleware 设计方案：阈值比例压缩机制

## 1. 文档定位

本文给出 AgentForge 内置上下文压缩 Middleware 的集成设计，目标是在不修改 ReAct 主循环的前提下，为长会话和多轮工具调用提供可控的上下文压缩能力。

本文是设计方案，不代表下文列出的类已经全部实现。当前 AgentForge 已具备 Middleware 生命周期、可改写的模型请求、可改写的工具结果、ChatMemory 和 ChatModel 等基础能力。本次集成以复刻阈值比例压缩机制为目标，不额外设计复杂配置体系。

## 2. 背景与问题

ReAct Agent 每一轮都会把会话记忆作为模型输入。随着用户消息、模型回复、工具调用和工具结果持续累积，上下文会出现三个问题：

1. 请求 Token 数逼近模型上下文上限，最终导致模型请求失败。
2. 大体积工具结果快速挤占窗口，模型输出和后续工具调用缺少空间。
3. 简单按消息条数淘汰历史会丢失长期目标、关键结论，并可能拆散“工具调用—工具结果”协议消息。

AgentForge 当前的 `MessageWindowChatMemory` 以消息条数控制容量，适合解决无界消息增长，但不能感知不同消息的 Token 差异。例如，一条工具结果可能比几十条短消息更大。因此需要在消息窗口之上增加 Token 维度的治理能力。

本方案采用“达到阈值后压缩历史，保留最近上下文”的机制：

```text
系统提示 + 历史消息 + 最近消息
                │
                │ Token 使用率达到阈值
                ▼
系统提示 + 历史摘要 + 最近完整消息组
```

## 3. 设计目标

### 3.1 核心目标

- 在模型调用前估算本轮输入规模，并在超过阈值时自动压缩。
- 永久保留 Agent 系统提示，不把系统规则混入普通历史摘要。
- 优先保留最近对话，旧历史转换为结构化摘要。
- 工具调用消息和对应工具结果作为原子消息组处理，避免产生孤儿消息。
- 在工具结果写入 Memory 前治理超大结果，防止单次工具调用击穿窗口。
- 同步和流式 ReAct 执行复用同一套 Middleware，不修改两套执行循环。
- 压缩失败时降级为原请求，不能让辅助能力阻断 Agent 主任务。

### 3.2 非目标

- 不保证摘要能够无损恢复全部历史原文。
- 不替代业务侧的长期记忆、向量检索或知识库存储。
- 不修改底层模型协议，也不绑定特定模型厂商的 Tokenizer。
- 第一阶段不自动根据模型名称推断上下文窗口，由调用方显式配置。

## 4. AgentForge 当前能力与集成结论

### 4.1 已具备的基础能力

当前代码已经提供本方案所需的主要扩展边界：

| 现有能力 | 代码位置 | 在压缩方案中的作用 |
| --- | --- | --- |
| `IAgentMiddleware.beforeModelCall` | `agentforge-agent-core/.../component/middleware/IAgentMiddleware.java` | 检测阈值、压缩历史、重建 `ChatRequest` |
| `afterToolExecution` | 同上 | 单个工具结果写回前执行截断或治理 |
| `afterConcurrentToolExecution` | 同上 | 并发工具结果统一分配预算并改写结果 |
| `AgentMiddlewareManager` | `agentforge-agent-core/.../component/middleware/AgentMiddlewareManager.java` | 按注册顺序串联请求和结果改写 |
| `AgentChatContext` | `agentforge-agent-core/.../domain/AgentChatContext.java` | 提供 ChatMemory、ChatModel、memoryId 和运行期扩展字段 |
| `ChatMemory` | `agentforge-agent-core/.../memory/ChatMemory.java` | 读取和替换本会话消息 |
| `MessageWindowChatMemory` | `agentforge-agent-core/.../extend/memory/window/MessageWindowChatMemory.java` | 消息条数兜底，并已有工具消息关联淘汰语义 |
| `ReActAgent` | `agentforge-agent-core/.../ReActAgent.java` | 模型调用前已经经过 Middleware 链 |

### 4.2 集成结论

上下文压缩应作为 `agentforge-agent-core` 的内置 Middleware 实现，而不是写入 `ReActAgent.think()`。原因如下：

- 压缩是横切能力，不属于 ReAct 的 think → act 核心语义。
- `beforeModelCall` 可以同时访问最终请求、Memory 和 ChatModel。
- Middleware 返回的新 `ChatRequest` 会被同步和流式链路实际使用。
- 工具执行后的回调允许在结果写回 Memory 前处理大结果。
- 通过 Builder 注册即可按 Agent 开启或关闭，保持核心运行时稳定。

建议新增目录：

```text
agentforge-agent-core/src/main/java/cloud/changlu/agentforge/agent/extend/middlewares/compact/
├── CompactSummarizationMiddleware.java
└── TokenStatsUtil.java
```

`CompactSummarizationMiddleware` 负责阈值检测、消息切分、摘要生成、Memory 更新和工具结果截断；`CompactConfig` 作为其 `public static` 内部类存在。`TokenStatsUtil` 负责 Token 估算、统计、文本截断和会话格式化。首版不再拆分更多接口，优先保持实现直接、行为一致。

## 5. 整体执行流程

### 5.1 模型调用前压缩

```text
ReActAgent.think
  │
  ├─ 从 ChatMemory 构建 ChatRequest
  │
  ├─ AgentMiddlewareManager.triggerBeforeModelCall
  │    │
  │    └─ CompactSummarizationMiddleware
  │         ├─ 读取 request.messages
  │         ├─ 估算输入 Token
  │         ├─ 未达到阈值：原样返回
  │         ├─ 达到阈值：按消息组切分历史与最近窗口
  │         ├─ 调用摘要模型压缩历史
  │         ├─ 原子替换 ChatMemory
  │         └─ 保留 parameters，重建 ChatRequest
  │
  └─ ChatModel.chat(processedRequest)
```

这里应以 `ChatRequest.messages()` 作为阈值判断的主要输入，因为它代表本轮真正准备送给模型的消息。Memory 用于持久化压缩结果，两者必须在压缩成功后保持一致。

### 5.2 工具结果治理

```text
工具执行完成
  │
  ├─ 单工具：afterToolExecution
  └─ 并发工具：afterConcurrentToolExecution
       │
       ├─ 估算“当前 Memory + 待写入工具结果”
       ├─ 在安全预算内：结果原样返回
       └─ 超预算：按结果预算截断，并附加明确截断标记
```

模型调用前压缩解决“历史累积”问题；工具结果治理解决“单次突增”问题。两者缺一不可，否则一个超大工具结果可能在下一次压缩发生前就让 Memory 膨胀，或让下一轮请求没有足够余量。

## 6. 阈值比例模型

### 6.1 配置项

本次复刻只保留以下五个配置字段，比例统一使用 0～100 的整数百分比：

| 配置项 | 建议默认值 | 含义 |
| --- | ---: | --- |
| `maxContextTokens` | `16000` | 目标模型最大上下文 Token 数 |
| `compressThreshold` | `80` | Token 使用率达到该百分比时触发压缩 |
| `keepRatio` | `40` | 压缩时为最近消息保留的 Token 比例 |
| `maxSystemRatio` | `30` | 摘要输入及摘要结果允许使用的最大 Token 比例 |
| `maxOtherRatio` | `50` | 待摘要的 AI、工具等其他消息允许使用的最大 Token 比例 |

对应配置结构固定为：

```java
public static class CompactConfig {
    private final int maxContextTokens;
    private final int compressThreshold;
    private final int keepRatio;
    private final int maxSystemRatio;
    private final int maxOtherRatio;
}
```

核心预算计算如下：

```text
compressThresholdTokens = maxContextTokens × compressThreshold / 100
keepTokens              = maxContextTokens × keepRatio / 100
maxSystemTokens         = maxContextTokens × maxSystemRatio / 100
maxOtherTokens          = maxContextTokens × maxOtherRatio / 100
```

其中 `compressThreshold` 决定“何时压缩”，`keepRatio` 决定“最近原始消息保留多少”，`maxSystemRatio` 和 `maxOtherRatio` 用于限制摘要阶段各部分的输入及输出规模。

### 6.2 配置约束

构建配置时至少校验：

```text
maxContextTokens > 0
0 <= compressThreshold <= 100
10 <= keepRatio <= 100
0 <= maxSystemRatio <= 100
0 <= maxOtherRatio <= 100
maxSystemRatio + maxOtherRatio <= 100
```

### 6.3 为什么压缩后不能仍停在触发线附近

如果 80% 触发压缩，压缩后仍占 78%，下一轮稍有工具输出就会再次压缩，形成压缩抖动。默认使用 40% 的 `keepRatio` 保留最近消息，再通过 `maxSystemRatio` 控制摘要长度，使压缩后规模明显低于触发线：

```text
80% 触发
40% 最近消息
最多 30% 摘要
压缩后通常低于触发阈值
```

## 7. 消息分区与协议完整性

### 7.1 消息分区

压缩前先把消息划分为三类：

1. **固定消息**：Agent 系统提示，始终原文保留。
2. **可压缩历史**：较早的用户、模型和工具消息，用于生成摘要。
3. **最近消息**：从后向前按预算选择，保持原文。

压缩后的顺序固定为：

```text
[SystemMessage: Agent 原始系统提示]
[SystemMessage: 历史对话摘要]
[最近完整消息组...]
```

历史摘要建议使用带稳定标识的 `SystemMessage`，例如：

```text
【AgentForge 历史上下文摘要】
目标：...
已确认事实：...
重要决策：...
工具执行结论：...
待办与约束：...
```

稳定标识用于下一次压缩时识别旧摘要。旧摘要不能被当成 Agent 原始系统提示永久叠加；它应与更老的历史一起参与下一轮摘要合并，最终始终只保留一条当前摘要。

### 7.2 工具消息必须原子分组

ReAct 工具协议通常是：

```text
AiMessage(toolExecutionRequests=[A, B])
ToolExecutionResultMessage(A)
ToolExecutionResultMessage(B)
```

这三条消息必须作为一个组保留或压缩。不能只保留结果而删除发起调用的 AI 消息，也不能保留调用却删除部分结果。否则模型 API 可能拒绝消息序列，或者模型无法理解工具结果对应关系。

`MessageGroupPartitioner` 建议采用以下规则：

- 普通 System、User、无工具调用的 AiMessage 各自为单组。
- 带工具请求的 AiMessage 与紧随其后、ID 匹配的全部 ToolExecutionResultMessage 合为一组。
- 遇到不完整工具组时记录告警，并把整个异常段放入可压缩历史，不让它进入最近原文窗口。
- 从尾部选择最近消息时按组累计 Token，不能按单条消息切割。

### 7.3 当前用户消息无需重复构造

AgentForge 的 `Agent.initMessages` 已在运行开始时把当前 `UserMessage` 写入 Memory，`ReActAgent.think()` 又从 Memory 构建本轮 `ChatRequest`。因此压缩 Middleware 应直接使用请求中的当前用户消息，不应根据 `AgentChatContext.getQuestion()` 再创建一条，否则会产生重复问题。

这一点是当前项目集成时最容易出现的语义错误。

## 8. Token 估算设计

### 8.1 TokenStatsUtil

首版直接提供 `TokenStatsUtil`，不增加估算器接口。工具类至少包含：

```java
estimateTextTokens(String text)
estimateMessageTokens(ChatMessage message)
estimateTotalTokens(List<ChatMessage> messages)
needCompress(List<ChatMessage> messages, int maxContextTokens, int threshold)
truncateText(String text, int maxTokens)
formatConversation(List<ChatMessage> messages, int maxTokens)
```

这样可保持压缩实现集中，后续若需要精确 Tokenizer，再在不改变五项配置的前提下替换工具类内部算法。

### 8.2 启发式估算注意点

单纯采用“字符数 ÷ 4”对英文较常见，但对中文会明显低估。因此建议按字符类型分别估算：

```text
中文及全角字符：约 1 字符 = 1 Token
英文、数字、半角符号：约 4 字符 = 1 Token
每条消息：增加角色和协议固定开销
每个工具调用：统计名称、ID、参数 JSON 和固定开销
工具定义：统计名称、描述和参数 Schema
```

启发式估算应偏保守，即宁可略高估也不要低估。生产环境若模型适配层能提供官方 Tokenizer，应优先注入精确实现。

模型响应中的 `TokenUsage.inputTokens()` 是调用完成后的真实值，可用于监控和校准估算器，但不能直接用于本次调用前判断，因为它描述的是已经完成的上一请求。

## 9. 摘要生成设计

### 9.1 摘要生成方法

首版不额外抽象摘要器接口，直接在 `CompactSummarizationMiddleware` 中提供 `generateSummary` 私有方法，并复用 `AgentChatContext.getChatModel()` 发起摘要请求：

```java
private ChatMessage generateSummary(
        List<ChatMessage> messages,
        AgentChatContext chatContext) {
    // 按 maxSystemRatio、maxOtherRatio 截断摘要输入
    // 调用 chatContext.getChatModel() 生成摘要
    // 按 maxSystemRatio 限制摘要输出
}
```

### 9.2 摘要提示词

摘要应面向 Agent 后续执行，而不是写成普通聊天概述。建议要求模型保留：

- 用户目标、范围和成功条件；
- 已确认事实、关键实体、参数和标识；
- 已完成操作及结果；
- 重要决策及理由；
- 尚未解决的问题和后续步骤；
- 用户明确提出的约束、偏好和禁止项；
- 工具调用的结论，不复制大段原始工具输出；
- 不推测、不补充历史中没有的信息。

摘要输出长度仍需由 `TokenStatsUtil` 校验。超过 `maxContextTokens × maxSystemRatio / 100` 时，在语义边界进行二次截断，并附加“摘要已裁剪”标记。

### 9.3 防递归与并发

摘要调用必须直接调用 `ChatModel`，不能再次走当前 `ReActAgent`，否则会递归触发 Middleware。

同一个 `memoryId` 可能出现并发请求。压缩是“读取—摘要—替换”的复合操作，需要会话级互斥或版本校验。建议第一阶段在 Middleware 内使用按 `memoryId` 分段的锁；长期方案是在 `ChatMemory` 增加原子 `replaceAll` 或基于版本的 compare-and-set 能力。

不要在持有会话锁时执行长时间摘要调用。推荐流程是：

1. 加锁读取消息快照和版本后释放锁。
2. 基于快照生成摘要。
3. 再次加锁，确认 Memory 未发生变化。
4. 未变化则替换；已变化则放弃本次结果或重新计算一次。

当前 `ChatMemory` 只有 `clear()` 和 `add()`，无法保证通用实现上的原子替换。第一阶段可在压缩 Middleware 内封装 `replaceMemory`，但正式落地建议给 `ChatMemory` 增加默认方法：

```java
default void replaceAll(List<ChatMessage> messages) {
    clear();
    for (ChatMessage message : messages) {
        add(message);
    }
}
```

并由 `MessageWindowChatMemory` 覆盖为一次 `store.updateMessages`，保证单次持久化和同步语义。

## 10. 核心算法

模型调用前的伪代码如下：

```java
public ChatRequest beforeModelCall(
        int currentStep,
        ChatRequest request,
        AgentChatContext context) {

    List<ChatMessage> messages = request.messages();
    if (!TokenStatsUtil.needCompress(
            messages,
            config.getMaxContextTokens(),
            config.getCompressThreshold())) {
        return request;
    }

    int keepTokens = config.getMaxContextTokens()
            * config.getKeepRatio() / 100;
    Partition partition = selectRecentMessageGroups(messages, keepTokens);

    if (partition.history().isEmpty()) {
        return request;
    }

    try {
        ChatMessage summary = generateSummary(
                partition.historyMessages(), context);

        List<ChatMessage> compacted = assemble(
                partition.fixedSystemMessages(),
                summary,
                partition.recentMessages());

        validateProtocol(compacted);
        context.getChatMemory().replaceAll(compacted);

        return ChatRequest.builder()
                .messages(compacted)
                .parameters(request.parameters())
                .build();
    } catch (Exception exception) {
        recordFailure(context, currentStep, exception);
        return request;
    }
}
```

关键要求：

- 重建请求时必须保留原 `parameters()`，否则工具定义、温度等参数会丢失。
- 先生成并验证完整压缩结果，再替换 Memory，不能先清空后摘要。
- 压缩后再次估算并记录压缩前后的 Token 使用率；首版不增加多轮压缩配置。
- 如果最近的单个工具消息组本身超过预算，不应拆组；应优先对该组中的工具结果做有标记截断，再重新估算。
- 如果连固定系统提示加当前用户消息都超过安全上限，Middleware 无法安全压缩，应抛出明确的上下文过大异常或按配置中断模型调用。

## 11. 工具结果截断策略

工具结果截断不能只做字符串切片。建议输出格式明确告知模型发生了裁剪：

```text
【工具结果已截断】
工具：searchDocuments
原因：上下文预算不足
原始估算：6200 tokens
保留估算：1200 tokens
建议：缩小查询范围、增加筛选条件或分页获取

<截断后的内容>
```

单工具结果预算：

```text
targetTotalTokens = maxContextTokens × compressThreshold / 100
needReduce        = currentTotalTokens - targetTotalTokens
targetResultTokens = max(resultTokens - needReduce, 100)
```

并发工具结果不能让每个结果各自占满 `available`。应先计算本批次总预算，再分配给每个结果：

首版并发工具结果按照相同阈值判断，对每个结果执行同一截断函数，并保证返回列表与工具请求列表长度、顺序一致。最少保留 100 Token，该常量属于实现细节，不进入 `CompactConfig`。

结构化 JSON 的截断应尽量保持合法 JSON。第一阶段可以使用文本截断并明确标记；后续增加 JSON 感知截断器，优先保留顶层字段、错误信息、分页信息和前若干数据项。

## 12. Middleware 注册顺序

Middleware 是顺序链式执行，压缩 Middleware 的位置会影响观测结果：

```java
ReActAgent agent = ReActAgent.builder()
        .chatModel(chatModel)
        .chatMemoryProvider(memoryProvider)
        .middleware(new UserMessageAppendMiddleware(appendService, listener))
        .middleware(new CompactSummarizationMiddleware(compactConfig))
        .middleware(new LoggingIAgentMiddleware())
        .middleware(new AgentTraceMiddleware(traceCollector))
        .build();
```

建议顺序：

1. 先执行会改变本轮输入的消息追加、动态提示等 Middleware。
2. 再执行上下文压缩，让它看到最终候选消息。
3. 最后执行 Logging 和 Trace，使观测结果对应真正发送给模型的请求。

如果希望同时记录压缩前后的指标，不应依赖放置两个日志 Middleware；应由压缩 Middleware 自己发布 `CompactMetrics`，包含压缩前后 Token、消息数、耗时和失败原因。

## 13. 配置与使用示例

建议 API：

```java
CompactConfig compactConfig = CompactConfig.builder()
        .maxContextTokens(32_000)
        .compressThreshold(80)
        .keepRatio(40)
        .maxSystemRatio(30)
        .maxOtherRatio(50)
        .build();

ReActAgent agent = ReActAgent.builder()
        .agentName("long-context-agent")
        .chatModel(chatModel)
        .chatMemoryProvider(memoryProvider)
        .middleware(new CompactSummarizationMiddleware(compactConfig))
        .build();
```

Core 层只提供 Java 配置对象，不直接依赖 Spring。Service 层如需页面化配置，可在自身模块把数据库或 YAML 配置转换为 `CompactConfig`，再由 `ReActAgentFactory` 按开关注册 Middleware。

## 14. 失败策略

复刻版本不增加失败策略配置。摘要生成或压缩过程抛出异常时，Middleware 记录错误并返回原 `ChatRequest`；只有在完整压缩结果生成成功后才能更新 Memory。工具结果截断失败时返回原工具结果并记录告警。

该降级行为固定在实现中，不进入 `CompactConfig`，从而确保配置类始终只有五个字段。

## 15. 可观测性

每次触发压缩建议记录以下指标：

- `memoryId` 的脱敏值；
- 当前 ReAct step；
- 触发原因：历史阈值、单工具结果、并发工具结果；
- 压缩前后消息数和估算 Token；
- 历史、摘要、最近消息各自 Token；
- 摘要模型耗时；
- 压缩轮次；
- 是否截断工具结果；
- 成功、降级或失败原因。

不要在 INFO 日志打印完整用户内容、工具结果或摘要正文。完整内容只允许在明确开启的安全调试环境中输出。

运行期可把本次指标放入 `AgentChatContext.extensions`：

```text
agentforge.compact.lastResult
agentforge.compact.totalCount
agentforge.compact.failureCount
```

Trace Middleware 可选择读取这些字段，将压缩事件纳入执行快照，但压缩 Middleware 不直接依赖 Trace 实现。

## 16. 测试方案

### 16.1 单元测试

`TokenStatsUtilTest`：

- 空文本、中文、英文、混合文本；
- 工具参数 JSON 和工具 Schema；
- 各类消息固定开销；
- 估算结果单调增长且不溢出。

`MessageGroupPartitionerTest`：

- 普通消息分组；
- 单工具调用及结果；
- 同轮多个工具调用及结果；
- 缺失结果、结果 ID 不匹配；
- 从尾部选取时不会拆散工具组。

`CompactSummarizationMiddlewareTest`：

- 未达到阈值时返回原请求且不调用摘要模型；
- 达到阈值后保留系统提示和最近消息；
- 旧摘要会合并而不是重复堆叠；
- 重建请求完整保留 `parameters()`；
- 压缩成功后 Request 与 Memory 一致；
- 摘要失败时 Memory 不被清空；
- 压缩后仍过大时执行二次收缩；
- 单个超大消息组的降级行为。

`ToolResultCompactionTest`：

- 单工具结果不超限时原样返回；
- 超限时带截断标记；
- 并发结果共享总预算；
- 返回列表长度和顺序与请求严格一致；
- Unicode 和 JSON 内容截断不产生字符串异常。

### 16.2 集成测试

- 使用脚本模型构造多轮 think → act，验证同步链路。
- 使用流式脚本模型验证流式链路与同步链路结果一致。
- 与 `UserMessageAppendMiddleware` 组合，验证追加消息先进入再压缩。
- 与 Logging、Trace 组合，验证注册顺序和指标。
- 使用持久化 `ChatMemoryStore`，验证压缩后重新加载会话仍只有一份摘要。
- 模拟同一 `memoryId` 并发压缩，验证不会覆盖新消息。

## 17. 分阶段落地计划

### 第一阶段：复刻核心能力

- 新增带静态内部类 `CompactConfig` 的 `CompactSummarizationMiddleware`。
- 配置严格限定为五项：`maxContextTokens`、`compressThreshold`、`keepRatio`、`maxSystemRatio`、`maxOtherRatio`。
- 新增 `TokenStatsUtil`，实现启发式 Token 估算、统计和截断。
- 在 Middleware 内实现工具协议消息分组。
- 实现 `beforeModelCall` 历史摘要与请求重建。
- 实现单工具和并发工具结果文本截断。
- 增加单元测试和同步/流式集成测试。

### 第二阶段：一致性与生产化

- 为 `ChatMemory` 增加原子批量替换能力。
- 增加会话版本或 compare-and-set，解决跨实例并发覆盖。
- 发布结构化 `CompactMetrics` 并接入 Trace。
- Service 层增加配置开关和参数校验。

### 第三阶段：精确与智能治理

- 按模型注入精确 Tokenizer。
- 统计工具定义的真实协议 Token。
- 增加 JSON、表格和代码感知的工具结果截断器。
- 支持关键消息固定、摘要质量评估和长期记忆下沉。

## 18. 关键设计取舍

### 18.1 为什么同时更新 Request 和 Memory

只改 Request 可以让当前调用成功，但下一轮又会从未压缩的 Memory 构建请求，造成重复压缩；只改 Memory 则当前已经构建好的 Request 仍携带旧消息。因此压缩成功后必须同步更新两者。

### 18.2 为什么不能只依赖 MessageWindowChatMemory

消息条数与 Token 数没有稳定关系，窗口无法识别一个超大工具结果，也无法把被淘汰的长期历史转换为摘要。两者职责不同：消息窗口做硬数量兜底，压缩 Middleware 做 Token 预算和语义保留。

### 18.3 为什么不把压缩放进 ChatMemory

摘要需要调用模型，并依赖当前模型、运行上下文、配置和观测信息。如果直接放进 Memory，会让存储抽象承担模型调用和业务策略，破坏职责边界。Memory 只负责状态，Middleware 负责何时以及如何压缩。

### 18.4 为什么工具结果要提前治理

工具结果是在一次模型调用之后、下一次模型调用之前进入上下文的。提前改写可以避免超大结果污染 Memory，并让同步和流式执行自然复用同一治理逻辑。

## 19. 最终方案

AgentForge 的上下文压缩能力应以 `CompactSummarizationMiddleware` 为入口，使用 `beforeModelCall` 完成阈值检测、历史摘要、Memory 替换和 Request 重建，使用工具执行后的两个回调治理单个及并发工具结果。

最终上下文保持三层结构：

```text
不可压缩的系统提示
        +
可迭代更新的历史摘要
        +
按 Token 预算保留的最近完整消息组
```

该方案不侵入 ReAct 主循环，兼容 AgentForge 现有 Middleware 链、同步/流式执行和 ChatMemory 抽象，同时为后续精确 Tokenizer、独立摘要模型、持久化一致性及可观测性留出了清晰扩展点。

---
title: 上下文阈值压缩 Middleware
sidebar_label: 上下文阈值压缩 Middleware
description: 长会话上下文治理：阈值触发、历史摘要、工具结果截断与压缩事件扩展
sidebar_position: 4
displayed_sidebar: sdkSidebar
---

# 上下文阈值压缩 Middleware

`CompactSummarizationMiddleware` 解决长会话中的上下文膨胀问题：当请求 Token 使用率达到阈值时，自动把较早历史压缩为一条结构化摘要，只保留最近消息原文；超大工具结果则在写回 Memory 前先截断。

```text
压缩前：系统提示 + 较早历史 + 最近对话
压缩后：系统提示 + 历史摘要 + 最近对话
```

它不修改 ReAct 主循环，而是通过 Middleware 生命周期完成治理：`beforeModelCall` 负责历史压缩，两个工具结果回调负责单次突增。

## 1. 适用场景

| 场景 | 说明 |
| --- | --- |
| 长会话 | 多轮用户消息、模型回复持续累积，Token 逼近模型上限 |
| 多工具调用 | 工具结果体积差异大，一条结果可能挤占大量窗口 |
| 成本敏感 | 需要在保留任务状态的前提下降低输入 Token |
| 服务端 Agent | 需要统一控制上下文规模，避免请求失败 |

不适合：

- 需要完整保留所有历史原文的审计场景；
- 长期记忆、向量检索或知识库存储；
- 单轮短对话或上下文远低于模型窗口的场景。

## 2. 核心压缩算法机制

### 2.1 在哪些环节执行

压缩检查只放在两个进入点：

| 时机 | 方法 | 处理对象 | 触发来源 |
| --- | --- | --- | --- |
| 模型调用前 | `beforeModelCall` | 本轮最终 `ChatRequest.messages()` | `model_call` |
| 单工具结果写回前 | `afterToolExecution` | 当前 Memory + 本次工具结果 | `tool_result` |
| 并发工具结果写回前 | `afterConcurrentToolExecution` | 当前 Memory + 本轮全部工具结果 | `concurrent_tool_result` |

```text
模型调用前检查     → 解决历史累积
工具结果写回前检查 → 解决单次突增
```

不会在以下位置执行压缩：

- 不修改 `ReActAgent` 的 think → act 主循环；
- 不在 `ChatMemory` 读写时隐式压缩；
- 不在流式 token 回调中逐 token 判断。

### 2.2 阈值判定

```text
总 Token = 所有消息估算 Token 之和
使用率   = 总 Token / maxContextTokens × 100
触发条件 = 使用率 >= compressThreshold
```

即达到阈值（含等于）就触发。工具结果触发时会先判断是否超阈值，再把结果截断回阈值预算以内。

### 2.3 核心预算公式

```text
触发压缩 Token = maxContextTokens × compressThreshold / 100
最近消息预算   = maxContextTokens × keepRatio / 100
摘要预算       = maxContextTokens × maxSystemRatio / 100
其他消息预算   = maxContextTokens × maxOtherRatio / 100
工具截断目标   = maxContextTokens × compressThreshold / 100
```

### 2.4 压缩流程

```text
1. 分离系统消息与普通消息
2. 按工具调用分组：AiMessage + 后续 ToolExecutionResultMessage 视为原子组
3. 从尾部向前选择最近消息组，最多保留 keepRatio 预算
4. 较早历史按 maxSystemRatio / maxOtherRatio 裁剪后交给当前 ChatModel 生成摘要
5. 重建：系统提示 + 历史摘要 + 最近消息组
6. 同步更新 ChatMemory 与本轮 ChatRequest
7. 摘要失败则返回原请求，Memory 回滚，不阻断主任务
```

关键约定：

- 系统提示永久保留，不混入历史摘要；
- 工具调用与其结果必须整组保留或整组压缩，不能拆散；
- 最后一组最近消息无条件保留，保证当前轮次上下文完整；
- 历史摘要使用固定标识 `[AgentForge Context Summary]`，下一轮会与更早历史重新合并，而不是层层叠加；
- 工具结果最小保留 `100` Token，避免截断到不可用。

## 3. 参数配置

`CompactConfig` 只有五个参数：

| 参数 | 默认值 | 范围 | 含义 |
| --- | ---: | --- | --- |
| `maxContextTokens` | `1000000` | `> 0` | 最大上下文 Token，所有比例预算的基数 |
| `compressThreshold` | `80` | `0 ~ 100` | 使用率达到多少时触发压缩 |
| `keepRatio` | `40` | `10 ~ 100` | 压缩时最多保留最近原文的比例 |
| `maxSystemRatio` | `30` | `0 ~ 100` | 摘要输入中系统类内容及摘要输出的上限 |
| `maxOtherRatio` | `50` | `0 ~ 100` | AI 回复、工具结果等其他摘要内容的上限 |

约束：`maxSystemRatio + maxOtherRatio <= 100`，否则构建配置时抛出 `IllegalArgumentException`。

## 4. SDK 对接使用

### 4.1 添加依赖

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-agent-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 4.2 构建配置并注册

```java
import cloud.changlu.agentforge.agent.extend.middlewares.compact.CompactSummarizationMiddleware;
import cloud.changlu.agentforge.agent.extend.middlewares.compact.CompactSummarizationMiddleware.CompactConfig;

CompactConfig compactConfig = CompactConfig.builder()
        .maxContextTokens(128_000)
        .compressThreshold(80)
        .keepRatio(40)
        .maxSystemRatio(30)
        .maxOtherRatio(50)
        .build();

ReActAgent agent = ReActAgent.builder()
        .agentName("long-context-agent")
        .chatModel(chatModel)
        .streamingChatModel(streamingChatModel)
        .chatMemoryProvider(new MessageWindowChatMemoryProvider(100))
        .toolService(toolService)
        .middleware(new CompactSummarizationMiddleware(compactConfig))
        .build();
```

`maxContextTokens` 应与目标模型的输入窗口一致。生产环境通常从模型配置转换：

```java
CompactConfig config = CompactConfig.builder()
        .maxContextTokens(modelConfig.getMaxInputTokens())
        .build();
```

### 4.3 注册顺序

建议顺序：

1. 先注册会改变本轮输入的消息追加、动态提示等 Middleware；
2. 再注册上下文压缩，让它看到最终候选消息；
3. 最后注册日志、流式和 Trace，使观测结果对应真正发送给模型的请求。

```java
.middlewares(Arrays.asList(
        new UserMessageAppendMiddleware(appendService, listener),
        new CompactSummarizationMiddleware(compactConfig),
        new LoggingIAgentMiddleware(),
        new AgentTraceMiddleware(traceCollector)))
```

## 5. 扩展压缩交互事件

Core 通过 `CompactLifecycleHook` 暴露压缩前后事件。`CompactSummarizationMiddleware` 同时实现了该接口，业务层可以继承并重写钩子，把压缩过程接入 SSE、审计、监控或消息队列。

### 5.1 生命周期钩子

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

钩子异常由 Core 捕获并记录 WARNING，不会影响压缩主流程。

### 5.2 CompactEvent 事件内容

| 字段 | 说明 |
| --- | --- |
| `memoryId` | 会话标识 |
| `step` | ReAct 步骤；工具触发时为 `-1` |
| `trigger` | `MODEL_CALL` / `TOOL_RESULT` / `CONCURRENT_TOOL_RESULT` |
| `success` | 是否成功 |
| `durationMs` | 压缩耗时 |
| `beforeMessages` / `afterMessages` | 前后消息数 |
| `beforeTokens` / `afterTokens` | 前后估算 Token |
| `maxContextTokens` | 最大上下文 Token |
| `errorMessage` | 失败原因 |
| `getBeforeUsagePercent()` / `getAfterUsagePercent()` | 前后使用率 |

`CompactTrigger` 提供 `getCode()` 与中文 `getDescription()`。

### 5.3 扩写事件示例

继承 Middleware 并重写钩子，把压缩事件转成业务事件：

```java
public class AuditCompactMiddleware extends CompactSummarizationMiddleware {

    private final CompactEventPublisher publisher;

    public AuditCompactMiddleware(CompactConfig config, CompactEventPublisher publisher) {
        super(config);
        this.publisher = publisher;
    }

    @Override
    public void beforeCompact(
            int currentStep,
            CompactTrigger trigger,
            int beforeMessages,
            int beforeTokens,
            int maxContextTokens,
            AgentChatContext chatContext) {
        publisher.publish("compacting", chatContext.getMemoryId(), Map.of(
                "trigger", trigger.getCode(),
                "step", currentStep,
                "beforeMessages", beforeMessages,
                "beforeTokens", beforeTokens,
                "maxContextTokens", maxContextTokens));
    }

    @Override
    public void afterCompact(CompactEvent event, AgentChatContext chatContext) {
        publisher.publish(event.isSuccess() ? "compacted" : "compact_failed",
                chatContext.getMemoryId(), Map.of(
                        "trigger", event.getTrigger().getCode(),
                        "duration", event.getDurationMs(),
                        "beforeTokens", event.getBeforeTokens(),
                        "afterTokens", event.getAfterTokens(),
                        "beforeUsagePercent", event.getBeforeUsagePercent(),
                        "afterUsagePercent", event.getAfterUsagePercent(),
                        "errorMessage", event.getErrorMessage() == null ? "" : event.getErrorMessage()));
    }
}
```

注册时替换原 Middleware：

```java
.middleware(new AuditCompactMiddleware(compactConfig, publisher))
```

### 5.4 扩展建议

- 业务层只重写 `beforeCompact` / `afterCompact`，不要重写 `beforeModelCall` 或工具回调，避免与压缩算法耦合；
- 事件推送属于旁路能力，Publisher 内部应捕获异常，不能阻断压缩；
- 发布态不要输出 Token、消息数等内部细节；调试态再补充完整指标；
- 不要在日志中打印完整用户内容、工具结果或摘要正文；
- 同一份事件建议同时用于实时推送与历史回放，保证刷新页面后展示一致。

## 6. 边界与最佳实践

- Token 采用启发式估算，生产环境如需精确值应接入模型 Tokenizer；
- 摘要复用当前 `ChatContext.getChatModel()`，暂不支持独立摘要模型；
- 当前未实现会话级互斥或 CAS，同一 `memoryId` 并发压缩需要应用层额外保护；
- 压缩只执行一次，不会自动循环压缩到阈值以下；
- 摘要失败时返回原请求，宁可“不压缩”也不阻断 Agent 主任务；
- 工具结果按文本截断，结构化 JSON 场景建议后续增加 JSON 感知截断器；
- 参数和结果写入日志或事件前必须截断、脱敏。

## 7. 参考

更完整的实现细节可参考仓库中的两篇设计文档：

- `docs/dev/backend/AgentForge核心模块设计原理文章系列/02、通用ReActagent层/middleware/内置middlware扩展：上下文压缩middlware（阈值比例压缩机制）.md`：Core 压缩算法、Prompt 与配置；
- `docs/dev/backend/AgentForge核心模块设计原理文章系列/agentforge-service核心业务模块/后端/agent核心设计实现/Agent页面会话对话交互/Agent 上下文阈值压缩交互设计.md`：Service 事件协议与前端调试/发布交互。

下一步：回到 [Middleware 设计总览](../middleware)。

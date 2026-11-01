# AgentForge ReAct 层04、内置 Middleware 扩展：Agent Loop TraceMiddleware 实现

> 适用版本：release_1.x
> 适用模块：`agentforge-framework/agentforge-agent-core`
> 源码位置：`agent/extend/middlewares/trace`
> 包前缀：`cloud.changlu.agentforge.agent.extend.middlewares.trace`
> 维护者：长路

系列定位：本文属于「AgentForge 核心模块设计原理文章系列 / 02、通用 ReAct Agent 层 / middleware」。前几篇分别拆了 ReAct 内核、中间件契约和日志中间件，本篇讲第三个内置实现，也是链路追踪能力的框架层落点：

> **`AgentTraceMiddleware` 如何把一次 Agent 运行拆成可被流程图、火焰图消费的结构化快照，并且在不引入 Spring、MySQL、Vue 的前提下，把耗时、工具调用和 token 消耗完整地交出去。**

与日志中间件不同，`AgentTraceMiddleware` 的产出不是给人读的文本，而是一份稳定的 **`AgentTraceSnapshot` 协议**。框架层只负责「采集事实」，业务层负责「如何存储、如何查询、如何展示」。本文会先拆框架层实现，再用 AgentForge Service 的真实代码示范业务层扩展方式，最后给出几种常见的二次扩展开法。

<br/>

## 文档导航

| 文档 | 说明 |
|---|---|
| [核心ReAct 框架设计，支持非流式、流式 一套调用.md](../%E6%A0%B8%E5%BF%83ReAct%20%E6%A1%86%E6%9E%B6%E8%AE%BE%E8%AE%A1%EF%BC%8C%E6%94%AF%E6%8C%81%E9%9D%9E%E6%B5%81%E5%BC%8F%E3%80%81%E6%B5%81%E5%BC%8F%20%E4%B8%80%E5%A5%97%E8%B0%83%E7%94%A8.md) | 第 01 篇：ReAct 分层、非流式主循环、流式递归驱动、状态机 |
| [标准Agent的middleware接口扩展实现.md](%E6%A0%87%E5%87%86Agent%E7%9A%84middleware%E6%8E%A5%E5%8F%A3%E6%89%A9%E5%B1%95%E5%AE%9E%E7%8E%B0.md) | 第 02 篇：中间件契约、管理器分发、流式/非流式接入 |
| [内置middleware扩展：日志Loggingmiddlware实现.md](%E5%86%85%E7%BD%AEmiddleware%E6%89%A9%E5%B1%95%EF%BC%9A%E6%97%A5%E5%BF%97Loggingmiddlware%E5%AE%9E%E7%8E%B0.md) | 第 03 篇：日志中间件、会话级统计、耗时与 token 汇总 |
| 当前文章（Trace 实现） | 快照协议、Loop 采集、token 记录、框架层接入、业务层扩展 |
| [Agent链路追踪设计实现（火焰图 & 流程图）.md](<../../agentforge-service核心业务模块/后端/agent基础设施/agent链路追踪/Agent链路追踪设计实现（火焰图 & 流程图）.md>) | 业务层全景方案：questionRound、MySQL 快照、查询聚合、流程图/火焰图 UI |

<br/>

## 目录

- [一、背景与定位：日志之后，为什么还需要 Trace](#一背景与定位日志之后为什么还需要-trace)
- [二、Trace 协议与状态模型](#二trace-协议与状态模型)
- [三、核心实现：运行态、回调与快照](#三核心实现运行态回调与快照)
- [四、框架层接入示范](#四框架层接入示范)
- [五、业务层扩展方式：AgentForge Service 案例](#五业务层扩展方式agentforge-service-案例)
- [六、案例扩展示范](#六案例扩展示范)
- [七、已知边界与改进点](#七已知边界与改进点)
- [八、总结](#八总结)
- [参考资料](#参考资料)

---

## 一、背景与定位：日志之后，为什么还需要 Trace

### 1.1、日志能回答什么，不能回答什么

`LoggingIAgentMiddleware` 解决的是「服务端排障」：

1. 这一轮模型调用了多久？
2. 工具参数和返回是什么？
3. 会话最终为什么结束？

但它输出的是**文本行**。文本适合人读，不适合：

1. 前端按 `questionRound` 聚合展示；
2. 画一张有时间轴、能缩放、能点击节点的火焰图；
3. 按 Agent、会话、时间范围做结构化查询；
4. 把 token、模型耗时、工具耗时沉淀成可对账的数据。

因此 AgentForge 在日志之外增加了第二条观测旁路：**Trace**。

| 维度 | `LoggingIAgentMiddleware` | `AgentTraceMiddleware` |
|---|---|---|
| 输出 | 人类可读日志 | 结构化 `AgentTraceSnapshot` |
| 状态存放 | 中间件内部的 `memoryId -> SessionStats` Map | `AgentChatContext.extensions` 中的单次运行态 |
| 主要消费方 | 运维、开发者 | Collector（MySQL / MQ / ES / 实时推送）与可视化页面 |
| 数据粒度 | 会话汇总 + 单次模型/工具调用 | 每个 ReAct Loop 一条 `STEP`，每次提问一条 `ROUND_FINAL` |
| 失败策略 | 日志失败 | best-effort，任何 Trace 异常都不能中断 Agent |
| 是否互相替代 | 否 | 否 |

两者的职责、字段粒度和保留周期都不同。日志适合排查「服务端发生了什么」，Trace 适合回答「用户看到的这次运行，时间和 token 花在了哪里」。

### 1.2、设计原则

`AgentTraceMiddleware` 从设计上坚持五条原则：

1. **只观察，不改写。**
   所有有返回值的回调都原样返回请求、响应和工具结果；中间件可以随时摘掉，Agent 语义不变。

2. **框架中立。**
   Agent Core 只定义 `AgentTraceCollector` SPI 和快照 DTO，不依赖 Spring、MyBatis、MySQL，也不认识 `aid`、`debug` 这类业务字段。

3. **快照是稳定协议。**
   流程图和火焰图只消费 `AgentTraceSnapshot`，不直接解析模型厂商的 SSE 片段，也不依赖 `AiMessage` 的具体 Java 实现。

4. **按 ReAct Loop 记录。**
   一次用户提问是 `questionRound`，一轮 Loop 是 `step`。`STEP` 快照记录过程，`ROUND_FINAL` 快照记录终态。

5. **best-effort，绝不反噬主流程。**
   序列化失败、Collector 写库失败、单个工具结果过长，都不能让 Agent 请求失败。

### 1.3、与《Agent 链路追踪设计方案》的关系

[Agent链路追踪设计实现（火焰图 & 流程图）.md](<../../agentforge-service核心业务模块/后端/agent基础设施/agent链路追踪/Agent链路追踪设计实现（火焰图 & 流程图）.md>) 描述的是完整三层方案：

```text
Agent Core        采集运行事实，产出 AgentTraceSnapshot
Service 后端      关联 sessionId / aid / questionRound，落库、查询、聚合
Service UI        流程图、火焰图、节点详情、实时刷新
```

本文对应其中的 **Agent Core 采集层（P0）**，同时说明 Service 层（P1/P2）如何通过扩展点接入。设计文档中的 `AgentTraceSnapshot`、`AgentTraceSnapshotStatus`、`STEP` / `ROUND_FINAL`、并发工具时间轴等概念，在本文都能找到对应的源码实现。

> 需要先说明一个落地差异：设计文档早期把协议放在 `agent/trace`，首版实际落在 `agent/extend/middlewares/trace`。这符合 AgentForge 当前「内置中间件统一放在 `extend/middlewares` 下」的代码组织方式。

### 1.4、覆盖的回调与边界

`AgentTraceMiddleware` 实现的是 `IAgentMiddleware`，没有实现 `IStreamingIAgentMiddleware`。它不需要逐 token 的增量事件，因为 Trace 不按 token 持久化。

| 回调 | 是否实现 | 采集动作 |
|---|---|---|
| `onInitComplete` | ✅ | 创建运行态，记录 `memoryId`、问题、开始时间、业务属性 |
| `beforeLoop` | ✅ | 创建当前 `step` 运行态，记录 `stepStart` |
| `beforeModelCall` | ✅ | 记录模型调用开始时间 |
| `afterModelCall` | ✅ | 记录模型结束时间、TokenUsage、`finishReason` |
| `onModelCallError` | ✅ | 记录模型异常（主要覆盖非流式路径） |
| `onAiCallRetry` | ❌ | 不单独记录每次重试；模型耗时覆盖整个重试过程 |
| `beforeToolExecution` | ✅ | 串行工具开始 |
| `afterToolExecution` | ✅ | 串行工具成功收口 |
| `onToolExecutionError` | ✅ | 串行工具失败收口 |
| `beforeConcurrentToolExecution` | ✅ | 并发批次开始，批量创建工具运行态 |
| `onConcurrentToolExecutionComplete` | ✅ | 并发批次中每个工具分别收口 |
| `afterConcurrentToolExecution` | ❌ | 单工具完成已收口，不再重复写，避免双计 |
| `afterLoop` | ✅ | 生成/更新 `STEP` 快照（`CONTINUE`） |
| `onLoopError` | ✅ | 生成 `STEP` 快照（`ERROR`） |
| `onStop` | ✅ | 生成 `ROUND_FINAL` 快照（`NORMAL` / `CANCEL` / `MAX_STEPS`） |
| `onStopWithError` | ✅ | 生成 `ROUND_FINAL` 快照（`ERROR`） |
| `onPartialResponse` / `onPartialThinking` | ❌ | 不逐 token 采集，避免高频写库 |
| `onIntermediateResponse` | ❌ | 工具调用信息已由工具回调覆盖 |

<br/>

## 二、Trace 协议与状态模型

### 2.1、四个核心对象

```text
agent/extend/middlewares/trace/
├── AgentTraceMiddleware.java                 采集器
├── AgentTraceCollector.java                  输出 SPI
└── domain/
    ├── AgentTraceSnapshot.java               一次 STEP / ROUND_FINAL 的完整快照
    ├── AgentTraceToolSnapshot.java           step 内的单个工具调用
    └── AgentTraceSnapshotStatus.java         稳定状态枚举
```

其中 `AgentTraceCollector` 只有一个方法：

```java
public interface AgentTraceCollector {

    void onSnapshot(AgentTraceSnapshot snapshot);
}
```

这个 SPI 刻意保持最小：

1. 不要求实现方用 Spring；
2. 不要求实现方落 MySQL；
3. 不限制同步、异步、MQ、ES、WebSocket；
4. 实现方自行处理重试、限流、数据保留和权限。

### 2.2、AgentTraceSnapshot 字段

`AgentTraceSnapshot` 是框架层与业务层之间的稳定契约：

| 字段 | 类型 | 说明 |
|---|---|---|
| `memoryId` | String | 来自 `AgentChatContext.getMemoryId()`，业务层可映射为 `session_id` |
| `questionRound` | int | 业务轮次；框架层不主动赋值，由业务 Collector 补齐 |
| `step` | int | 当前 ReAct Loop，从 1 开始 |
| `snapshotKind` | String | `STEP` 或 `ROUND_FINAL` |
| `question` | String | 本次用户问题（截断后） |
| `startedAt` / `finishedAt` | long | 快照时间范围，Unix 毫秒 |
| `durationMs` | long | 快照总耗时 |
| `modelStartedAt` / `modelFinishedAt` | long | 模型调用时间范围 |
| `modelDurationMs` | long | 模型调用耗时 |
| `toolDurationMs` | long | 本 step 所有工具耗时之和 |
| `inputTokens` / `outputTokens` / `totalTokens` | long | Token 用量 |
| `cachedTokens` | long | 缓存命中的输入 token |
| `finished` | boolean | 是否为 `ROUND_FINAL` |
| `status` | `AgentTraceSnapshotStatus` | `CONTINUE` / `NORMAL` / `CANCEL` / `ERROR` / `MAX_STEPS` |
| `error` | String | 异常摘要 |
| `tools` | List | 当前 step 的工具调用快照 |
| `attributes` | Map | `finishReason`、`stepCount` 以及业务扩展字段 |

> 与设计文档的差异：首版协议还没有 `messages` 字段，也没有独立的 `AgentTraceMessageSnapshot`。当前可视化主要依赖 `question`、`tools` 和 `attributes`。如果业务需要展示 AI 文本、思考内容或完整消息树，可以按第六节的方式扩展，不需要改 ReAct 内核。

### 2.3、AgentTraceToolSnapshot 字段

| 字段 | 说明 |
|---|---|
| `toolCallId` | 模型发出的工具调用 ID，用于并发场景去重 |
| `toolName` | 工具名 |
| `arguments` | 参数摘要（截断） |
| `result` | 结果摘要（截断） |
| `error` | 异常摘要 |
| `startedAt` / `finishedAt` | 工具时间范围 |
| `durationMs` | 工具耗时 |
| `status` | `RUNNING` / `SUCCESS` / `ERROR` |

### 2.4、时间与 Token 的语义

这是 Trace 最容易误读的地方，先明确口径：

1. **`durationMs` 是 wall clock。**
   `ROUND_FINAL.durationMs = finishedAt - startedAt`，表示这次提问从开始到结束的真实耗时。

2. **`modelDurationMs` 是各 step 模型耗时之和。**
   一轮 ReAct Loop 只有一次模型调用，因此 `ROUND_FINAL.modelDurationMs` 等于所有 step 的模型耗时累加。

3. **`toolDurationMs` 是各 step 工具耗时之和。**
   并发工具会分别记录各自的 `durationMs`，再相加。因此 `toolDurationMs` 可能大于 `ROUND_FINAL.durationMs`，这不是数据错误，而是并发执行的自然结果。

4. **`totalTokens` 是各次模型调用的用量之和。**
   每一轮 Loop 都会把完整 ChatMemory 重新发给模型，所以多轮 Loop 的累计 token 会明显大于最终回答本身的 token。Trace 记录的是「这次请求总共消耗了多少」，不是「最终上下文有多大」。

5. **`cachedTokens` 是输入 token 中命中缓存的部分。**
   AgentForge 的统一 `TokenUsage` 已经提供该字段，Trace 直接透传；缓存命中率按 `cachedTokens / inputTokens` 计算。

<br/>

## 三、核心实现：运行态、回调与快照

### 3.1、运行态放在 AgentChatContext，而不是全局 Map

日志中间件的状态放在中间件实例的 `ConcurrentHashMap<memoryId, SessionStats>` 里，因为日志需要跨回调累计，并且中间件实例会被多个会话共享。

Trace 采用了另一种方式：**把运行态直接挂到本次运行的 `AgentChatContext.extensions` 上**。

```java
private static final String RUNTIME_KEY =
        AgentTraceMiddleware.class.getName() + ".runtime";

@Override
public void onInitComplete(AgentChatContext context) {
    Runtime runtime = new Runtime();
    runtime.memoryId = String.valueOf(context.getMemoryId());
    runtime.question = truncate(context.getQuestion());
    runtime.startedAt = System.currentTimeMillis();
    enrichAttributes(context, runtime.attributes);
    context.putExtension(RUNTIME_KEY, runtime);
}
```

读取时也只从当前上下文取：

```java
private Runtime runtime(AgentChatContext context) {
    Object value = context == null ? null : context.getExtension(RUNTIME_KEY);
    return value instanceof Runtime ? (Runtime) value : null;
}
```

这样做有三个好处：

1. **天然按运行隔离**：不依赖 `memoryId` 做全局 key，不会出现两个并发请求互相覆盖统计。
2. **随上下文回收**：`AgentChatContext` 是一次运行的短生命周期对象，运行结束即可被 GC，不需要额外的 TTL 清理。
3. **中间件实例可复用**：`ReActAgent` 和中间件可以被缓存复用，但每次运行的 Runtime 都是新的。

运行态结构：

```java
private static class Runtime {
    private String memoryId;
    private String question;
    private long startedAt;
    private boolean finished;
    private final Map<Integer, Step> steps = new LinkedHashMap<Integer, Step>();
    private final Map<String, Object> attributes = new LinkedHashMap<String, Object>();
}

private static class Step {
    private int step;
    private long startedAt;
    private long finishedAt;
    private long modelStartedAt;
    private long modelFinishedAt;
    private long inputTokens;
    private long outputTokens;
    private long totalTokens;
    private long cachedTokens;
    private String error;
    private final Map<String, AgentTraceToolSnapshot> tools =
            new LinkedHashMap<String, AgentTraceToolSnapshot>();
    private final Map<String, Object> attributes = new LinkedHashMap<String, Object>();
}
```

`steps` 使用 `LinkedHashMap`，保证聚合时按 step 顺序遍历；`tools` 用 `toolCallId` 作为 key，保证并发工具可以分别收口。

### 3.2、初始化与步骤边界

`onInitComplete` 只在一次运行开始时触发一次，负责记录会话级信息。

`beforeLoop` 每进入一轮 ReAct Loop 触发一次，负责创建 step 运行态：

```java
@Override
public void beforeLoop(int currentStep, AgentChatContext context) {
    Runtime runtime = runtime(context);
    if (runtime == null) return;
    Step step = new Step();
    step.step = currentStep;
    step.startedAt = System.currentTimeMillis();
    runtime.steps.put(currentStep, step);
}
```

对应的 `afterLoop` 负责收口 step 的时间范围，并发布 `STEP` 快照。

### 3.3、模型调用与 Token 记录

模型调用前后只做三件事：记录开始时间、记录结束时间、读取 TokenUsage。

```java
@Override
public ChatRequest beforeModelCall(
        int currentStep, ChatRequest request, AgentChatContext context) {
    Step step = step(context, currentStep);
    if (step != null) step.modelStartedAt = System.currentTimeMillis();
    return request;
}

@Override
public ChatResponse afterModelCall(
        int currentStep, ChatRequest request, ChatResponse response,
        AgentChatContext context) {
    Step step = step(context, currentStep);
    if (step != null) {
        step.modelFinishedAt = System.currentTimeMillis();
        TokenUsage usage = response == null ? null : response.tokenUsage();
        if (usage != null) {
            step.inputTokens = usage.inputTokens();
            step.outputTokens = usage.outputTokens();
            step.totalTokens = usage.totalTokens();
            step.cachedTokens = usage.cachedTokens();
        }
        if (response != null && response.finishReason() != null) {
            step.attributes.put("finishReason", response.finishReason().toString());
        }
    }
    return response;
}
```

几个细节：

1. **每个 step 只有一次模型调用**，所以 token 字段是赋值而不是累加。
2. **重试不产生额外 step**。`beforeModelCall` 在 `AiCallRetrySupport.execute` 之前触发，`afterModelCall` 在最终成功后触发，因此 `modelDurationMs` 覆盖了整个重试过程，token 只取最终成功响应的用量。
3. **失败调用没有 token**。模型异常走 `onModelCallError`，只记录错误和时间，不记录 TokenUsage。

```java
@Override
public void onModelCallError(
        int currentStep, ChatRequest request, Throwable error,
        AgentChatContext context) {
    Step step = step(context, currentStep);
    if (step != null) {
        step.modelFinishedAt = System.currentTimeMillis();
        step.error = errorText(error);
    }
}
```

> 注意：`onModelCallError` 在非流式路径触发；流式路径模型失败走 `onLoopError` + `onStopWithError`，由这两个回调收口。

### 3.4、工具执行：串行与并发统一收口

工具节点的开始和结束分别由两组回调负责。

串行：

```java
@Override
public void beforeToolExecution(
        ToolExecutionRequest request, AgentChatContext context) {
    startTool(currentStep(context), request, context);
}

@Override
public String afterToolExecution(
        ToolExecutionRequest request, String result, AgentChatContext context) {
    finishTool(currentStep(context), request, result, null, context);
    return result;
}

@Override
public void onToolExecutionError(
        ToolExecutionRequest request, Throwable error, AgentChatContext context) {
    finishTool(currentStep(context), request, null, error, context);
}
```

并发：

```java
@Override
public void beforeConcurrentToolExecution(
        List<ToolExecutionRequest> requests, AgentChatContext context) {
    if (requests == null) return;
    Integer currentStep = currentStep(context);
    for (ToolExecutionRequest request : requests) startTool(currentStep, request, context);
}

@Override
public void onConcurrentToolExecutionComplete(
        ToolExecutionRequest request, String result, Throwable error,
        AgentChatContext context) {
    finishTool(currentStep(context), request, result, error, context);
}
```

工具 key 的设计：

```java
private static String toolKey(ToolExecutionRequest request) {
    return request.id() == null
            ? request.name() + "@" + System.identityHashCode(request)
            : request.id();
}
```

优先使用 `toolCallId`，保证同一轮模型返回多个工具时不会互相覆盖；只有在模型没有提供 id 时，才退化为「名称 + 对象身份」。

工具收口：

```java
private void finishTool(
        Integer currentStep, ToolExecutionRequest request, String result,
        Throwable error, AgentChatContext context) {
    if (currentStep == null || request == null) return;
    Step step = step(context, currentStep);
    if (step == null) return;
    AgentTraceToolSnapshot tool = step.tools.get(toolKey(request));
    if (tool == null) {
        startTool(currentStep, request, context);
        tool = step.tools.get(toolKey(request));
    }
    tool.setFinishedAt(System.currentTimeMillis());
    tool.setDurationMs(duration(tool.getStartedAt(), tool.getFinishedAt()));
    tool.setResult(truncate(result));
    tool.setError(errorText(error));
    tool.setStatus(error == null ? "SUCCESS" : "ERROR");
}
```

`afterConcurrentToolExecution` 没有实现，原因是每个工具已经在 `onConcurrentToolExecutionComplete` 中单独收口；如果批次回调再写一次，会导致工具耗能被重复计算。

### 3.5、STEP 与 ROUND_FINAL 快照

一轮 Loop 结束时发布 `STEP`：

```java
@Override
public void afterLoop(int currentStep, StepResult result, AgentChatContext context) {
    Step step = step(context, currentStep);
    if (step == null) return;
    step.finishedAt = System.currentTimeMillis();
    publish(snapshot(runtime(context), step, "STEP", false,
            AgentTraceSnapshotStatus.CONTINUE));
}
```

本轮回合异常时发布 `STEP ERROR`：

```java
@Override
public void onLoopError(int currentStep, Throwable error, AgentChatContext context) {
    Step step = step(context, currentStep);
    if (step == null) return;
    step.finishedAt = System.currentTimeMillis();
    step.error = errorText(error);
    publish(snapshot(runtime(context), step, "STEP", false,
            AgentTraceSnapshotStatus.ERROR));
}
```

Agent 停止时发布 `ROUND_FINAL`：

```java
@Override
public void onStop(int currentStep, StopResult result, AgentChatContext context) {
    AgentTraceSnapshotStatus status = AgentTraceSnapshotStatus.NORMAL;
    if (result != null && result.getStopResultState() == StopResultState.CANCEL) {
        status = AgentTraceSnapshotStatus.CANCEL;
    } else if (result != null
            && result.getStopResultState() == StopResultState.MAX_STEPS) {
        status = AgentTraceSnapshotStatus.MAX_STEPS;
    }
    finish(context, currentStep, status, null);
}

@Override
public void onStopWithError(
        int currentStep, Throwable error, AgentChatContext context) {
    finish(context, currentStep, AgentTraceSnapshotStatus.ERROR, error);
}
```

这里有一个重要的设计选择：**`STEP` 的 status 永远是 `CONTINUE`（或异常时的 `ERROR`），最终业务状态只放在 `ROUND_FINAL` 上**。

原因是一轮 Loop 结束时，Agent 还不知道这次提问最终会不会继续下一轮。把 `NORMAL` / `MAX_STEPS` / `CANCEL` 留给 `ROUND_FINAL`，查询层和前端才能拿到稳定的终态。

### 3.6、最终快照的聚合算法

`finish` 做了三件事：幂等保护、聚合、清理运行态。

```java
private void finish(
        AgentChatContext context, int currentStep,
        AgentTraceSnapshotStatus status, Throwable error) {
    Runtime runtime = runtime(context);
    if (runtime == null || runtime.finished) return;
    runtime.finished = true;

    Step last = runtime.steps.get(currentStep);
    if (last == null && !runtime.steps.isEmpty()) {
        last = new ArrayList<Step>(runtime.steps.values())
                .get(runtime.steps.size() - 1);
    }

    AgentTraceSnapshot snapshot = aggregate(runtime, last, status);
    snapshot.setError(errorText(error));
    publish(snapshot);
    context.getExtensions().remove(RUNTIME_KEY);
}
```

聚合逻辑：

```java
private AgentTraceSnapshot aggregate(
        Runtime runtime, Step last, AgentTraceSnapshotStatus status) {
    AgentTraceSnapshot snapshot = base(runtime, last == null ? 0 : last.step);
    snapshot.setSnapshotKind("ROUND_FINAL");
    snapshot.setFinished(true);
    snapshot.setStatus(status);
    snapshot.setStartedAt(runtime.startedAt);
    snapshot.setFinishedAt(System.currentTimeMillis());
    snapshot.setDurationMs(snapshot.getFinishedAt() - snapshot.getStartedAt());

    for (Step step : runtime.steps.values()) {
        snapshot.setModelDurationMs(
                snapshot.getModelDurationMs()
                        + duration(step.modelStartedAt, step.modelFinishedAt));
        snapshot.setToolDurationMs(
                snapshot.getToolDurationMs() + toolDuration(step));
        snapshot.setInputTokens(snapshot.getInputTokens() + step.inputTokens);
        snapshot.setOutputTokens(snapshot.getOutputTokens() + step.outputTokens);
        snapshot.setTotalTokens(snapshot.getTotalTokens() + step.totalTokens);
        snapshot.setCachedTokens(snapshot.getCachedTokens() + step.cachedTokens);
        snapshot.getTools().addAll(copyTools(step));
    }
    snapshot.getAttributes().put("stepCount", runtime.steps.size());
    return snapshot;
}
```

`ROUND_FINAL` 的字段口径：

| 字段 | 来源 |
|---|---|
| `startedAt` | 运行开始时间 |
| `finishedAt` | 停止回调触发时间 |
| `durationMs` | wall clock |
| `modelDurationMs` | 所有 step 的模型耗时之和 |
| `toolDurationMs` | 所有 step 的工具耗时之和 |
| `inputTokens` / `outputTokens` / `totalTokens` | 所有 step 的 token 之和 |
| `tools` | 所有 step 的工具节点合并 |
| `attributes.stepCount` | Loop 轮数 |
| `step` | 最后一个 step |

### 3.7、异常隔离与幂等

Trace 是观测旁路，必须做到「自己出错不影响 Agent」：

```java
private void publish(AgentTraceSnapshot snapshot) {
    if (collector == null || snapshot == null) return;
    try {
        collector.onSnapshot(snapshot);
    } catch (RuntimeException ignored) {
        // Observability is deliberately isolated from the Agent's main execution path.
    }
}
```

同时：

1. `runtime.finished` 保证 `ROUND_FINAL` 只发布一次；
2. `context.getExtensions().remove(RUNTIME_KEY)` 在终态后释放运行态；
3. 文本统一截断到 4096 字符；
4. 工具参数、结果、异常信息都只保存摘要。

Collector 自身也应保持幂等。AgentForge 的 MySQL 实现使用 `session_id + question_round + step + finished` 作为应用层幂等键，先更新、未命中再插入。

### 3.8、三个受保护扩展钩子

框架层预留了三个 `protected` 方法，给业务平台扩展，而不需要修改 Agent Core：

```java
/** 创建协议对象，允许业务平台返回快照子类型。 */
protected AgentTraceSnapshot createSnapshot() {
    return new AgentTraceSnapshot();
}

/** 给业务快照子类填充强类型字段。 */
protected void enrichSnapshot(
        AgentTraceSnapshot snapshot, Map<String, Object> traceAttributes) {}

/** 给每条快照补充可序列化的业务元数据。 */
protected void enrichAttributes(
        AgentChatContext context, Map<String, Object> traceAttributes) {}
```

调用时机：

| 钩子 | 调用位置 | 用途 |
|---|---|---|
| `enrichAttributes` | `onInitComplete` | 从 `AgentChatContext.extensions` 读取业务字段，写入运行态 `attributes` |
| `createSnapshot` | 每次构造快照 | 返回业务快照子类，例如 `AgentForgeTraceSnapshot` |
| `enrichSnapshot` | `base()` 中 | 把 `attributes` 中的业务字段落到快照子类的强类型属性上 |

需要注意：`enrichAttributes` 只在 `onInitComplete` 调用一次，因此业务字段必须在 Agent 初始化完成前写入 `AgentChatContext.extensions`。AgentForge Service 通过 `AgentRequest.extensions` 透传 `aid`、`debug`，正是基于这个时序。

### 3.9、同步与流式路径对照

同一套回调在两条路径上都能工作，触发点如下：

| 回调 | 非流式 `Agent.run()` | 流式 `ReActTokenStream` |
|---|---|---|
| `onInitComplete` | `Agent.java:56` | `ReActTokenStream.java:149` |
| `beforeLoop` | `Agent.java:73` | `ReActTokenStream.java:184` |
| `beforeModelCall` | `ReActAgent.java:170` | `ReActTokenStream.java:186` |
| `afterModelCall` | `ReActAgent.java:214` | `ReActTokenStream.java:243` |
| `onModelCallError` | `ReActAgent.java:200` | 不触发，异常走 `onLoopError` |
| 串行工具回调 | `AgentToolExecutor.java:124/135/183` | 同一执行器 |
| 并发工具回调 | `AgentToolExecutor.java:201/247` | 同一执行器 |
| `afterLoop` | `Agent.java:77` | `ReActTokenStream.java:258/286` |
| `onLoopError` | `Agent.java:95/103/108` | `ReActTokenStream.java:307/331` |
| `onStop` | `Agent.java:123/131/136` | `ReActTokenStream.java:260/309/314/332` |
| `onStopWithError` | `Agent.java:109` | `ReActTokenStream.java:319` |

因此 Trace 不需要为流式单独写一套埋点。流式路径的差异主要在于：

1. 模型失败不会触发 `onModelCallError`，而是 `onLoopError` + `onStopWithError`；
2. 取消会先 `onLoopError` 再 `onStop(CANCEL)`；
3. 达到最大步数会先 `onLoopError` 再 `onStop(MAX_STEPS)`；
4. 增量 token 不采集，模型完整响应到达后才记录 token。

<br/>

## 四、框架层接入示范

### 4.1、最小可运行装配

Agent Core 不关心 Collector 怎么实现。最小用法：

```java
ReActAgent agent = ReActAgent.builder()
        .agentName("weather-agent")
        .systemPrompt("你是一个天气助手")
        .chatModel(chatModel)
        .streamingChatModel(streamingChatModel)
        .chatMemoryProvider(new MessageWindowChatMemoryProvider(100))
        .toolService(toolService)
        .middleware(new LoggingIAgentMiddleware())
        .middleware(new AgentTraceMiddleware(new InMemoryTraceCollector()))
        .build();
```

`InMemoryTraceCollector` 可以是一个最简单的观察实现：

```java
public class InMemoryTraceCollector implements AgentTraceCollector {

    private final List<AgentTraceSnapshot> snapshots =
            new CopyOnWriteArrayList<AgentTraceSnapshot>();

    @Override
    public void onSnapshot(AgentTraceSnapshot snapshot) {
        snapshots.add(snapshot);
    }

    public List<AgentTraceSnapshot> snapshots() {
        return snapshots;
    }
}
```

这样一次包含两个 Loop 的请求会得到：

```text
STEP(step=1, CONTINUE)     第一轮模型 + 工具
STEP(step=2, CONTINUE)     第二轮模型
ROUND_FINAL(status=NORMAL) 汇总：总耗时、模型耗时、工具耗时、token、stepCount
```

### 4.2、用日志 Collector 观察

如果只想快速验证，可以写一个把快照打印成日志的 Collector：

```java
public class LoggingTraceCollector implements AgentTraceCollector {

    private static final Logger LOG =
            Logger.getLogger(LoggingTraceCollector.class.getName());

    @Override
    public void onSnapshot(AgentTraceSnapshot snapshot) {
        LOG.info(String.format(
                "[TRACE] kind=%s step=%d status=%s duration=%dms "
                        + "model=%dms tool=%dms token=%d/%d/%d cached=%d",
                snapshot.getSnapshotKind(),
                snapshot.getStep(),
                snapshot.getStatus(),
                snapshot.getDurationMs(),
                snapshot.getModelDurationMs(),
                snapshot.getToolDurationMs(),
                snapshot.getInputTokens(),
                snapshot.getOutputTokens(),
                snapshot.getTotalTokens(),
                snapshot.getCachedTokens()));
    }
}
```

一个典型的多轮请求会看到：

```text
[TRACE] kind=STEP step=1 status=CONTINUE duration=860ms model=820ms tool=12ms token=120/35/155 cached=0
[TRACE] kind=STEP step=2 status=CONTINUE duration=660ms model=650ms tool=0ms token=180/24/204 cached=0
[TRACE] kind=ROUND_FINAL step=2 status=NORMAL duration=1530ms model=1470ms tool=12ms token=300/59/359 cached=0
```

### 4.3、测试关注点

Trace 的测试不应依赖日志文本，而应断言协议：

1. 无工具请求产生 1 条 `STEP` + 1 条 `ROUND_FINAL`；
2. 两轮 Loop 请求产生 2 条 `STEP` + 1 条 `ROUND_FINAL`；
3. 并发工具的 `toolCallId` 不互相覆盖；
4. 正常、取消、最大步数、模型异常、工具异常都有正确终态；
5. Collector 抛异常时 Agent 仍能正常返回；
6. 同步和流式生成的 `ROUND_FINAL` 字段结构一致。

<br/>

## 五、业务层扩展方式：AgentForge Service 案例

框架层只负责采集，业务层要解决四个问题：

1. 业务字段怎么带进来？
2. 快照怎么落库？
3. 查询怎么按 `questionRound` 聚合？
4. 前端怎么画流程图和火焰图？

AgentForge Service 的对应模块位于：

```text
agentforge-service/agentforge-service-web/src/main/java/cloud/changlu/agentforge/service/modules/trace/
├── AgentTraceAttributes.java
├── middleware/AgentForgeTraceMiddleware.java
├── domain/AgentForgeTraceSnapshot.java
├── domain/AgentTraceSnapshotPojo.java
├── service/MysqlAgentTraceCollector.java
├── service/AgentTraceQueryService.java
├── dao/AgentTraceSnapshotMapper.java
└── controller/AgentTraceController.java
```

整体关系：

```text
ReActAgentFactory
   └── 注册 AgentForgeTraceMiddleware
          ├── 继承 AgentTraceMiddleware
          ├── enrichAttributes: 写入 aid / debug
          ├── createSnapshot: 返回 AgentForgeTraceSnapshot
          └── enrichSnapshot: 把 aid / debug 落到强类型字段
                    │
                    ▼
            AgentTraceCollector
                    │
                    ▼
        MysqlAgentTraceCollector
                    │
                    ▼
        ai_agent_trace_snapshot
                    │
                    ▼
        AgentTraceQueryService / AgentTraceController
                    │
                    ▼
        AgentTracePage / FlowView / FlameView
```

### 5.1、业务中间件：AgentForgeTraceMiddleware

```java
@Component
public class AgentForgeTraceMiddleware extends AgentTraceMiddleware {

    public AgentForgeTraceMiddleware(AgentTraceCollector collector) {
        super(collector);
    }

    @Override
    protected void enrichAttributes(
            AgentChatContext context, Map<String, Object> traceAttributes) {
        copy(context, traceAttributes, AgentTraceAttributes.AID);
        copy(context, traceAttributes, AgentTraceAttributes.DEBUG);
    }

    @Override
    protected AgentTraceSnapshot createSnapshot() {
        return new AgentForgeTraceSnapshot();
    }

    @Override
    protected void enrichSnapshot(
            AgentTraceSnapshot snapshot, Map<String, Object> traceAttributes) {
        AgentForgeTraceSnapshot serviceSnapshot = (AgentForgeTraceSnapshot) snapshot;
        Object aid = traceAttributes.get(AgentTraceAttributes.AID);
        Object debug = traceAttributes.get(AgentTraceAttributes.DEBUG);
        serviceSnapshot.setAid(aid == null ? null : String.valueOf(aid));
        serviceSnapshot.setDebug(debug instanceof Boolean ? (Boolean) debug : null);
    }

    private void copy(
            AgentChatContext context, Map<String, Object> traceAttributes, String attributeName) {
        Object value = context.getExtension(attributeName);
        if (value != null) {
            traceAttributes.put(attributeName, value);
        }
    }
}
```

这里体现了框架层与业务层的边界：

- `AgentTraceMiddleware` 不认识 `aid`、`debug`；
- Service 通过继承和 `context.getExtension(...)` 把业务字段带进通用 `attributes`；
- 快照子类把通用字段升级为强类型字段，方便 Collector 直接读取。

### 5.2、业务快照：AgentForgeTraceSnapshot

```java
public class AgentForgeTraceSnapshot extends AgentTraceSnapshot {

    private String aid;
    private Boolean debug;

    public String getAid() {
        return aid;
    }

    public void setAid(String aid) {
        this.aid = aid;
    }

    public Boolean getDebug() {
        return debug;
    }

    public void setDebug(Boolean debug) {
        this.debug = debug;
    }
}
```

这样 `MysqlAgentTraceCollector` 可以直接：

```java
if (snapshot instanceof AgentForgeTraceSnapshot) {
    return ((AgentForgeTraceSnapshot) snapshot).getAid();
}
```

而不需要让 Agent Core 的 `AgentTraceSnapshot` 增加任何业务字段。

### 5.3、业务 Collector：MysqlAgentTraceCollector

```java
@Component
public class MysqlAgentTraceCollector implements AgentTraceCollector {

    private static final Logger LOG =
            LoggerFactory.getLogger(MysqlAgentTraceCollector.class);

    private final AgentTraceSnapshotMapper mapper;
    private final ObjectMapper objectMapper;

    @Override
    public void onSnapshot(AgentTraceSnapshot snapshot) {
        if (snapshot == null || snapshot.getMemoryId() == null) return;
        try {
            int round = resolveQuestionRound(snapshot);
            snapshot.setQuestionRound(round);

            AgentTraceSnapshotPojo pojo = new AgentTraceSnapshotPojo();
            pojo.setSessionId(snapshot.getMemoryId());
            pojo.setAid(resolveAid(snapshot));
            pojo.setQuestionRound(round);
            pojo.setStep(snapshot.getStep());
            pojo.setStartedAt(toDate(snapshot.getStartedAt()));
            pojo.setFinishedAt(toDate(snapshot.getFinishedAt()));
            pojo.setDurationMs(snapshot.getDurationMs());
            pojo.setInputTokens(snapshot.getInputTokens());
            pojo.setOutputTokens(snapshot.getOutputTokens());
            pojo.setCachedTokens(snapshot.getCachedTokens());
            pojo.setTotalTokens(snapshot.getTotalTokens());
            pojo.setFinished(snapshot.isFinished() ? 1 : 0);
            pojo.setStatus(snapshot.getStatus().name());
            pojo.setDebug(resolveDebug(snapshot));
            pojo.setSnapshotJson(objectMapper.writeValueAsString(snapshot));

            if (mapper.updateSnapshot(pojo) == 0) {
                mapper.insert(pojo);
            }
        } catch (Exception e) {
            LOG.error("Save Agent trace failed, sessionId: {}, step: {}",
                    snapshot.getMemoryId(), snapshot.getStep(), e);
        }
    }
}
```

它的关键设计：

1. **应用层幂等**：先按 `session_id + question_round + step + finished` 更新，未命中再插入。
2. **失败不影响 Agent**：整个方法被 `try/catch` 包住，异常只记日志。
3. **快照双写**：常用字段拆列，完整协议写入 `snapshot_json`，查询层可以按列聚合，也可以还原完整快照。
4. **questionRound 兜底**：首版如果快照上没有轮次，就用 `countFinishedBySessionId + 1` 计算。

对应 SQL：

```sql
UPDATE ai_agent_trace_snapshot
SET aid=#{aid}, started_at=#{startedAt}, finished_at=#{finishedAt},
    duration_ms=#{durationMs}, input_tokens=#{inputTokens},
    output_tokens=#{outputTokens}, cached_tokens=#{cachedTokens},
    total_tokens=#{totalTokens}, status=#{status}, debug=#{debug},
    snapshot_json=#{snapshotJson}, modify_time=CURRENT_TIMESTAMP(3)
WHERE session_id=#{sessionId} AND question_round=#{questionRound}
  AND step=#{step} AND finished=#{finished}
```

### 5.4、工厂注册：ReActAgentFactory

```java
ReActAgent.ReActAgentBuilder builder =
        ReActAgent.builder()
                .agentName("StandardReActAgent")
                .systemPrompt(standardAgentConfig.getSystemPrompt())
                .chatModel(LlmFactory.buildChatModel(llmConfig))
                .streamingChatModel(LlmFactory.buildStreamChatModel(llmConfig))
                .chatMemoryProvider(
                        chatMemoryProviderFactory.getChatMemoryProvider(
                                standardAgentConfig.getChatMemoryConfig()))
                .middleware(new LoggingIAgentMiddleware())
                .middleware(sessionLifecycleMiddleware)
                .middleware(streamingMiddleware)
                .middleware(agentTraceMiddleware)
                .toolService(buildToolService(standardAgentConfig));
```

Trace 中间件放在最后，原因是：

1. 它只观察，不改写请求、响应和工具结果；
2. 放在最后可以记录前序中间件处理后的最终结果；
3. 不依赖任何前序中间件的副作用，注册顺序相对安全。

如果业务中间件会改写 `ChatRequest`、`ChatResponse` 或工具结果，建议把 Trace 放在它们之后，并在测试中固定顺序。

### 5.5、查询聚合与前端可视化

`AgentTraceQueryService` 把同一会话的快照按 `questionRound` 分组：

```java
List<AgentTraceSnapshotPojo> rows = mapper.selectBySessionId(sessionId.trim());
Map<Integer, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
for (AgentTraceSnapshotPojo row : rows) {
    Map<String, Object> snapshot =
            objectMapper.readValue(row.getSnapshotJson(), Map.class);
    snapshot.put("id", row.getId());
    snapshot.put("modifyTime", row.getModifyTime());
    grouped.computeIfAbsent(row.getQuestionRound(), key -> new ArrayList<>())
            .add(snapshot);
}
```

每一组优先使用 `ROUND_FINAL` 作为摘要，未完成时回退到最新 `STEP`：

```java
Map<String, Object> display = snapshots.get(snapshots.size() - 1);
for (Map<String, Object> item : snapshots) {
    if ("ROUND_FINAL".equals(item.get("snapshotKind"))) display = item;
}
```

查询接口：

```text
GET /api/agent-trace/sessions?aid=xxx&debug=1&limit=50
GET /api/agent-trace/session/{sessionId}
```

前端消费同一份快照：

1. `traceGraphAdapter.buildTraceGraph(round)` 构造 `Round -> 模型 -> 工具 -> 结果` 的流程图节点；
2. `AgentTraceFlameView` 按 `startedAt`、`durationMs` 在统一时间轴上绘制 `Root / Step / Model / Tool` 行；
3. 节点详情直接读取 `status`、`durationMs`、`totalTokens`、`arguments`、`result`、`error`。

因此，框架层只要稳定产出这些字段，流程图、火焰图和节点详情就不需要理解 Agent 内部对象。

### 5.6、一次完整链路的时序

```text
AgentChatController
  └── ReActAgentFactory
        └── Agent.run / runStream
              ├── onInitComplete
              │     └── AgentForgeTraceMiddleware.enrichAttributes(aid, debug)
              ├── beforeLoop(step=1)
              ├── beforeModelCall / afterModelCall
              ├── 工具回调（串行或并发）
              ├── afterLoop(step=1)  -> STEP 快照
              ├── beforeLoop(step=2)
              ├── beforeModelCall / afterModelCall
              ├── afterLoop(step=2)  -> STEP 快照
              └── onStop             -> ROUND_FINAL 快照
                    └── MysqlAgentTraceCollector.onSnapshot
                          ├── update ai_agent_trace_snapshot
                          └── insert ai_agent_trace_snapshot（未命中时）

AgentTracePage
  └── AgentTraceController
        └── AgentTraceQueryService
              └── 按 questionRound 分组 -> 流程图 / 火焰图
```

<br/>

## 六、案例扩展示范

下面几种扩展都不需要修改 `Agent.run()`、`ReActTokenStream` 或 `AgentTraceMiddleware` 的核心逻辑。

### 6.1、场景一：增加租户 / 用户维度

如果业务需要按租户、用户过滤 Trace，新增一个子类中间件即可：

```java
public class TenantTraceMiddleware extends AgentTraceMiddleware {

    public static final String TENANT_ID = "tenant.id";
    public static final String USER_ID = "user.id";

    public TenantTraceMiddleware(AgentTraceCollector collector) {
        super(collector);
    }

    @Override
    protected void enrichAttributes(
            AgentChatContext context, Map<String, Object> traceAttributes) {
        copy(context, traceAttributes, TENANT_ID);
        copy(context, traceAttributes, USER_ID);
    }

    private void copy(
            AgentChatContext context, Map<String, Object> traceAttributes, String key) {
        Object value = context.getExtension(key);
        if (value != null) traceAttributes.put(key, value);
    }
}
```

调用方需要在请求初始化时把字段写入 `AgentRequest.extensions`：

```java
Map<String, Object> extensions = new HashMap<String, Object>();
extensions.put(TenantTraceMiddleware.TENANT_ID, tenantId);
extensions.put(TenantTraceMiddleware.USER_ID, userId);

AgentRequest.builder()
        .memoryId(sessionId)
        .question(question)
        .extensions(extensions)
        .build();
```

落库时，Collector 从 `snapshot.getAttributes()` 读取，写入自己的业务列即可。

### 6.2、场景二：接入异步 MQ / ES / WebSocket Collector

Collector 是 SPI，替换实现即可。例如异步转发到 MQ：

```java
public class AsyncTraceCollector implements AgentTraceCollector {

    private final AgentTraceCollector delegate;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public AsyncTraceCollector(AgentTraceCollector delegate) {
        this.delegate = delegate;
    }

    @Override
    public void onSnapshot(AgentTraceSnapshot snapshot) {
        String payload = toJson(snapshot);
        executor.submit(() -> {
            try {
                delegate.onSnapshot(fromJson(payload));
            } catch (Exception ignored) {
                // 异步侧失败不能影响 Agent
            }
        });
    }
}
```

注意两点：

1. 入队前最好先序列化成不可变副本，避免异步线程读到后续被修改的对象；
2. 异步队列要设置容量和丢弃策略，避免 Trace 反压 Agent。

### 6.3、场景三：Token 成本核算与指标上报

Token 已经在快照里，成本核算只是 Collector 的业务计算：

```java
public class CostTraceCollector implements AgentTraceCollector {

    private final double inputPricePerMillion;
    private final double outputPricePerMillion;
    private final double cachedPricePerMillion;

    @Override
    public void onSnapshot(AgentTraceSnapshot snapshot) {
        long cached = snapshot.getCachedTokens();
        long input = Math.max(0, snapshot.getInputTokens() - cached);

        double cost = input / 1_000_000.0 * inputPricePerMillion
                + snapshot.getOutputTokens() / 1_000_000.0 * outputPricePerMillion
                + cached / 1_000_000.0 * cachedPricePerMillion;

        // 写入成本表，或上报 Micrometer / Prometheus
        recordCost(snapshot.getMemoryId(), snapshot.getQuestionRound(), cost);
    }
}
```

对于 `ROUND_FINAL`，`snapshot.getTotalTokens()` 就是这次提问的累计 token；对于 `STEP`，它是单轮 Loop 的 token。二者口径不同，做账单时建议只消费 `ROUND_FINAL`。

> 注意：不同厂商对 `cachedTokens` 与 `inputTokens` 的包含关系并不完全一致。上面的「输入减缓存」只是示意写法，真实计费应以 `TokenUsage` 归一后的 provider 口径为准；如果无法确认，宁可按 `inputTokens` 全额计价，避免低估成本。

### 6.4、场景四：自定义快照字段与前端节点

如果业务需要展示模型名称、工具来源、命中知识库等字段，可以扩展快照子类：

```java
public class BusinessTraceSnapshot extends AgentTraceSnapshot {

    private String modelName;
    private String toolSource;
    private String knowledgeBaseId;

    // getter / setter ...
}
```

然后在子类中间件的 `enrichSnapshot` 中填充：

```java
@Override
protected void enrichSnapshot(
        AgentTraceSnapshot snapshot, Map<String, Object> traceAttributes) {
    BusinessTraceSnapshot business = (BusinessTraceSnapshot) snapshot;
    business.setModelName((String) traceAttributes.get("model.name"));
    business.setToolSource((String) traceAttributes.get("tool.source"));
}
```

Collector 把子类序列化到 `snapshot_json` 后，前端可以直接读取这些字段。流程图和火焰图只需要在 `traceGraphAdapter` / `AgentTraceFlameView` 中增加对应展示，不需要改 Agent Core。

### 6.5、场景五：补充消息级快照

设计文档中提到了 `AgentTraceMessageSnapshot`，首版协议尚未包含。如果需要在 Trace 页面展示 AI 文本、思考内容和完整消息树，可以按以下方式扩展：

1. 在 `AgentTraceSnapshot` 中增加 `messages` 字段，或新增 `AgentTraceMessageSnapshot`；
2. 在 `afterModelCall` 中记录 `AiMessage` 的文本、思考内容和工具请求；
3. 在 `finishTool` 中记录 `ToolExecutionResultMessage` 的摘要；
4. 在 Collector 中继续做截断和脱敏。

示意：

```java
private void captureAiMessage(Step step, ChatResponse response) {
    if (response == null || response.aiMessage() == null) return;
    AiMessage aiMessage = response.aiMessage();
    step.aiText = truncate(aiMessage.text());
    step.aiThinking = truncate(aiMessage.thinking());
    step.toolRequests = aiMessage.toolExecutionRequests();
}
```

需要注意：

1. 工具参数和结果默认截断；
2. API Key、Authorization、Cookie 等敏感字段必须脱敏；
3. 不要把完整模型请求头写入 Trace；
4. 消息快照只用于展示，不能替代 ChatMemory。

### 6.6、场景六：统一 questionRound，消除 count 竞态

首版 `MysqlAgentTraceCollector` 在没有轮次时使用：

```java
Integer count = mapper.countFinishedBySessionId(snapshot.getMemoryId());
return (count == null ? 0 : count) + 1;
```

这在单用户、串行请求下可用，但同一会话并发请求时可能拿到相同轮次。设计文档推荐的做法是**在请求初始化、创建 AI 消息占位记录时分配 questionRound，并通过 `AgentRequest.extensions` 透传**：

```java
public static final String TRACE_QUESTION_ROUND = "agentforge.trace.questionRound";

// Service 请求初始化阶段
int questionRound = sessionService.nextQuestionRound(sessionId);
Map<String, Object> extensions = new HashMap<String, Object>();
extensions.put(TRACE_QUESTION_ROUND, questionRound);

request = AgentRequest.builder()
        .memoryId(sessionId)
        .question(question)
        .extensions(extensions)
        .build();
```

框架层在 `Runtime` 中增加 `questionRound` 字段，并在 `onInitComplete` 读取：

```java
private static class Runtime {
    // ... 原有字段
    private int questionRound;
}

Object round = context.getExtension(TRACE_QUESTION_ROUND);
if (round instanceof Number) {
    runtime.questionRound = ((Number) round).intValue();
}
```

`base()` 构造快照时：

```java
snapshot.setQuestionRound(runtime.questionRound);
```

Collector 优先使用快照上的轮次，只有缺失时才回退到 count：

```java
int round = snapshot.getQuestionRound() > 0
        ? snapshot.getQuestionRound()
        : resolveByCount(snapshot.getMemoryId());
```

这样轮次分配权回到业务层，Trace 不再通过数据库计数推断业务语义。

<br/>

## 七、已知边界与改进点

首版实现已经覆盖主链路，但仍有几处需要在使用时注意：

| 问题 | 现象 | 建议 |
|---|---|---|
| 非流式模型异常终态 | 非流式模型失败会走 `onModelCallError`，但 `Agent.run` 最终仍以 `onStop(NORMAL)` 收口，`ROUND_FINAL.status` 可能是 `NORMAL`，且 `error` 为空 | 在 `finish` 中检查最后一个 step 的 `error`，或根据 `StopResult` 的业务状态覆盖为 `ERROR` |
| `questionRound` 计数竞态 | 并发请求同一 session 时，`count + 1` 可能分配出相同轮次 | 按 6.6 的方式由业务层分配并通过 extensions 透传 |
| 没有消息级快照 | 协议只有 `question`、`tools`、`attributes`，没有 AI 文本和完整消息树 | 按 6.5 扩展 `messages`，或在业务层单独存储 |
| 并发工具 Map 线程安全 | `finishTool` 在工具线程池中执行，`step.tools` 是 `LinkedHashMap` | 改为 `ConcurrentHashMap`，或保证工具收口只在单线程执行 |
| 文本截断固定 | 所有文本统一截断到 4096 字符 | 抽成可配置项，按参数、结果、异常分别设置上限 |
| Collector 同步写库 | `MysqlAgentTraceCollector` 在 Agent 线程中同步落库 | 高并发场景改为异步队列 + 批量写入 |
| `STEP` 状态固定 `CONTINUE` | 单轮快照不表达最终业务状态 | 查询时以 `ROUND_FINAL` 为准，或增加 step 级状态字段 |
| 缺少采样与限流 | 所有请求都产生 Trace | 增加采样率、按 aid 开关、按数据量限流 |
| 查询无权限过滤 | 首版单用户环境未做用户归属校验 | 多用户接入后结合 Agent / Session 归属关系校验 |

另外，`modelDurationMs` 覆盖的是「模型调用 + 重试 + 退避」的总时间，不是单次 HTTP 请求耗时。如果需要区分首次调用和重试，可以实现 `onAiCallRetry` 记录重试节点，或在 `attributes` 中累计重试次数。

<br/>

## 八、总结

`AgentTraceMiddleware` 是 AgentForge 中间件体系的第二个观察型样本，和日志中间件的区别在于「输出协议」而不是「回调位置」：

1. **框架层只采集事实。**
   `AgentTraceMiddleware` 用已有生命周期回调，把一次运行拆成 `STEP` 和 `ROUND_FINAL` 快照，记录模型耗时、工具耗时、token、工具参数与结果、状态和异常。

2. **运行态跟着上下文走。**
   Runtime 挂在 `AgentChatContext.extensions`，天然按运行隔离，随上下文回收，不需要全局 Map 和 TTL 清理。

3. **输出走最小 SPI。**
   `AgentTraceCollector` 只有一个方法，框架不依赖 Spring、MySQL、MQ，存储和查询完全交给业务层。

4. **业务层通过继承扩展。**
   AgentForge Service 用 `AgentForgeTraceMiddleware` 增加 `aid` / `debug`，用 `AgentForgeTraceSnapshot` 增加强类型字段，用 `MysqlAgentTraceCollector` 落库，用 `AgentTraceQueryService` 按 `questionRound` 聚合，最终驱动流程图和火焰图。

5. **扩展不需要改内核。**
   增加租户维度、接 MQ/ES、做 token 成本核算、补充消息快照、统一 questionRound，都可以在中间件子类、快照子类或 Collector 层完成。

一句话概括：

> **Agent Core 负责把一次 ReAct Loop 讲清楚，业务层负责把多次 Loop 组织成用户能看懂的一次提问。**

这也是《Agent链路追踪设计实现（火焰图 & 流程图）》三层方案的落地方式：框架层提供稳定快照，Service 提供业务语义和持久化，UI 提供可视化。三者通过 `AgentTraceSnapshot` 解耦，后续无论换存储、换前端还是增加新的观测维度，都不需要重新侵入 ReAct 执行链路。

<br/>

## 参考资料

- 源码：
  - `agentforge-agent-core/src/main/java/cloud/changlu/agentforge/agent/extend/middlewares/trace/AgentTraceMiddleware.java`
  - `agentforge-agent-core/src/main/java/cloud/changlu/agentforge/agent/extend/middlewares/trace/AgentTraceCollector.java`
  - `agentforge-agent-core/src/main/java/cloud/changlu/agentforge/agent/extend/middlewares/trace/domain/AgentTraceSnapshot.java`
  - `agentforge-agent-core/src/main/java/cloud/changlu/agentforge/agent/extend/middlewares/trace/domain/AgentTraceToolSnapshot.java`
  - `agentforge-agent-core/src/main/java/cloud/changlu/agentforge/agent/extend/middlewares/trace/domain/AgentTraceSnapshotStatus.java`
- 契约：`component/middleware/IAgentMiddleware.java`、`IStreamingIAgentMiddleware.java`
- 分发器：`component/middleware/AgentMiddlewareManager.java`
- 业务扩展：
  - `agentforge-service-web/.../trace/middleware/AgentForgeTraceMiddleware.java`
  - `agentforge-service-web/.../trace/domain/AgentForgeTraceSnapshot.java`
  - `agentforge-service-web/.../trace/service/MysqlAgentTraceCollector.java`
  - `agentforge-service-web/.../trace/service/AgentTraceQueryService.java`
  - `agentforge-service-web/.../trace/controller/AgentTraceController.java`
- 建表脚本：`agentforge-service/sql/increment/1.0.x/20261029000000_v1.0.x.sql`
- 同系列：
  - [核心ReAct 框架设计，支持非流式、流式 一套调用.md](../%E6%A0%B8%E5%BF%83ReAct%20%E6%A1%86%E6%9E%B6%E8%AE%BE%E8%AE%A1%EF%BC%8C%E6%94%AF%E6%8C%81%E9%9D%9E%E6%B5%81%E5%BC%8F%E3%80%81%E6%B5%81%E5%BC%8F%20%E4%B8%80%E5%A5%97%E8%B0%83%E7%94%A8.md)
  - [标准Agent的middleware接口扩展实现.md](%E6%A0%87%E5%87%86Agent%E7%9A%84middleware%E6%8E%A5%E5%8F%A3%E6%89%A9%E5%B1%95%E5%AE%9E%E7%8E%B0.md)
  - [内置middleware扩展：日志Loggingmiddlware实现.md](%E5%86%85%E7%BD%AEmiddleware%E6%89%A9%E5%B1%95%EF%BC%9A%E6%97%A5%E5%BF%97Loggingmiddlware%E5%AE%9E%E7%8E%B0.md)
  - [Agent链路追踪设计实现（火焰图 & 流程图）.md](<../../agentforge-service核心业务模块/后端/agent基础设施/agent链路追踪/Agent链路追踪设计实现（火焰图 & 流程图）.md>)

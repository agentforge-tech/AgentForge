# AgentForge ReAct 层03、内置 Middleware 扩展：日志 LoggingIAgentMiddleware 实现

> 适用版本：release_1.x
> 适用模块：`agentforge-framework/agentforge-agent-core`
> 源码位置：`agent/extend/middlewares/LoggingIAgentMiddleware.java`
> 包前缀：`cloud.changlu.agentforge.agent.extend.middlewares`
> 维护者：长路

系列定位：本文属于「AgentForge 核心模块设计原理文章系列 / 02、通用 ReAct Agent 层 / middleware」。前两篇讲了 ReAct 内核与中间件契约，本篇用一个真实内置实现收尾：

> **`LoggingIAgentMiddleware` 如何在不改变任何执行结果的前提下，把每一轮模型调用、工具执行、重试和最终停止，变成可读、可统计、可定位问题的日志。**

<br/>

## 文档导航

| 文档 | 说明 |
|---|---|
| [核心ReAct 框架设计，支持非流式、流式 一套调用.md](../%E6%A0%B8%E5%BF%83ReAct%20%E6%A1%86%E6%9E%B6%E8%AE%BE%E8%AE%A1%EF%BC%8C%E6%94%AF%E6%8C%81%E9%9D%9E%E6%B5%81%E5%BC%8F%E3%80%81%E6%B5%81%E5%BC%8F%20%E4%B8%80%E5%A5%97%E8%B0%83%E7%94%A8.md) | 第 01 篇：ReAct 分层、非流式主循环、流式递归驱动、状态机 |
| [标准Agent的middleware接口扩展实现.md](%E6%A0%87%E5%87%86Agent%E7%9A%84middleware%E6%8E%A5%E5%8F%A3%E6%89%A9%E5%B1%95%E5%AE%9E%E7%8E%B0.md) | 第 02 篇：中间件契约、管理器分发、流式/非流式接入 |
| 当前文章（Logging 实现） | 观察型中间件、会话级统计、核心日志示范、流式与并发边界 |

<br/>

## 目录

- [一、背景与定位：为什么第一个内置中间件是日志](#一背景与定位为什么第一个内置中间件是日志)
- [二、核心实现：按 memoryId 隔离的统计与源码拆解](#二核心实现按-memoryid-隔离的统计与源码拆解)
- [三、核心日志打印示范](#三核心日志打印示范)
- [四、流式与并发工具下的行为](#四流式与并发工具下的行为)
- [五、接入、测试与改进点](#五接入测试与改进点)
- [六、总结](#六总结)

---

## 一、背景与定位：为什么第一个内置中间件是日志

### 1.1、日志中间件要回答的三个问题

Agent 出问题时，最典型的三类疑问是：

1. **模型到底调用了几次？每次耗时多久？**
2. **这一轮调用了哪些工具？参数是什么？返回了什么？**
3. **这次会话为什么结束？是正常回答、被取消，还是达到最大步数？**

如果没有中间件，这些问题只能靠模型 Provider 的 HTTP 日志、工具执行器日志、业务层最终结果三处拼，缺少「第几轮、属于哪个会话」的 ReAct 语义。`LoggingIAgentMiddleware` 的目标，就是把这些信息合成一条**按会话组织的、带轮次与耗时的时间线**。

### 1.2、设计定位：只观察，不改写

它只实现 `IAgentMiddleware`，没有实现 `IStreamingIAgentMiddleware`：

```java
public class LoggingIAgentMiddleware implements IAgentMiddleware {
```

这意味着它不会逐 token 打印，也不会接收 `onIntermediateResponse`；逐 token 的观测应交给专门的流式中间件或指标系统。

所有有返回值的回调都原样返回，保证「可随时摘掉」：

```java
@Override
public ChatRequest beforeModelCall(
        int currentStep, ChatRequest chatRequest, AgentChatContext chatContext) {
    // 记录开始时间、消息数...
    return chatRequest;
}

@Override
public String afterToolExecution(
        ToolExecutionRequest toolRequest, String toolResult, AgentChatContext chatContext) {
    // 记录耗时、结果...
    return toolResult;
}
```

### 1.3、覆盖的回调与边界

| 回调 | 是否实现 | 用途 |
|---|---|---|
| `onInitComplete` | ✅ | 记录会话开始时间与问题 |
| `beforeModelCall` / `afterModelCall` | ✅ | 模型耗时、token、finishReason、工具调用数、文本 |
| `onModelCallError` | ✅ | 记录模型调用最终失败（仅非流式） |
| `onAiCallRetry` | ✅ | 记录重试次数、退避时间、异常（仅非流式） |
| `beforeToolExecution` / `afterToolExecution` | ✅ | 工具参数、耗时、结果（仅串行） |
| `onToolExecutionError` | ✅ | 记录工具异常 |
| `onStop` / `onStopWithError` | ✅ | 会话汇总与状态清理 |
| `beforeLoop` / `afterLoop` / `onLoopError` | ❌ | 由模型/工具回调间接覆盖 |
| 并发工具三件套 | ❌ | 当前实现未覆盖，属于已知盲区 |
| 流式增量事件 | ❌ | 刻意不逐 token 打印 |

<br/>

## 二、核心实现：按 memoryId 隔离的统计与源码拆解

### 2.1、一次会话一张统计表

`ReActAgent` 实例会被缓存复用，中间件又挂在 Agent 上，所以**同一个中间件会被多个会话共享**。所有状态必须按 `memoryId` 隔离：

```java
private final Map<Object, SessionStats> sessionStatsMap =
        new ConcurrentHashMap<Object, SessionStats>();
```

`SessionStats`（`LoggingIAgentMiddleware.java:295`）分两类字段：

```java
private static class SessionStats {
    long sessionStartTime;

    final AtomicLong totalModelCallTime = new AtomicLong(0);
    final AtomicLong totalToolExecutionTime = new AtomicLong(0);

    final AtomicInteger totalInputTokens = new AtomicInteger(0);
    final AtomicInteger totalOutputTokens = new AtomicInteger(0);
    final AtomicInteger totalTokens = new AtomicInteger(0);

    final Map<String, Long> toolStartMap = new ConcurrentHashMap<String, Long>();
    final Map<String, Long> modelStartMap = new ConcurrentHashMap<String, Long>();
}
```

- **累计值**：`AtomicLong` / `AtomicInteger`，用于汇总；
- **进行中的开始时间**：`toolStartMap` 按工具 key 记录，`modelStartMap` 按步骤号记录，用于完成回调里计算耗时。

懒创建时用 `putIfAbsent`，避免两个线程同时首次进入互相覆盖：

```java
private SessionStats stats(AgentChatContext chatContext) {
    Object memoryId = memoryId(chatContext);
    SessionStats stats = sessionStatsMap.get(memoryId);
    if (stats == null) {
        stats = new SessionStats();
        SessionStats previous = sessionStatsMap.putIfAbsent(memoryId, stats);
        if (previous != null) {
            stats = previous;
        }
    }
    return stats;
}
```

key 的设计：

| Map | key | 说明 |
|---|---|---|
| `sessionStatsMap` | `memoryId` | 会话隔离 |
| `toolStartMap` | `toolRequest.id()`，为空时退化为 `name()` | 同一次工具调用前后配对 |
| `modelStartMap` | `String.valueOf(currentStep)` | 每一轮模型调用前后配对 |

### 2.2、会话开始与模型调用

`onInitComplete` 每次运行只触发一次：

```java
@Override
public void onInitComplete(AgentChatContext chatContext) {
    stats(chatContext).sessionStartTime = System.currentTimeMillis();
    logger.info(
            "[会话开始] memoryId: " + memoryId(chatContext)
                    + ", question: " + truncate(question(chatContext), MAX_CONTENT_LENGTH));
}
```

模型调用前记录开始时间与消息数：

```java
@Override
public ChatRequest beforeModelCall(
        int currentStep, ChatRequest chatRequest, AgentChatContext chatContext) {
    if (chatRequest != null) {
        stats(chatContext).modelStartMap.put(
                modelKey(currentStep), System.currentTimeMillis());
        logger.info("[模型调用开始] 步骤: " + currentStep
                + ", 消息数: " + chatRequest.messages().size()
                + ", memoryId: " + memoryId(chatContext));
    }
    return chatRequest;
}
```

模型调用后是信息最密集的一段（`LoggingIAgentMiddleware.java:140`，节选）：

```java
Long start = stats.modelStartMap.remove(modelKey(currentStep));
long cost = start == null ? 0L : System.currentTimeMillis() - start;
stats.totalModelCallTime.addAndGet(cost);

StringBuilder builder = new StringBuilder();
builder.append("[模型调用完成] 步骤: ").append(currentStep)
        .append(", 耗时: ").append(cost).append("ms");

TokenUsage tokenUsage = chatResponse.tokenUsage();
if (tokenUsage != null) {
    stats.totalInputTokens.addAndGet((int) tokenUsage.inputTokens());
    stats.totalOutputTokens.addAndGet((int) tokenUsage.outputTokens());
    stats.totalTokens.addAndGet((int) tokenUsage.totalTokens());
    builder.append(", token: ").append(tokenUsage.inputTokens())
            .append("/").append(tokenUsage.outputTokens())
            .append("/").append(tokenUsage.totalTokens());
}

FinishReason finishReason = chatResponse.finishReason();
if (finishReason != null) {
    builder.append(", finishReason: ").append(finishReason);
}
if (chatResponse.aiMessage() != null) {
    builder.append(", 工具调用数: ")
            .append(chatResponse.aiMessage().toolExecutionRequests().size());
    builder.append(", 文本: ")
            .append(truncate(chatResponse.aiMessage().text(), MAX_CONTENT_LENGTH));
}
builder.append(", memoryId: ").append(memoryId(chatContext));
logger.info(builder.toString());

return chatResponse;
```

三个细节：先 `remove` 再计算，避免重复进入把开始时间留在 Map；`start` 缺失时耗时记为 0；模型文本截断到 500 字符，避免日志被长回答淹没。

### 2.3、工具执行：前后配对，失败清理

```java
@Override
public void beforeToolExecution(
        ToolExecutionRequest toolRequest, AgentChatContext chatContext) {
    logger.info("[工具执行开始] 工具: " + toolRequest.name()
            + ", 参数: " + truncate(toolRequest.arguments(), MAX_CONTENT_LENGTH)
            + ", memoryId: " + memoryId(chatContext));
    stats(chatContext).toolStartMap.put(
            toolKey(toolRequest), System.currentTimeMillis());
}

@Override
public String afterToolExecution(
        ToolExecutionRequest toolRequest, String toolResult, AgentChatContext chatContext) {
    Long start = stats(chatContext).toolStartMap.remove(toolKey(toolRequest));
    long cost = start == null ? 0L : System.currentTimeMillis() - start;
    stats(chatContext).totalToolExecutionTime.addAndGet(cost);

    logger.info("[工具执行完成] 工具: " + toolRequest.name()
            + ", 耗时: " + cost + "ms"
            + ", 结果: " + truncate(toolResult, MAX_TOOL_RESULT_LENGTH)
            + ", memoryId: " + memoryId(chatContext));
    return toolResult;   // 只观察不改写
}
```

工具失败时不会进入 `afterToolExecution`，而是进入 `onToolExecutionError`：移除开始时间、打印 WARNING。失败工具的耗时不会累加到 `totalToolExecutionTime`，但 Map 不会泄漏。

> **注意**：这组回调只在串行工具模式下触发。并发工具批次走的是 `beforeConcurrentToolExecution` / `onConcurrentToolExecutionComplete` / `afterConcurrentToolExecution`，当前日志中间件未实现，详见第四节。

### 2.4、模型异常与重试

非流式路径下，模型最终失败会触发 `onModelCallError`：

```java
@Override
public void onModelCallError(
        int currentStep, ChatRequest chatRequest, Throwable error,
        AgentChatContext chatContext) {
    stats(chatContext).modelStartMap.remove(modelKey(currentStep));
    logger.log(Level.WARNING,
            "[模型调用异常] 步骤: " + currentStep
                    + ", memoryId: " + memoryId(chatContext), error);
}
```

每次准备重试前触发 `onAiCallRetry`，记录第几次重试、退避多久、上一次异常类型与消息：

```java
logger.warning("[AI调用重试] 步骤: " + currentStep
        + ", 重试次数: " + retryCount + "/" + maxRetries
        + ", 等待: " + delayMs + "ms"
        + ", 异常: " + (lastException != null
                ? lastException.getClass().getSimpleName() : "null")
        + ", memoryId: " + memoryId(chatContext)
        + ", error: " + (lastException != null
                ? truncate(lastException.getMessage(), 200) : "null"));
```

> **注意**：流式路径没有接入 `onModelCallError` 与 `onAiCallRetry`。`ReActTokenStream` 在模型 `onError` 时走 `onLoopError` + `onStopWithError`；重试则完全未接入。

### 2.5、会话结束：汇总与清理

正常结束 / 取消 / 最大步数都会进入 `onStop`（`LoggingIAgentMiddleware.java:202`）：

```java
@Override
public void onStop(int currentStep, StopResult stopResult, AgentChatContext chatContext) {
    SessionStats stats = stats(chatContext);
    StopResultState state = stopResult == null ? null : stopResult.getStopResultState();
    logger.info("[会话结束] 步骤: " + currentStep
            + ", 状态: " + (state == null ? "null" : state.getDescription())
            + ", 总耗时: " + (System.currentTimeMillis() - stats.sessionStartTime) + "ms"
            + ", 模型耗时: " + stats.totalModelCallTime.get() + "ms"
            + ", 工具耗时: " + stats.totalToolExecutionTime.get() + "ms"
            + ", token(输入/输出/总计): "
            + stats.totalInputTokens.get() + "/"
            + stats.totalOutputTokens.get() + "/"
            + stats.totalTokens.get()
            + ", runRes: " + truncate(
                    stopResult == null ? null : stopResult.getRunRes(), MAX_CONTENT_LENGTH)
            + ", memoryId: " + memoryId(chatContext));

    // 会话结束后释放统计，避免长生命周期Agent实例累积
    sessionStatsMap.remove(memoryId(chatContext));
}
```

异常结束走 `onStopWithError`，同样打印耗时汇总并在最后 `sessionStatsMap.remove(memoryId)`。这是必须的：Agent 实例生命周期远长于一次会话，不清理会持续累积。

<br/>

## 三、核心日志打印示范

### 3.1、示范代码

```java
ReActAgent agent = ReActAgent.builder()
        .agentName("weather-agent")
        .systemPrompt("你是一个天气助手")
        .chatModel(chatModel)
        .chatMemoryProvider(new MessageWindowChatMemoryProvider(100))
        .toolService(toolService)
        .middleware(new LoggingIAgentMiddleware())
        .build();

ChatResult result = agent.run(AgentRequest.builder()
        .memoryId("user-1")
        .question("杭州天气怎么样？")
        .build());
```

场景：模型第 1 轮返回 `getWeather` 工具调用，第 2 轮给出最终回答。

### 3.2、核心日志输出

以下省略 JUL 的时间戳与类名前缀，只保留日志正文；耗时与 token 为示意值：

```text
[会话开始] memoryId: user-1, question: 杭州天气怎么样？

[模型调用开始] 步骤: 1, 消息数: 2, memoryId: user-1
[模型调用完成] 步骤: 1, 耗时: 820ms, token: 120/35/155,
              finishReason: TOOL_EXECUTION, 工具调用数: 1,
              文本: , memoryId: user-1

[工具执行开始] 工具: getWeather, 参数: {"city":"Hangzhou"}, memoryId: user-1
[工具执行完成] 工具: getWeather, 耗时: 12ms,
              结果: 22 degrees Celsius, memoryId: user-1

[模型调用开始] 步骤: 2, 消息数: 4, memoryId: user-1
[模型调用完成] 步骤: 2, 耗时: 650ms, token: 180/24/204,
              finishReason: STOP, 工具调用数: 0,
              文本: 杭州今天22度，晴。, memoryId: user-1

[会话结束] 步骤: 2, 状态: normal stop,
          总耗时: 1490ms, 模型耗时: 1470ms, 工具耗时: 12ms,
          token(输入/输出/总计): 300/59/359,
          runRes: 杭州今天22度，晴。, memoryId: user-1
```

### 3.3、如何读这条日志

| 日志段 | 能回答的问题 |
|---|---|
| `[会话开始]` | 哪个会话、用户问了什么，作为整条链路起点 |
| `[模型调用完成]` | 本轮模型耗时、输入/输出 token、是否要求调用工具、模型文本 |
| `[工具执行开始/完成]` | 调用了哪个工具、参数、返回、耗时 |
| `[会话结束]` | 为什么结束、总耗时、模型/工具耗时占比、累计 token、最终结果 |

这条时间线直接对应 ReAct 的一轮 `think + act`：

```text
会话开始
  ├── 第 1 轮 think：模型返回工具调用
  │      └── act：执行 getWeather，结果写回记忆
  └── 第 2 轮 think：模型返回最终回答
         └── 停止
```

<br/>

## 四、流式与并发工具下的行为

### 4.1、流式路径：生命周期可用，增量事件缺失

它没有实现 `IStreamingIAgentMiddleware`，但流式路径仍会触发已实现的回调：

| 回调 | 流式下是否触发 | 说明 |
|---|---|---|
| `onInitComplete` | ✅ | `ReActTokenStream.start()` 触发 |
| `beforeModelCall` / `afterModelCall` | ✅ | 每轮 `chat()` / `onRoundComplete()` 触发 |
| `onModelCallError` | ❌ | 流式用 `onStopWithError` + `onError` 收敛 |
| `onAiCallRetry` | ❌ | 流式当前未接入重试 |
| `beforeToolExecution` / `afterToolExecution` | ✅（串行） | 共用 `AgentToolExecutor` |
| `onToolExecutionError` | ✅ | 工具异常时触发 |
| `onPartialResponse` / `onPartialThinking` | ❌ | 未实现子接口，拿不到增量 token |
| `onIntermediateResponse` | ❌ | 未实现子接口，拿不到中间响应 |
| `onStop` / `onStopWithError` | ✅ | 正常/取消/最大步数/异常结束时触发 |

因此流式下仍能拿到每轮模型耗时、token、串行工具耗时、会话汇总；缺的是首 token 时间、每秒 token 数、重试日志。需要这些指标时，另写一个 `IStreamingIAgentMiddleware` 即可。

### 4.2、并发工具：当前实现看不到单工具日志

`AgentSettings` 默认开启并发工具执行。一轮返回多个工具调用时，执行器走并发批次，只触发批次回调：

```text
beforeConcurrentToolExecution
onConcurrentToolExecutionComplete
afterConcurrentToolExecution
```

而日志中间件只实现了串行的 `beforeToolExecution` / `afterToolExecution`。结果是：**并发批次下看不到任何工具开始/完成日志，也不累计工具耗时**；模型耗时与 token 统计不受影响。

补齐方式：实现 `beforeConcurrentToolExecution` 与 `onConcurrentToolExecutionComplete`，在批次开始时把每个工具的 `toolKey` 写入 `toolStartMap`，在单个工具完成时移除、累计并打印。注意这些回调发生在工具线程池里，Map 必须是并发 Map（当前已是 `ConcurrentHashMap`），日志拼接也应尽量轻量。

<br/>

## 五、接入、测试与改进点

### 5.1、接入与顺序

```java
ReActAgent agent = ReActAgent.builder()
        .chatModel(chatModel)
        .streamingChatModel(streamingChatModel)
        .chatMemoryProvider(new MessageWindowChatMemoryProvider(100))
        .toolService(toolService)
        .middleware(new LoggingIAgentMiddleware())
        .build();
```

中间件按注册顺序执行：

```java
.middleware(new LoggingIAgentMiddleware())
.middleware(new ToolMaskingMiddleware())
```

日志中间件放前面，记录的是原始输入；放后面，记录的是前序中间件改写后的结果。按你需要观测的口径选择。

### 5.2、测试

`ReActAgentMiddlewareTest.shouldRunWithLoggingMiddleware` 验证「加了日志中间件后执行结果不变」：

```java
ScriptedChatModel chatModel = new ScriptedChatModel()
        .enqueueToolCall("call_1", "getWeather", "{\"city\":\"Hangzhou\"}")
        .enqueueText("Hangzhou今天22度，晴。");

ChatResult result = agent(chatModel, settings(0), new LoggingIAgentMiddleware())
        .run(request("杭州天气怎么样？"));

assertEquals(AgentRunState.SUCCESS, result.getRunState());
assertEquals("Hangzhou今天22度，晴。", result.getRes());
```

日志内容本身属于观察数据，不应成为业务断言的耦合点。

### 5.3、已知改进点

| 问题 | 说明 | 建议 |
|---|---|---|
| token 强转 `int` | `TokenUsage` 是 `long`，累加用 `AtomicInteger` 极端情况会溢出 | 改为 `AtomicLong` |
| 即时字符串拼接 | 日志级别关闭时仍会完成拼接 | `logger.isLoggable` 或参数化日志 |
| 统计口径不完整 | 失败模型调用、失败工具、并发工具均未计入耗时 | 需要精确对账时补齐 |
| 依赖停止回调清理 | 新增终止路径若不走 `onStop`/`onStopWithError` 会泄漏 | 扩展终止路径时同步检查 |
| 文本日志不利于聚合 | 需要正则解析 | 改为结构化日志或接指标系统 |

### 5.4、可扩展方向

- **补并发工具回调**：让并发批次也有工具日志与耗时；
- **补流式指标**：实现 `IStreamingIAgentMiddleware`，统计 TTFT、输出 token 速率、思考/正文比例；
- **结构化日志**：输出 JSON，直接接 ELK / Loki；
- **接指标系统**：在 `onStop` 把会话耗时、模型耗时、工具耗时、token 上报 Micrometer / Prometheus。

<br/>

## 六、总结

`LoggingIAgentMiddleware` 是一个「小而完整」的观察型中间件样本：

1. **只实现 `IAgentMiddleware`**，用生命周期回调覆盖会话、模型、工具、停止四个阶段；
2. **按 `memoryId` 隔离统计**，用 `ConcurrentHashMap` + `AtomicXxx` 管理会话状态；
3. **只观察不改写**，所有返回值原样透传，保证可随时摘除；
4. **用开始时间 Map 配对计算耗时**，成功累计、失败清理，结束时统一汇总并释放；
5. **边界明确**：不逐 token 打印、不覆盖并发工具、不接入流式重试。

它验证了中间件设计的核心命题：

> **横切逻辑不需要侵入 ReAct 内核，也能拿到足够完整的执行时序。**

当这套日志不够用时，扩展路径也很清晰：补并发回调、补流式指标、换结构化输出、接指标系统——每一步都只是在中间件层增加实现，不需要改动 `Agent.run()` 或 `ReActTokenStream`。

<br/>

## 参考资料

- 源码：`agentforge-agent-core/src/main/java/cloud/changlu/agentforge/agent/extend/middlewares/LoggingIAgentMiddleware.java`
- 契约：`component/middleware/IAgentMiddleware.java`、`IStreamingIAgentMiddleware.java`
- 分发器：`component/middleware/AgentMiddlewareManager.java`
- 测试：`ReActAgentMiddlewareTest.shouldRunWithLoggingMiddleware`
- 同系列：
  - [核心ReAct 框架设计，支持非流式、流式 一套调用.md](../%E6%A0%B8%E5%BF%83ReAct%20%E6%A1%86%E6%9E%B6%E8%AE%BE%E8%AE%A1%EF%BC%8C%E6%94%AF%E6%8C%81%E9%9D%9E%E6%B5%81%E5%BC%8F%E3%80%81%E6%B5%81%E5%BC%8F%20%E4%B8%80%E5%A5%97%E8%B0%83%E7%94%A8.md)
  - [标准Agent的middleware接口扩展实现.md](%E6%A0%87%E5%87%86Agent%E7%9A%84middleware%E6%8E%A5%E5%8F%A3%E6%89%A9%E5%B1%95%E5%AE%9E%E7%8E%B0.md)

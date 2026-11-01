# AgentForge ReAct 层02、标准 Agent 的 Middleware 接口扩展实现：把横切逻辑收敛成一条可组合回调链

> 适用版本：release_1.x
> 适用模块：`agentforge-framework/agentforge-agent-core`
> 包前缀：`cloud.changlu.agentforge.agent.component.middleware`
> 维护者：长路

系列定位：本文属于「AgentForge 核心模块设计原理文章系列 / 02、通用 ReAct Agent 层」，是 ReAct 内核的第 02 篇。上一篇拆开了 `run()` / `runStream()` 两条驱动器，本篇专门拆中间件：

1. `IAgentMiddleware` 为什么把所有方法都设计成 `default`；
2. `AgentMiddlewareManager` 如何按注册顺序分发回调、隔离异常、支持「观察 / 改写 / 中断」三种语义；
3. 非流式与流式两条路径，分别把回调插在哪些精确的代码位置；
4. 新增一个中间件、甚至新增一种回调，应该怎么扩展。

一句话结论：

> **AgentForge 的中间件不是带 `next()` 的洋葱模型，而是与 Agent 生命周期对齐的、按注册顺序扇出的回调链；它用「异常吞掉、返回 `null` 中断、返回值沿链传递」三种约定，把横切逻辑挡在 ReAct 内核之外。**

<br/>

## 先看结论：流式与非流式下的中间件

同一个中间件，在两条路径上共享契约和管理器，但触发时机与错误收敛方式不同。先给一张速览表，后面的第六、七节再逐行对照源码。

| 维度 | 非流式 `run()` | 流式 `runStream()` |
|---|---|---|
| 契约 | `IAgentMiddleware` | `IAgentMiddleware` + `IStreamingIAgentMiddleware` |
| 初始化 | `Agent.run()` 触发 `onInitComplete` | `ReActTokenStream.start()` 触发 |
| 轮次推进 | `while` 循环 | `onCompleteResponse` 内递归 `chat(step + 1)` |
| 轮次回调 | `beforeLoop` / `afterLoop` | 同左，时机等价 |
| 模型回调 | `beforeModelCall` / `afterModelCall` / `onModelCallError` / `onAiCallRetry` | 前三者相同；`onAiCallRetry` 当前未接入 |
| 增量事件 | 无 | `onPartialResponse` / `onPartialThinking` |
| 工具轮中间事件 | 无 | `onIntermediateResponse` |
| 工具回调 | 串行走单工具回调；并发走批次回调 | 完全相同（共用 `AgentToolExecutor`） |
| `beforeModelCall` 返回 `null` | 结束为 `SUCCESS`，触发 `onStop(NORMAL)` | 结束为 `CANCEL`，触发 `onStop(CANCEL)` + `onError` |
| 模型调用失败 | `onModelCallError` + `onStop(NORMAL)`，`runState = MODEL_CALL_ERROR` | `onStopWithError` + `onError` |
| 最终结果 | `ChatResult` | `onCompleteResponse(ChatResponse)` |

三条落地建议：

1. **只写一个 `IAgentMiddleware`，两条路径都能用。** 生命周期与工具回调在流式下同样触发，不需要为流式重复实现。
2. **需要 token 或中间响应时，才实现 `IStreamingIAgentMiddleware`。** 只做日志、脱敏、Trace 的中间件保持 `IAgentMiddleware` 即可；实现了子接口但不想接收增量事件，可让 `handlesStreamingEvents()` 返回 `false`。
3. **工具回调要同时覆盖串行与并发。** 并发批次只触发 `beforeConcurrentToolExecution` / `onConcurrentToolExecutionComplete` / `afterConcurrentToolExecution`，不会触发单工具的 `beforeToolExecution` / `afterToolExecution`。

> **注意**：不要假设两条路径的错误回调完全对称。非流式的模型调用失败会被 `think()` 收敛成 `STOP`，因此 `onStop` 收到 `NORMAL`；流式则走 `onStopWithError`。需要统一告警时，优先在 `onModelCallError` 与 `onStopWithError` 两处都做兜底。

<br/>

## 文档导航

| 文档 | 说明 |
|---|---|
| [核心ReAct 框架设计，支持非流式、流式 一套调用.md](../%E6%A0%B8%E5%BF%83ReAct%20%E6%A1%86%E6%9E%B6%E8%AE%BE%E8%AE%A1%EF%BC%8C%E6%94%AF%E6%8C%81%E9%9D%9E%E6%B5%81%E5%BC%8F%E3%80%81%E6%B5%81%E5%BC%8F%20%E4%B8%80%E5%A5%97%E8%B0%83%E7%94%A8.md) | 第 01 篇：ReAct 分层、非流式主循环、流式递归驱动、状态机 |
| 当前文章（标准 Agent Middleware 原理） | 中间件契约、管理器分发、流式/非流式接入、扩展点与最佳实践 |
| [内置middleware扩展：日志Loggingmiddlware实现.md](%E5%86%85%E7%BD%AEmiddleware%E6%89%A9%E5%B1%95%EF%BC%9A%E6%97%A5%E5%BF%97Loggingmiddlware%E5%AE%9E%E7%8E%B0.md) | 第 03 篇：日志中间件、会话级统计、耗时与 token 汇总 |

<br/>

## 目录

- [先看结论：流式与非流式下的中间件](#先看结论流式与非流式下的中间件)
- [一、背景：横切逻辑为什么不能写进 Agent 主循环](#一背景横切逻辑为什么不能写进-agent-主循环)
- [二、设计目标与约束](#二设计目标与约束)
- [三、契约层：两套接口，一份生命周期](#三契约层两套接口一份生命周期)
- [四、管理器：注册、排序与分发](#四管理器注册排序与分发)
- [五、三种语义：观察、改写、中断](#五三种语义观察改写中断)
- [六、非流式接入点：回调到底插在哪](#六非流式接入点回调到底插在哪)
- [七、流式接入点：增量事件与中间响应](#七流式接入点增量事件与中间响应)
- [八、实战：两个内置中间件](#八实战两个内置中间件)
- [九、手写一个中间件](#九手写一个中间件)
- [十、并发、生命周期与线程安全](#十并发生命周期与线程安全)
- [十一、扩展指南：新增一个回调要改哪些地方](#十一扩展指南新增一个回调要改哪些地方)
- [十二、测试如何验证语义](#十二测试如何验证语义)
- [十三、总结](#十三总结)

---

## 一、背景：横切逻辑为什么不能写进 Agent 主循环

当 Agent 从 Demo 走向生产，主循环周围会迅速长出一堆「与推理本身无关，但每个阶段都要插一脚」的需求：

- **可观测性**：每轮模型耗时、工具耗时、token 用量、完整链路 Trace；
- **安全与合规**：工具参数脱敏、工具结果脱敏、敏感问题拦截；
- **审计**：谁在什么会话里调用了哪个工具、参数是什么；
- **稳定性**：模型限流、重试告警、超时统计；
- **业务扩展**：把租户信息、请求 ID、业务标签透传到工具执行层。

如果没有扩展点，通常只有三种结局：

1. **直接改 `ReActAgent`**：每加一个需求就动一次核心类，回归范围不可控；
2. **继承派生**：`LoggingReActAgent`、`TraceReActAgent`、`MaskingReActAgent`……类爆炸；
3. **在业务层包一层**：只能看到最终结果，看不到每一轮 `think` / `act` 的内部时序。

AgentForge 选择第四种：**在 ReAct 内核的关键生命周期上开放一组回调，由中间件实现横切逻辑。**

<br/>

## 二、设计目标与约束

这套中间件设计要同时满足六个目标：

| 目标 | 落地方式 |
|---|---|
| 不破坏已有实现 | 所有回调都是 `default` 空实现，新增回调不强制改老代码 |
| 非流式/流式共用 | 生命周期回调放在 `IAgentMiddleware`，流式增量事件放到子接口 |
| 顺序可预期 | 严格按注册顺序分发，不引入隐式优先级 |
| 失败不影响主流程 | 每个回调独立 `try/catch`，记录日志后继续 |
| 既能观察也能改写 | 有返回值的回调把结果沿链传递，形成可组合的转换链 |
| 能主动中断 | `beforeModelCall` 返回 `null` 即中断本轮模型调用 |

同时保留两条工程约束：

- **零第三方依赖**：只用 JDK 的集合、日志、并发工具；
- **Java 8 语法兼容**：接口默认方法、`Consumer`、`ConcurrentHashMap`，不使用更晚的语言特性。

<br/>

## 三、契约层：两套接口，一份生命周期

### 3.1、`IAgentMiddleware`：生命周期回调

`IAgentMiddleware`（`IAgentMiddleware.java:17`）定义了 16 个回调，按阶段可以分成五组：

```text
初始化
  └── onInitComplete

Loop 生命周期
  ├── beforeLoop
  ├── afterLoop
  └── onLoopError

模型调用
  ├── beforeModelCall      （可改写、可中断）
  ├── afterModelCall       （可改写）
  ├── onModelCallError
  └── onAiCallRetry

工具执行
  ├── beforeToolExecution
  ├── afterToolExecution   （可改写）
  ├── onToolExecutionError
  ├── beforeConcurrentToolExecution
  ├── onConcurrentToolExecutionComplete
  └── afterConcurrentToolExecution （可改写）

停止
  ├── onStop
  └── onStopWithError
```

对应的触发时机：

| 回调 | 触发时机 | 返回值语义 |
|---|---|---|
| `onInitComplete` | 上下文构建完成、正式循环开始前，每次运行一次 | 无 |
| `beforeLoop` | 每一轮 loop 开始前 | 无 |
| `afterLoop` | 每一轮 loop 结束后（工具执行完或模型直接回答） | 无 |
| `onLoopError` | 单轮执行抛异常时 | 无 |
| `beforeModelCall` | 真正发起模型请求前 | 返回新请求；返回 `null` 中断 |
| `afterModelCall` | 模型响应返回后、写回记忆前 | 返回新响应；`null` 回退原始响应 |
| `onModelCallError` | 模型调用最终失败时 | 无 |
| `onAiCallRetry` | 每次准备重试前 | 无 |
| `beforeToolExecution` | 单个工具执行前（串行模式） | 无 |
| `afterToolExecution` | 单个工具成功后（串行模式） | 返回新结果，沿链传递 |
| `onToolExecutionError` | 工具执行抛异常时 | 无 |
| `beforeConcurrentToolExecution` | 并发批次开始前 | 无 |
| `onConcurrentToolExecutionComplete` | 并发批次中单个工具完成时（仅观测） | 无 |
| `afterConcurrentToolExecution` | 并发批次全部完成后 | 返回新结果列表，沿链传递 |
| `onStop` | 正常结束 / 取消 / 达到最大步数 | 无 |
| `onStopWithError` | 非中断类异常导致停止 | 无 |

### 3.2、为什么全部是 `default`

接口里所有方法都写成：

```java
default void beforeLoop(int currentStep, AgentChatContext chatContext) {}
```

这带来三个直接好处：

1. **新增回调不破坏实现类**：老中间件不需要补空方法；
2. **按需实现**：一个只关心工具脱敏的中间件，不需要感知模型重试；
3. **契约可演进**：后续版本增加新阶段，只要给一个合理的默认值，就是源码兼容的。

这也是为什么 `IAgentMiddleware` 能稳定承载 16 个回调，而不是拆成一堆细粒度接口。

### 3.3、`IStreamingIAgentMiddleware`：流式事件子接口

流式增量事件只对部分中间件有意义，因此被拆到子接口（`IStreamingIAgentMiddleware.java:12`）：

```java
public interface IStreamingIAgentMiddleware extends IAgentMiddleware {

    default void onPartialResponse(
            int currentStep, String partialResponse, AgentChatContext chatContext) {}

    default void onPartialThinking(
            int currentStep, PartialThinking partialThinking, AgentChatContext chatContext) {}

    default void onIntermediateResponse(
            int currentStep, ChatResponse intermediateResponse, AgentChatContext chatContext) {}

    default boolean handlesStreamingEvents() {
        return true;
    }
}
```

三个事件的语义：

| 事件 | 时机 | 典型用途 |
|---|---|---|
| `onPartialResponse` | 模型每生成一段正文增量 | 前端逐字渲染、增量落库 |
| `onPartialThinking` | 模型每生成一段思考/推理增量 | 展示推理过程、思考链审计 |
| `onIntermediateResponse` | 本轮响应包含工具调用，且工具执行完成、下一轮思考前 | 渲染工具调用卡片、记录中间响应 |

`handlesStreamingEvents()` 是一个性能开关：返回 `false` 的中间件不会进入流式事件列表，**但它仍然会收到 `beforeLoop`、`beforeModelCall` 等生命周期回调**。换句话说，它过滤的只是「增量事件」，不是「整个中间件」。

### 3.4、一个容易忽略的边界

`IAgentMiddleware` 是「生命周期」，`IStreamingIAgentMiddleware` 是「生命周期 + 增量事件」。因此：

- 只实现 `IAgentMiddleware` 的中间件，在流式路径下**不会**收到 `partial` / `intermediate`；
- 实现 `IStreamingIAgentMiddleware` 但 `handlesStreamingEvents()` 返回 `false`，效果相同；
- 无论哪种，只要 `beforeModelCall` / `afterModelCall` / 工具回调存在，流式路径都会触发。

<br/>

## 四、管理器：注册、排序与分发

`AgentMiddlewareManager`（`AgentMiddlewareManager.java:23`）是中间件的容器与分发器。它的内部结构只有三个列表：

```java
private final List<IAgentMiddleware> middlewares;          // 原始注册顺序
private final List<IAgentMiddleware> sortedMiddlewares;    // 实际分发顺序
private final List<IStreamingIAgentMiddleware> streamingMiddlewares; // 流式事件接收者
```

### 4.1、注册：写时复制 + 重建视图

```java
private final List<IAgentMiddleware> middlewares =
        new CopyOnWriteArrayList<IAgentMiddleware>();

public AgentMiddlewareManager register(IAgentMiddleware middleware) {
    if (middleware != null) {
        middlewares.add(middleware);
        sortMiddlewares();
    }
    return this;
}

public AgentMiddlewareManager registerAll(List<IAgentMiddleware> middlewares) {
    if (middlewares != null) {
        for (IAgentMiddleware middleware : middlewares) {
            if (middleware != null) {
                this.middlewares.add(middleware);
            }
        }
        sortMiddlewares();
    }
    return this;
}
```

`middlewares` 使用 `CopyOnWriteArrayList`，保证注册与读取的并发安全；`sortedMiddlewares` 与 `streamingMiddlewares` 是普通 `ArrayList`，在每次注册后整体重建：

```java
private void sortMiddlewares() {
    sortedMiddlewares.clear();
    streamingMiddlewares.clear();

    for (IAgentMiddleware middleware : middlewares) {
        sortedMiddlewares.add(middleware);
        if (middleware instanceof IStreamingIAgentMiddleware) {
            IStreamingIAgentMiddleware streamingMiddleware =
                    (IStreamingIAgentMiddleware) middleware;
            if (streamingMiddleware.handlesStreamingEvents()) {
                streamingMiddlewares.add(streamingMiddleware);
            }
        }
    }
}
```

这里没有按注解、`Ordered` 接口或优先级排序，**注册顺序就是执行顺序**。原因很简单：

- 顺序在构建 Agent 时就确定，运行时不需要动态调整；
- 对于改写型中间件，顺序本身就是语义（例如「先脱敏再截断」与「先截断再脱敏」结果不同）；
- 避免引入一套额外的排序元数据，降低使用成本。

### 4.2、分发：每个回调一个 `triggerXxx`

管理器的分发方法呈固定模板，以「单工具执行后」为例（`AgentMiddlewareManager.java:298`）：

```java
public String triggerAfterToolExecution(
        ToolExecutionRequest toolRequest, String toolResult, AgentChatContext chatContext) {
    if (sortedMiddlewares.isEmpty()) {
        return toolResult;
    }

    String res = toolResult;
    for (IAgentMiddleware middleware : sortedMiddlewares) {
        try {
            res = middleware.afterToolExecution(toolRequest, res, chatContext);
        } catch (Exception e) {
            log.log(Level.SEVERE,
                    "中间件 " + name(middleware) + " 执行 afterToolExecution 时出错", e);
            // 继续执行其他中间件，不中断流程
        }
    }
    return res;
}
```

这段模板包含四个固定动作：

1. **空列表快速返回**：没有中间件时零开销；
2. **沿链传递结果**：`res` 作为下一个中间件的输入；
3. **异常隔离**：某个中间件抛异常，只记录日志，`res` 保持上一个有效值；
4. **返回最终值**：调用方只关心链尾结果。

模型调用前的「可中断」版本略有不同（`AgentMiddlewareManager.java:416`）：

```java
public ChatRequest triggerBeforeModelCall(
        int currentStep, ChatRequest chatRequest, AgentChatContext chatContext) {
    if (sortedMiddlewares.isEmpty()) {
        return chatRequest;
    }

    ChatRequest processedRequest = chatRequest;
    for (IAgentMiddleware middleware : sortedMiddlewares) {
        try {
            processedRequest =
                    middleware.beforeModelCall(currentStep, processedRequest, chatContext);
            if (processedRequest == null) {
                return null;   // 立即中断，后续中间件不再执行
            }
        } catch (Exception e) {
            log.log(Level.SEVERE,
                    "中间件 " + name(middleware) + " 执行 beforeModelCall 时出错", e);
        }
    }
    return processedRequest;
}
```

注意：**异常会被吞掉，`null` 不会。** 中间件内部抛异常被视为「这个中间件失效」，主流程继续；而显式返回 `null` 被视为「业务决定中断」，立即生效。

### 4.3、模型调用后的 `null` 语义

`triggerAfterModelCall` 对 `null` 的处理与 `beforeModelCall` 不同（`AgentMiddlewareManager.java:444`）：

```java
ChatResponse processedResponse = chatResponse;
for (IAgentMiddleware middleware : sortedMiddlewares) {
    try {
        processedResponse = middleware.afterModelCall(
                currentStep, chatRequest, processedResponse, chatContext);
        if (processedResponse == null) {
            // 回退到本次模型调用的原始响应
            return chatResponse;
        }
    } catch (Exception e) {
        // 保持上一个有效响应，继续
    }
}
return processedResponse;
```

也就是说：

- `beforeModelCall` 返回 `null` = **阻止调用**；
- `afterModelCall` 返回 `null` = **放弃改写，回退原始响应**。

这个不对称是刻意的：进入 `afterModelCall` 时模型已经调用完，返回 `null` 不应把响应「吃掉」，否则调用方无从继续。

### 4.4、管理器实例的作用域

`ReActAgent` 在构造时创建管理器并注册 Builder 里的中间件（`ReActAgent.java:72`）：

```java
this.toolExecutor = new AgentToolExecutor(builder.toolService);
this.middlewareManager = new AgentMiddlewareManager();
this.middlewareManager.registerAll(builder.middlewares);
```

因此：

- 管理器是 **Agent 实例级** 的，一个 Agent 一份；
- 中间件实例默认被 **同一 Agent 的所有会话复用**；
- 中间件若要保存会话状态，必须按 `memoryId` 隔离，并在 `onStop` / `onStopWithError` 清理。

<br/>

## 五、三种语义：观察、改写、中断

中间件看起来方法很多，但按「对主流程的影响」只有三类。

### 5.1、观察型：只读，不影响执行

代表回调：

```text
onInitComplete / beforeLoop / afterLoop / onLoopError
onModelCallError / onAiCallRetry
beforeToolExecution / onToolExecutionError
beforeConcurrentToolExecution / onConcurrentToolExecutionComplete
onStop / onStopWithError
onPartialResponse / onPartialThinking / onIntermediateResponse
```

特点：

- 无返回值；
- 抛异常会被管理器吞掉；
- 适合日志、指标、Trace、审计。

### 5.2、改写型：返回值沿链传递

代表回调：

```java
ChatRequest beforeModelCall(int currentStep, ChatRequest chatRequest, AgentChatContext ctx);

ChatResponse afterModelCall(int currentStep, ChatRequest req, ChatResponse resp, AgentChatContext ctx);

String afterToolExecution(ToolExecutionRequest req, String result, AgentChatContext ctx);

List<String> afterConcurrentToolExecution(
        List<ToolExecutionRequest> reqs, List<String> results, AgentChatContext ctx);
```

特点：

- 后一个中间件看到的是前一个中间件的输出；
- 抛异常时，链上的值保持不变；
- 适合脱敏、内容裁剪、请求参数注入、响应后处理。

一个典型链路：

```text
原始工具结果
   │
   ▼
TenantMiddleware.afterToolExecution     追加租户标记
   │
   ▼
MaskingMiddleware.afterToolExecution    脱敏手机号/身份证
   │
   ▼
TruncateMiddleware.afterToolExecution   超长截断
   │
   ▼
写回 ChatMemory
```

注册顺序直接决定链路顺序：

```java
.middleware(new TenantMiddleware())
.middleware(new MaskingMiddleware())
.middleware(new TruncateMiddleware())
```

### 5.3、中断型：返回 `null` 终止当前动作

目前只有 `beforeModelCall` 具备中断语义：

```java
ChatRequest beforeModelCall(int currentStep, ChatRequest chatRequest, AgentChatContext chatContext);
```

返回 `null` 后，两条驱动器的收敛方式不同：

| 路径 | 结果 |
|---|---|
| 非流式 | `ThinkResult(isFinish=true, needAct=false, runRes="模型调用被中间件中断")` → `afterLoop(STOP)` → `onStop(NORMAL)` → `ChatResult(SUCCESS)` |
| 流式 | `notifyStop(CANCEL)` → `onLoopError` + `onStop(CANCEL)` → `onError(AgentException("模型调用被中间件中断"))` |

这个差异来自「同步返回值」与「异步错误通道」的不同，并不是中间件契约本身的分歧。接入时如果依赖「中断后一定是取消态」，需要按路径分别处理。

### 5.4、返回值契约速查

| 回调 | 返回 `null` | 抛异常 |
|---|---|---|
| `beforeModelCall` | 中断本轮模型调用 | 记日志，保持上一个有效请求，继续 |
| `afterModelCall` | 回退本次调用的原始响应 | 记日志，保持上一个有效响应，继续 |
| `afterToolExecution` | 结果变为 `null`，执行器最终写入 `tool exec success, but no result` | 记日志，保持上一个有效结果，继续 |
| `afterConcurrentToolExecution` | 保持上一个有效列表 | 记日志，保持上一个有效列表，继续 |
| 其他观察型 | 无返回值 | 记日志，继续 |

<br/>

## 六、非流式接入点：回调到底插在哪

非流式路径由业务线程同步驱动：`run()` 在哪个线程调用，`beforeLoop`、`beforeModelCall`、`afterModelCall` 等回调就在哪个线程执行。唯一的例外是并发工具批次，它的单工具完成回调发生在 `AgentToolExecutor` 的线程池里。因此非流式中间件可以较自然地使用 `ThreadLocal`，但必须在 `onStop` / `onStopWithError` 中清理，避免线程复用导致串数据。

### 6.1、初始化与循环

`Agent.run()` 在循环开始前触发一次初始化（`Agent.java:54`）：

```java
if (middlewareManager != null) {
    middlewareManager.triggerOnInitComplete(chatContext);
}
```

循环体内严格成对触发（`Agent.java:71`）：

```java
while (currentStep <= maxSteps) {
    if (middlewareManager != null) {
        middlewareManager.triggerBeforeLoop(currentStep, chatContext);
    }
    curStep = step(currentStep, chatContext);
    if (middlewareManager != null) {
        middlewareManager.triggerAfterLoop(currentStep, curStep, chatContext);
    }
    ...
}
```

注意 `afterLoop` 在 `step()` 返回后立即触发，此时工具已经执行完、结果已经写回记忆，但下一轮 `think` 还没开始。

### 6.2、模型调用

`ReActAgent.think()` 是模型回调的集中点（`ReActAgent.java:168`）：

```java
// 调用前：可改写、可中断
ChatRequest processedRequest =
        middlewareManager.triggerBeforeModelCall(currentStep, chatRequest, chatContext);
if (processedRequest == null) {
    return ThinkResult.builder()
            .isFinish(true)
            .needAct(false)
            .runRes("模型调用被中间件中断")
            .curActTools(Collections.emptyList())
            .build();
}

// 重试：每次准备重试前触发
chatResponse = AiCallRetrySupport.execute(
        () -> getChatModel().chat(processedRequest),
        retryCount,
        aiCallRetryDelay,
        (attempt, maxRetries, lastException, delayMs) ->
                middlewareManager.triggerOnAiCallRetry(
                        currentStep, processedRequest, chatContext,
                        attempt, maxRetries, delayMs, lastException));

// 调用后：可改写
chatResponse = middlewareManager.triggerAfterModelCall(
        currentStep, processedRequest, chatResponse, chatContext);
```

调用失败时：

```java
catch (Exception e) {
    middlewareManager.triggerOnModelCallError(
            currentStep, processedRequest, e, chatContext);
    return ThinkResult.builder()
            .state(AgentRunState.MODEL_CALL_ERROR)
            .isFinish(true)
            ...
            .build();
}
```

这里有一个很实用的细节：**模型调用失败不会抛到 `Agent.run()` 的异常分支，而是被 `think()` 收敛成 `STOP`。** 所以非流式路径下：

```text
onModelCallError → afterLoop(STOP) → onStop(NORMAL)
```

`onStop` 收到的是 `NORMAL`，但 `ChatResult.runState` 是 `MODEL_CALL_ERROR`。如果业务只监听 `onStopWithError` 来上报模型错误，会漏掉这一类。

### 6.3、工具执行

工具回调集中在 `AgentToolExecutor`。串行路径（`AgentToolExecutor.java:121`）：

```java
for (ToolExecutionRequest toolRequest : toolRequests) {
    if (middlewareManager != null) {
        middlewareManager.triggerBeforeToolExecution(toolRequest, chatContext);
    }

    ToolExecutionResult result =
            executeTool(executorMap, toolRequest, chatContext, middlewareManager);

    String text = result.text();
    if (!result.isError() && middlewareManager != null) {
        text = middlewareManager.triggerAfterToolExecution(
                toolRequest, text, chatContext);
    }
    ...
    chatContext.getChatMemory().add(toolMessage);
}
```

两个边界：

- 工具失败时 **不会** 触发 `afterToolExecution`，只触发 `onToolExecutionError`；
- 改写后的 `text` 才会写回记忆，并进入下一轮模型请求。

并发路径（`AgentToolExecutor.java:199`）用的是另一组回调：

```text
beforeConcurrentToolExecution
   │
   ▼
并发执行每个工具
   ├── 成功 → onConcurrentToolExecutionComplete(result)
   └── 异常 → onToolExecutionError + onConcurrentToolExecutionComplete(error)
   │
   ▼
afterConcurrentToolExecution（整批结果改写）
   │
   ▼
按请求顺序写回记忆
```

因此，**并发模式不会触发 `beforeToolExecution` / `afterToolExecution`**。一个只在单工具回调里做脱敏的中间件，在并发批次下会失效。正确做法是同时实现 `beforeConcurrentToolExecution` / `afterConcurrentToolExecution`，或统一在并发回调里处理。

### 6.4、停止

`Agent.run()` 在循环结束后统一触发（`Agent.java:118`）：

```java
if (cancelReason != null) {
    // 取消
    middlewareManager.triggerOnStop(
            currentStep, stopResult(StopResultState.CANCEL, runRes), chatContext);
} else if (!isFinished && currentStep > maxSteps) {
    // 最大步数
    middlewareManager.triggerOnStop(
            currentStep, stopResult(StopResultState.MAX_STEPS, runRes), chatContext);
} else if (isFinished) {
    // 正常结束
    middlewareManager.triggerOnStop(
            currentStep, stopResult(StopResultState.NORMAL, runRes), chatContext);
}
```

取消场景会先触发 `onLoopError`（在 catch 块里），再触发 `onStop(CANCEL)`；非中断异常则触发 `onLoopError` + `onStopWithError`，然后抛出 `AgentException`。

### 6.5、非流式事件顺序

测试 `ReActAgentMiddlewareTest` 对「工具轮 + 最终回答」的断言（`ReActAgentMiddlewareTest.java:50`）：

```text
init
beforeLoop:1
beforeModel:1
afterModel:1:1
beforeTool:getWeather
afterTool:getWeather
afterLoop:1:FINISHED
beforeLoop:2
beforeModel:2
afterModel:2:0
afterLoop:2:STOP
stop:2:NORMAL
```

这张顺序表就是非流式中间件的「时间轴」：初始化一次，每轮 loop 成对出现，工具回调夹在模型回调与 `afterLoop` 之间，最后 `stop` 收尾。

<br/>

## 七、流式接入点：增量事件与中间响应

流式路径的驱动线程不属于业务线程：`start()` 只负责发起第一轮请求，后续的增量 token、轮次完成、错误都可能由模型 SDK 的回调线程触发。因此流式中间件不能假设「`start()` 返回时执行已经结束」，也不能把跨事件的状态放在普通成员变量里；需要聚合时，按 `memoryId` / `currentStep` 隔离，并使用并发容器或 `AtomicXxx`。

### 7.1、初始化与每轮请求

`ReActTokenStream.start()` 触发初始化（`ReActTokenStream.java:147`）：

```java
if (middlewareManager != null) {
    middlewareManager.triggerOnInitComplete(chatContext);
}
```

每一轮 `chat()` 开始时（`ReActTokenStream.java:182`）：

```java
if (middlewareManager != null) {
    middlewareManager.triggerBeforeLoop(currentStep, chatContext);
    processedRequest = middlewareManager.triggerBeforeModelCall(
            currentStep, chatRequest, chatContext);
    if (processedRequest == null) {
        notifyStop(currentStep, StopResultState.CANCEL,
                MODEL_CALL_INTERRUPTED, new AgentException(MODEL_CALL_INTERRUPTED));
        notifyError(new AgentException(MODEL_CALL_INTERRUPTED));
        return;
    }
}
```

与非流式相比，流式把 `beforeLoop` 放在 `chat()` 内部，而不是循环体外；但在事件顺序上仍然等价。

### 7.2、增量事件：先中间件，后业务回调

`onPartialResponse`（`ReActTokenStream.java:205`）：

```java
@Override
public void onPartialResponse(String partialResponse) {
    if (middlewareManager != null) {
        middlewareManager.triggerOnPartialResponse(
                currentStep, partialResponse, chatContext);
    }
    if (partialResponseHandler != null) {
        partialResponseHandler.accept(partialResponse);
    }
}
```

`onPartialThinking` 同理：

```java
@Override
public void onPartialThinking(String partialThinking) {
    PartialThinking thinking = new PartialThinking(partialThinking);
    if (middlewareManager != null) {
        middlewareManager.triggerOnPartialThinking(
                currentStep, thinking, chatContext);
    }
    if (partialThinkingHandler != null) {
        partialThinkingHandler.accept(thinking);
    }
}
```

顺序是 **中间件优先**。这允许中间件先做过滤、脱敏或统计，再把增量交给业务回调；如果反过来，业务回调可能已经消费了不该出现的 token。

### 7.3、中间响应：工具执行完、下一轮思考前

`onRoundComplete()`（`ReActTokenStream.java:270`）：

```java
toolExecutor.execute(
        currentStep,
        aiMessage.toolExecutionRequests(),
        chatContext,
        toolExecutedHandler,
        middlewareManager,
        agentSettings != null && agentSettings.isEnableConcurrentToolExecution());

if (middlewareManager != null) {
    middlewareManager.triggerOnIntermediateResponse(currentStep, response, chatContext);
}
if (intermediateResponseHandler != null) {
    intermediateResponseHandler.accept(response);
}
if (middlewareManager != null) {
    middlewareManager.triggerAfterLoop(
            currentStep, StepResult.toFinished(""), chatContext);
}

chat(currentStep + 1);
```

因此流式工具轮的事件顺序是：

```text
beforeLoop → beforeModel → afterModel
    → beforeTool / afterTool（或并发批次回调）
    → intermediate
    → afterLoop(FINISHED)
    → 下一轮 beforeLoop
```

`onIntermediateResponse` 是流式独有的观测点：它拿到的 `ChatResponse` 仍然带着本轮的 `toolExecutionRequests`，适合前端渲染「调用了哪些工具」，也适合审计「模型为什么发起这次行动」。

### 7.4、流式的停止与错误

正常回答（`ReActTokenStream.java:256`）：

```java
if (!aiMessage.hasToolExecutionRequests()) {
    middlewareManager.triggerAfterLoop(
            currentStep, StepResult.toStop(aiMessage.text()), chatContext);
    middlewareManager.triggerOnStop(
            currentStep,
            stopResult(StopResultState.NORMAL, aiMessage.text()),
            chatContext);
    handleComplete(response);
    return;
}
```

错误/取消/最大步数统一走 `handleError`（`ReActTokenStream.java:305`）：

```java
middlewareManager.triggerOnLoopError(currentStep, error, chatContext);
if (isMaxStepsError(error)) {
    middlewareManager.triggerOnStop(currentStep,
            stopResult(StopResultState.MAX_STEPS, error.getMessage()), chatContext);
} else if (error instanceof CancelException) {
    middlewareManager.triggerOnStop(currentStep,
            stopResult(StopResultState.CANCEL, error.getMessage()), chatContext);
} else {
    middlewareManager.triggerOnStopWithError(currentStep, error, chatContext);
}
```

取消则由 `handleCancel` 触发（`ReActTokenStream.java:299`）：

```java
notifyStop(currentStep, StopResultState.CANCEL, message, new CancelException(message));
notifyError(new CancelException(message));
```

`notifyStop` 内部成对触发 `onLoopError + onStop`，避免中途取消时只触发其中一个。

### 7.5、流式事件顺序

`ReActAgentStreamMiddlewareTest` 的断言（`ReActAgentStreamMiddlewareTest.java:46`）：

```text
init
beforeLoop:1
beforeModel:1
afterModel:1:1
beforeTool:getWeather
afterTool:getWeather
intermediate:1
afterLoop:1:FINISHED
beforeLoop:2
beforeModel:2
partial:2:杭州
partial:2:今天22度
afterModel:2:0
afterLoop:2:STOP
stop:2:NORMAL
```

与非流式相比，只多了 `intermediate:1` 与 `partial:*`；轮次级骨架完全一致。

<br/>

## 八、实战：两个内置中间件

### 8.1、`LoggingIAgentMiddleware`：无状态核心 + 会话级统计

`LoggingIAgentMiddleware`（`extend/middlewares/LoggingIAgentMiddleware.java:25`）是一个只观察、不改写的中间件。它的关键设计是 **按 `memoryId` 隔离统计状态**：

```java
private final Map<Object, SessionStats> sessionStatsMap =
        new ConcurrentHashMap<Object, SessionStats>();

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

它统计的内容包括：

- 会话总耗时；
- 模型调用总耗时；
- 工具执行总耗时；
- 输入 / 输出 / 总 token；
- 每次工具执行的开始时间（按 `toolId` 记录）。

会话结束时释放：

```java
@Override
public void onStop(int currentStep, StopResult stopResult, AgentChatContext chatContext) {
    // 打印汇总...
    sessionStatsMap.remove(memoryId(chatContext));
}

@Override
public void onStopWithError(int currentStep, Throwable error, AgentChatContext chatContext) {
    // 打印异常汇总...
    sessionStatsMap.remove(memoryId(chatContext));
}
```

这个中间件很好地示范了 **「Agent 实例复用，中间件必须自己管理会话状态」** 的约束。

### 8.2、`AgentTraceMiddleware`：用 `extensions` 保存一次运行的 Trace

`AgentTraceMiddleware`（`extend/middlewares/trace/AgentTraceMiddleware.java:22`）不实现流式子接口，只依赖生命周期回调。它把一次运行的 Trace 状态挂在 `AgentChatContext.extensions` 上：

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

每一轮会创建一个 `Step`：

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

模型调用前后记录时间与 token，工具回调记录工具快照，`afterLoop` 发布一轮快照，`onStop` 汇总并清理：

```java
@Override
public void onStop(int currentStep, StopResult result, AgentChatContext context) {
    AgentTraceSnapshotStatus status = AgentTraceSnapshotStatus.NORMAL;
    if (result != null && result.getStopResultState() == StopResultState.CANCEL) {
        status = AgentTraceSnapshotStatus.CANCEL;
    } else if (result != null && result.getStopResultState() == StopResultState.MAX_STEPS) {
        status = AgentTraceSnapshotStatus.MAX_STEPS;
    }
    finish(context, currentStep, status, null);
}
```

`publish` 采用 best-effort，采集器异常不影响 Agent：

```java
private void publish(AgentTraceSnapshot snapshot) {
    if (collector == null || snapshot == null) return;
    try {
        collector.onSnapshot(snapshot);
    } catch (RuntimeException ignored) {
        // 可观测性刻意与主执行路径隔离
    }
}
```

这套写法值得复用：

- **一次运行的状态放 `context.extensions`**，天然按运行隔离；
- **不要放在中间件实例字段里**，否则多会话会串；
- **在 `onStop` 统一清理**，避免 `extensions` 随运行结束还持有大对象。

<br/>

## 九、手写一个中间件

### 9.1、需求：工具结果脱敏 + 审计日志

先实现一个只关心串行工具回调的中间件：

```java
public class ToolMaskingMiddleware implements IAgentMiddleware {

    private static final Logger log =
            Logger.getLogger(ToolMaskingMiddleware.class.getName());

    @Override
    public void beforeToolExecution(
            ToolExecutionRequest toolRequest, AgentChatContext chatContext) {
        log.info("[tool-start] session=" + chatContext.getMemoryId()
                + ", tool=" + toolRequest.name()
                + ", args=" + mask(toolRequest.arguments()));
    }

    @Override
    public String afterToolExecution(
            ToolExecutionRequest toolRequest,
            String toolResult,
            AgentChatContext chatContext) {
        String masked = mask(toolResult);
        log.info("[tool-done] session=" + chatContext.getMemoryId()
                + ", tool=" + toolRequest.name()
                + ", result=" + truncate(masked, 500));
        return masked;
    }

    private static String mask(String text) {
        if (text == null) return null;
        return text.replaceAll("(\\d{3})\\d{4}(\\d{4})", "$1****$2");
    }

    private static String truncate(String text, int max) {
        return text == null || text.length() <= max
                ? text
                : text.substring(0, max) + "...";
    }
}
```

注册：

```java
ReActAgent agent = ReActAgent.builder()
        .chatModel(chatModel)
        .streamingChatModel(streamingChatModel)
        .chatMemoryProvider(memoryProvider)
        .toolService(toolService)
        .middleware(new ToolMaskingMiddleware())
        .build();
```

注意：如果开启了并发工具执行，上面的 `afterToolExecution` **不会** 被调用。需要补上并发批次回调：

```java
@Override
public void beforeConcurrentToolExecution(
        List<ToolExecutionRequest> requests, AgentChatContext chatContext) {
    for (ToolExecutionRequest request : requests) {
        log.info("[batch-start] tool=" + request.name());
    }
}

@Override
public void onConcurrentToolExecutionComplete(
        ToolExecutionRequest request,
        String result,
        Throwable error,
        AgentChatContext chatContext) {
    if (error == null) {
        log.info("[batch-item-done] tool=" + request.name());
    } else {
        log.warning("[batch-item-error] tool=" + request.name()
                + ", error=" + error.getMessage());
    }
}

@Override
public List<String> afterConcurrentToolExecution(
        List<ToolExecutionRequest> requests,
        List<String> results,
        AgentChatContext chatContext) {
    List<String> masked = new ArrayList<String>(results.size());
    for (String result : results) {
        masked.add(mask(result));
    }
    return masked;
}
```

### 9.2、需求：流式增量指标

实现 `IStreamingIAgentMiddleware`，只关心增量事件：

```java
public class StreamMetricsMiddleware implements IStreamingIAgentMiddleware {

    private final AtomicLong tokenCount = new AtomicLong();
    private final AtomicLong thinkingCount = new AtomicLong();

    @Override
    public void onPartialResponse(
            int currentStep, String partialResponse, AgentChatContext chatContext) {
        tokenCount.incrementAndGet();
    }

    @Override
    public void onPartialThinking(
            int currentStep, PartialThinking partialThinking, AgentChatContext chatContext) {
        thinkingCount.incrementAndGet();
    }

    @Override
    public void onIntermediateResponse(
            int currentStep, ChatResponse response, AgentChatContext chatContext) {
        System.out.println("[tool-round] step=" + currentStep
                + ", tools=" + response.aiMessage().toolExecutionRequests().size());
    }
}
```

如果某个中间件只想接收生命周期事件，不想被增量 token 打扰，可以让它返回 `false`：

```java
public class LifecycleOnlyMiddleware implements IStreamingIAgentMiddleware {
    @Override
    public boolean handlesStreamingEvents() {
        return false;
    }
}
```

这样它不会进入 `streamingMiddlewares`，但 `beforeLoop`、`beforeModelCall` 等仍然照常触发。

### 9.3、注册顺序就是语义

对于改写型中间件，注册顺序必须按「你希望的加工顺序」排列：

```java
ReActAgent.builder()
        .middleware(new TenantMiddleware())      // 1. 注入租户标记
        .middleware(new ToolMaskingMiddleware()) // 2. 脱敏
        .middleware(new TruncateMiddleware())    // 3. 截断
        .build();
```

执行时：

```text
Tenant → Masking → Truncate
```

如果顺序反过来，截断可能把脱敏需要的上下文截掉，或者脱敏处理的是已经截断的文本。**不要依赖中间件自己排序，管理器不会替你做这件事。**

<br/>

## 十、并发、生命周期与线程安全

### 10.1、回调可能来自不同线程

| 路径 | 回调线程 |
|---|---|
| 非流式 `run()` | 调用 `run()` 的业务线程 |
| 流式增量事件 | 模型 SDK 的流式回调线程 |
| 串行工具回调 | 调用 `run()` / 流式回调的线程 |
| 并发工具回调 | `AgentToolExecutor` 内部的工具线程池（最多 8 个守护线程） |

因此中间件内部状态必须考虑并发：

- 会话级统计用 `ConcurrentHashMap`，按 `memoryId` 隔离；
- 工具级状态用 `toolId` 作为 key，避免同名工具互相覆盖；
- 不要用普通 `HashMap` 在并发工具回调里做共享写入；
- 如果必须聚合顺序，使用 `AtomicLong` / `LongAdder` 或每工具独立对象。

### 10.2、生命周期与资源释放

一次运行的完整生命周期：

```text
onInitComplete
   │
   ├── beforeLoop / beforeModelCall / afterModelCall
   ├── beforeToolExecution / afterToolExecution
   ├── ... 多轮 ...
   │
   └── onStop 或 onStopWithError
```

约定：

1. **`onInitComplete` 每次运行只触发一次**，适合初始化会话级状态；
2. **`onStop` / `onStopWithError` 是清理点**，必须在这里移除 `memoryId` 对应的状态；
3. **不要把 `AgentChatContext` 或 `ChatMemory` 长期保存在中间件字段里**，否则会阻止会话记忆被回收；
4. **不要假设 `onStop` 一定在 `onStopWithError` 之前或之后**，二者互斥。

### 10.3、异常与「失败开放」

管理器对所有回调都采用 fail-open：

```java
try {
    middleware.beforeLoop(currentStep, chatContext);
} catch (Exception e) {
    log.log(Level.SEVERE, "中间件 ... 执行 beforeLoop 时出错", e);
}
```

这意味着：

- 中间件抛异常 **不会** 让 Agent 失败；
- 但也不会自动降级或重试，异常只存在于日志里；
- 如果某个中间件是「必须成功」的（例如合规拦截），不要依赖抛异常，应该显式返回 `null` 中断，或在中间件内部自行上报。

测试 `shouldKeepRunningWhenMiddlewareThrows` 明确验证了这一点：即使 `beforeLoop` 与 `afterModelCall` 都抛 `IllegalStateException`，Agent 仍然返回正常结果（`ReActAgentMiddlewareTest.java:180`）。

<br/>

## 十一、扩展指南：新增一个回调要改哪些地方

中间件的扩展性不只体现在「实现一个接口」，还体现在「新增一种生命周期事件」。推荐按下面五步做。

### 第一步：在契约层加 `default` 方法

```java
public interface IAgentMiddleware {

    default void onContextCompacted(
            int currentStep, AgentChatContext chatContext) {}
}
```

必须是 `default`，否则所有已有实现类都会编译失败。

如果是流式专属事件，加到 `IStreamingIAgentMiddleware`，同样给默认空实现。

### 第二步：在管理器加 `triggerXxx`

```java
public void triggerOnContextCompacted(int currentStep, AgentChatContext chatContext) {
    if (sortedMiddlewares.isEmpty()) {
        return;
    }
    for (IAgentMiddleware middleware : sortedMiddlewares) {
        try {
            middleware.onContextCompacted(currentStep, chatContext);
        } catch (Exception e) {
            log.log(Level.SEVERE,
                    "中间件 " + name(middleware) + " 执行 onContextCompacted 时出错", e);
        }
    }
}
```

观察型回调直接照抄这个模板；改写型回调需要增加「结果沿链传递」；中断型回调需要明确 `null` 的语义。

### 第三步：在两条驱动器上找等价插入点

| 事件类型 | 非流式插入点 | 流式插入点 |
|---|---|---|
| 初始化 | `Agent.run()` | `ReActTokenStream.start()` |
| 每轮开始 | `Agent.run()` 循环体 | `ReActTokenStream.chat()` |
| 模型前/后 | `ReActAgent.think()` | `ReActTokenStream.chat()` / `onRoundComplete()` |
| 工具前/后 | `AgentToolExecutor` | 同左（共用执行器） |
| 停止 | `Agent.run()` 收敛段 | `ReActTokenStream` 的 `handleComplete` / `handleError` / `handleCancel` |
| 增量 | 不适用 | `StreamingChatResponseHandler` 回调 |

原则是：**同一个语义，在两条路径上必须在等价位置触发。** 否则「一套调用」就会在中间件层面破功。

### 第四步：定义返回值与异常契约

新增回调时先回答三个问题：

1. 它是观察型、改写型还是中断型？
2. 返回 `null` 表示什么？回退、忽略，还是中断？
3. 抛异常时，链上的值是否保持？

把答案写进 Javadoc，并在管理器里按同一套模板实现。

### 第五步：补两条路径的测试

至少覆盖：

- 非流式事件顺序；
- 流式事件顺序；
- 中间件抛异常不阻断主流程；
- 改写结果确实写回记忆；
- 中断语义在两条路径上的收敛结果。

现有测试可以直接作为模板：

- `ReActAgentMiddlewareTest`
- `ReActAgentStreamMiddlewareTest`
- `AgentMiddlewareManagerTest`

<br/>

## 十二、测试如何验证语义

### 12.1、顺序验证

`ReActAgentMiddlewareTest.shouldFireHooksInOrderForToolRound` 断言完整的非流式顺序：

```text
init → beforeLoop:1 → beforeModel:1 → afterModel:1:1
     → beforeTool → afterTool → afterLoop:1:FINISHED
     → beforeLoop:2 → beforeModel:2 → afterModel:2:0
     → afterLoop:2:STOP → stop:2:NORMAL
```

`ReActAgentStreamMiddlewareTest.shouldFireHooksInOrderAcrossStreamingToolRound` 断言流式顺序：

```text
init → beforeLoop:1 → beforeModel:1 → afterModel:1:1
     → beforeTool → afterTool → intermediate:1
     → afterLoop:1:FINISHED
     → beforeLoop:2 → beforeModel:2 → partial:2:*
     → afterModel:2:0 → afterLoop:2:STOP → stop:2:NORMAL
```

两者只差流式专属事件，说明中间件接入点是对齐的。

### 12.2、改写验证

`shouldRewriteToolResultBeforeWritingMemory` 验证：

- 中间件改写后的结果写回 `ChatMemory`；
- 下一轮模型请求里能看到改写后的内容（`ReActAgentMiddlewareTest.java:71`）。

### 12.3、中断验证

`shouldAbortModelCallFromMiddleware` 验证：

- 模型调用次数为 0；
- 返回文本是「模型调用被中间件中断」；
- 事件顺序停在 `beforeModel:1`，随后 `afterLoop:1:STOP` 与 `stop:1:NORMAL`（`ReActAgentMiddlewareTest.java:91`）。

流式版本 `shouldAbortStreamingModelCallFromMiddleware` 验证：

- 模型调用次数为 0；
- `onError` 收到 `AgentException`；
- 事件为 `init → beforeLoop:1 → beforeModel:1 → loopError:1 → stop:1:CANCEL`（`ReActAgentStreamMiddlewareTest.java:129`）。

这组测试也直观展示了两条路径在「中断收敛」上的差异。

### 12.4、容错验证

- `shouldKeepRunningWhenMiddlewareThrows`：中间件异常不阻断非流式执行；
- `shouldKeepStreamingWhenMiddlewareThrows`：中间件异常不阻断流式执行；
- `shouldSkipPartialEventsForNonStreamingMiddleware`：`handlesStreamingEvents=false` 只过滤增量事件，生命周期回调仍然触发。

<br/>

## 十三、总结

AgentForge 的中间件实现可以浓缩成四句话：

1. **契约层用 `default` 方法承载生命周期**，让新增回调不破坏已有实现；
2. **流式能力用子接口隔离**，让不关心 token 的中间件零成本退出增量事件；
3. **管理器按注册顺序扇出**，每个回调独立 `try/catch`，实现失败开放；
4. **三种语义用返回值表达**：观察无返回、改写沿链传递、中断返回 `null`。

最终结构：

```text
                    ┌────────────────────────────────────┐
                    │        IAgentMiddleware             │
                    │  生命周期：Loop / Model / Tool / Stop │
                    └──────────────┬─────────────────────┘
                                   │ extends
                    ┌──────────────▼─────────────────────┐
                    │  IStreamingIAgentMiddleware         │
                    │  partial / thinking / intermediate  │
                    └──────────────┬─────────────────────┘
                                   │ register
                    ┌──────────────▼─────────────────────┐
                    │     AgentMiddlewareManager          │
                    │  顺序分发 / 异常隔离 / 结果传递       │
                    └───────┬──────────────────┬─────────┘
                            │                  │
                    Agent.run()        ReActTokenStream
                    （非流式驱动器）      （流式驱动器）
                            │                  │
                     ChatResult          TokenStream 回调
```

这套设计让「日志、Trace、脱敏、审计、限流」这类横切逻辑不再侵入 ReAct 主循环，同时保证非流式与流式在中间件层面看到的是同一套生命周期。

下一篇会用一个真实的内置实现收尾：[内置middleware扩展：日志Loggingmiddlware实现.md](%E5%86%85%E7%BD%AEmiddleware%E6%89%A9%E5%B1%95%EF%BC%9A%E6%97%A5%E5%BF%97Loggingmiddlware%E5%AE%9E%E7%8E%B0.md)，拆解 `LoggingIAgentMiddleware` 如何统计每轮模型耗时、工具耗时与 token，并在会话结束时汇总。之后会进入工具层，拆解 `AgentToolExecutor` 如何统一 `local` / `http` / `mcp` 三类工具的查找、执行、并发与结果写回。

<br/>

## 参考资料

- 契约：`component/middleware/IAgentMiddleware.java`、`IStreamingIAgentMiddleware.java`
- 分发器：`component/middleware/AgentMiddlewareManager.java`
- 接入点：`Agent.java`、`BaseReActAgent.java`、`ReActAgent.java`、`stream/ReActTokenStream.java`、`tool/AgentToolExecutor.java`
- 内置实现：`extend/middlewares/LoggingIAgentMiddleware.java`、`extend/middlewares/trace/AgentTraceMiddleware.java`
- 测试：`ReActAgentMiddlewareTest`、`ReActAgentStreamMiddlewareTest`、`AgentMiddlewareManagerTest`
- 同系列：[核心ReAct 框架设计，支持非流式、流式 一套调用.md](../%E6%A0%B8%E5%BF%83ReAct%20%E6%A1%86%E6%9E%B6%E8%AE%BE%E8%AE%A1%EF%BC%8C%E6%94%AF%E6%8C%81%E9%9D%9E%E6%B5%81%E5%BC%8F%E3%80%81%E6%B5%81%E5%BC%8F%20%E4%B8%80%E5%A5%97%E8%B0%83%E7%94%A8.md)

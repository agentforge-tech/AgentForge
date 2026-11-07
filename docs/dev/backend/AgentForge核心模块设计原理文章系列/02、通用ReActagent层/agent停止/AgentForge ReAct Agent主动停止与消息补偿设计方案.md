# AgentForge ReAct Agent 主动停止与消息补偿设计方案

## 1. 现状与目标

改造前，`BaseAgent.cancel(memoryId)` 只写入 `cancelFlags`，ReAct 循环必须等模型或工具返回、执行到下一个 `isCancelled` 检查点后才会停止。它属于“协作式取消”，无法解决模型长时间无响应、工具阻塞或流式连接一直不结束的问题。

当前实现已经完成核心目标：

- 删除 `cancelFlags`，取消的唯一真相源是 `AgentRunControl`；
- `cancel(memoryId, executionId)` 不再只改标记，而是主动关闭模型连接、取消 Future、中断执行线程；
- 迟到 token、模型完成回调和工具结果通过运行栅栏丢弃；
- 取消后按当前消息链状态补齐 ChatMemory；
- 统一走一次 `onStop(CANCEL)`。

“强制停止”的含义是：**Agent 主流程立即进入取消终态，不再接收迟到结果，并尽最大能力主动终止底层任务**。Java 不存在安全的通用强杀能力，不能使用 `Thread.stop()`；对吞掉中断的第三方代码，只能依赖连接关闭、超时或进程隔离。

### 1.1 方案边界

本文覆盖：

- `ReActAgent` 非流式执行与 `ReActTokenStream` 流式执行；
- 模型请求、SSE 连接、重试调度任务和工具执行任务的取消；
- ChatMemory 消息协议补偿；
- `onStop(CANCEL)` 的统一、幂等触发；
- `cancel` 契约和运行状态查询。

本文不涉及平台层停止 HTTP 接口、任务表、消息表、轮询、超时落库和前端交互。

## 2. 落地状态总览

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| `AgentRunControl` 运行控制与终态 CAS | 已实现 | `terminal` + `lock`，唯一真相源 |
| `cancel(memoryId, executionId)` | 已实现 | 精确定位，旧请求不会误伤新执行 |
| 非流式模型取消 | 已实现 | `ChatCall.newCall` + `JdkHttpCall.cancel` |
| 流式模型取消 | 已实现 | `chatCancellable` + `executeStreamingCancellable` |
| SSE 连接/输入流关闭 | 已实现 | `JdkStreamingCancellationHandle` |
| 流式重试任务取消 | 已实现 | 重试 `ScheduledFuture` 登记为取消句柄 |
| 并发工具 Future 取消 | 已实现 | `registerFuture` + `cancel(true)` |
| 顺序工具执行 | 部分实现 | 当前线程执行 + `interrupt`，未改为独立 Future |
| 工具协作式取消令牌 | 未实现 | 后续扩展 |
| 迟到回调栅栏 | 已实现 | `isCurrent + !terminal + !cancellationRequested` |
| 消息链补偿 | 已实现 | `CancellationMessageCompensator` 按消息状态推断 |
| `PendingMessageLedger` | 未实现 | 后续扩展，用于消除竞态 |
| `AgentRunFinalizer` 独立类 | 未实现 | 当前由 `Agent`/`ReActTokenStream` 统一收口 |
| `cancelFlags` | 已删除 | 不再保留两套取消真相源 |
| `getExecutionState(memoryId, executionId)` | 已实现 | 单参数便捷查询暂未提供 |

## 3. 当前总体架构

```text
Agent.cancel(memoryId, executionId)
          |
          v
AgentRunRegistry.cancel
          |
          v
AgentRunControl.requestCancel(reason)
          |
          +-- CAS/锁内：status = CANCEL_REQUESTED
          +-- 标记未完成工具为 CANCEL_REQUESTED
          +-- 快照 modelHandle / futures / executionThread
          |
          v
锁外主动中断
          +-- modelHandle.cancel()      关闭 HTTP / SSE
          +-- future.cancel(true)       取消工具/重试任务
          +-- thread.interrupt()        中断兜底
          +-- cancelListener.run()      流式立即收口
          |
          v
Agent / ReActTokenStream 统一收口
          +-- CancellationMessageCompensator.compensate
          +-- runControl.finish(CANCELLED, CANCEL)
          +-- registry.complete
          +-- middleware.onStop(CANCEL) 一次
```

当前没有单独的 `AgentRunFinalizer` 类，终态收口由：

- 非流式：`Agent.executeLoop`；
- 流式：`ReActTokenStream.handleCancel/finishExecution`；
- 终态闸门：`AgentRunControl.finish` 的 `terminal` CAS。

## 4. 核心对象

### 4.1 `CancellationHandle`

模型层最小取消契约，已实现：

```java
public interface CancellationHandle {
    boolean cancel();
    boolean isCancelled();

    CancellationHandle NONE = ...;
}
```

`Agent` 层不直接依赖 `Future`、`HttpURLConnection` 或具体 HTTP 客户端。

### 4.2 `AgentExecutionState`

完整状态模型、Phase 流转和持久化见同级文档：`agent状态/AgentForge Agent State 持久化设计实现.md`。与取消相关的约束：

- `status == CANCEL_REQUESTED` 表示已受理、正在收口；
- `status == CANCELLED` 表示补偿和 `onStop(CANCEL)` 已完成；
- 取消原因放在 `message`，不新增独立字段；
- `phase` 在刚取消时保留原值，进入补偿后变为 `COMPENSATING`。

### 4.3 `IAgent` 取消与查询契约

当前实现：

```java
boolean cancel(Object memoryId, String executionId);

Optional<AgentExecutionState> getExecutionState(Object memoryId, String executionId);
```

匹配规则：

```text
memoryId 相同 AND executionId 相同 AND 当前执行尚未进入终态
```

任一条件不满足返回 false，不退化为“取消该 memoryId 当前运行”。单参数 `getExecutionState(memoryId)` 目前未提供。

### 4.4 `AgentRunControl`

当前实现字段与职责：

```java
public final class AgentRunControl {
    private final String executionId;
    private final Object memoryId;
    private final AtomicBoolean terminal;
    private final Object lock;
    private AgentExecutionStatus status;
    private AgentExecutionPhase phase;
    private int currentStep;
    private final Map<String, MutableToolState> tools;
    private final List<Future<?>> futures;
    private CancellationHandle modelHandle;
    private Thread executionThread;
    private Runnable cancelListener;
    private String cancelReason;
    private AgentRunState resultState;
    private String errorMessage;
    private long updatedAtMillis;

    public boolean requestCancel(String reason) { ... }
    public boolean finish(AgentExecutionStatus status, AgentRunState result, String error) { ... }
    public AgentExecutionState snapshot() { ... }
}
```

`requestCancel` 的两段式设计：

1. **锁内**：幂等检查、写 `CANCEL_REQUESTED`、标记工具、复制句柄快照；
2. **锁外**：关闭模型句柄、取消 Future、中断线程、触发取消监听。

锁外执行避免持锁期间做阻塞 I/O 或调用外部代码。

`finish` 通过 `terminal` CAS 保证正常完成、取消和异常只有一方能写终态。

### 4.5 `AgentRunRegistry`

当前方法：

```java
AgentRunControl register(String executionId, Object memoryId);
AgentRunControl current(Object memoryId);
boolean isCurrent(Object memoryId, String executionId);
Optional<AgentExecutionState> snapshot(Object memoryId, String executionId);
boolean cancel(Object memoryId, String executionId, String reason);
void complete(AgentRunControl control);
void clear(Object memoryId);
```

`complete` 会保存最终快照到 store 并移除活跃记录；`clear` 会先取消活跃执行，再清理状态。

## 5. 模型调用的主动停止（已实现）

### 5.1 流式调用

`StreamingChatModel` 已提供：

```java
default CancellationHandle chatCancellable(
        ChatRequest request, StreamingChatResponseHandler handler)
```

`HttpTransport` 已提供：

```java
default CancellationHandle executeStreamingCancellable(
        HttpRequest request, StreamingHttpResponseHandler handler)
```

`JdkHttpTransport` 的取消句柄会：

1. 原子标记 cancelled；
2. 关闭 `InputStream/BufferedReader`；
3. 调用 `HttpURLConnection.disconnect()`；
4. `future.cancel(true)` 中断读取线程；
5. 因关闭连接产生的异常若已取消，则不再上报。

`ReActTokenStream.attemptChat()` 发起请求后立即注册句柄，所有 partial/complete/error 回调先经过：

```java
private boolean acceptCallback() {
    return runControl == null
            || (runRegistry.isCurrent(memoryId, executionId)
                    && !runControl.isTerminal()
                    && !runControl.isCancellationRequested());
}
```

取消后即使 Provider 继续产生回调，也不会写 ChatMemory、发送 token 或进入下一轮。

流式重试的 `ScheduledFuture` 已登记到 `registerFuture`，取消发生在退避等待时不会再发起下一次请求。

### 5.2 非流式调用

`ChatModel` 已提供 `newCall(ChatRequest)`，内置 OpenAI/Anthropic 返回持有真实 HTTP 句柄的 `ChatCall`：

```java
public interface ChatCall extends CancellationHandle {
    ChatResponse execute();
}
```

`JdkHttpCall.cancel()` 会关闭输入流并 `disconnect()`。`ReActAgent.think()` 的流程：

1. 进入 `MODEL_CALL`；
2. 创建 `ChatCall` 并注册到 `AgentRunControl`；
3. 注册后再次检查取消，避免对已取消 call 发起 `execute()`；
4. 等待结果；
5. 写 ChatMemory 前再次检查取消，丢弃迟到完成。

`AiCallRetrySupport` 遇到 `CancelException` 或线程中断立即停止；即使底层异常表现为 `ModelException`，下一次重试前也会被取消状态拦住。

补充说明：

- 非流式模型错误最终以 `AgentRunState.MODEL_CALL_ERROR` 返回，状态写为 `FAILED`；
- 为兼容既有中间件契约，非流式模型错误当前仍会上报 `onModelCallError` + `onStop(NORMAL)`；
- 流式模型错误走 `onStopWithError`；
- 流式达到最大步数会写为 `COMPLETED + MAX_STEPS`，并上报 `onStop(MAX_STEPS)`。

### 5.3 取消识别不依赖异常类型

取消线程先把 `status` 置为 `CANCEL_REQUESTED`，再关闭连接/中断线程。因此无论底层抛出：

- `IOException` / `ModelException`
- `InterruptedException`
- 自定义终止异常

Agent 层统一通过 `control.isCancellationRequested()` 判定为 CANCEL，而不会误报 `MODEL_CALL_ERROR`。

## 6. 工具调用的主动停止

### 6.1 并发工具（已实现）

- 每批工具创建固定线程池，提交 `Future`；
- 每个 `Future` 登记到 `registerFuture`；
- 取消时对全部 Future 执行 `cancel(true)`；
- 工具线程也会收到 interrupt。

### 6.2 顺序工具（部分实现）

当前顺序工具仍在执行线程上直接运行，取消时通过 `executionThread.interrupt()` 中断；每个工具开始前检查 `isCancellationRequested()`，剩余工具不再提交。

尚未实现文档早期设想的：

- 长期存在的 `AgentToolExecutorService`；
- 顺序工具也提交为 `Future`；
- `CancellationToken`/`CancellableToolExecutor`/`ToolExecutionContext`。

这些作为后续增强项，不阻塞当前取消语义。

### 6.3 取消异常分类（已实现）

`AgentToolExecutor.executeTool` 会把以下情况统一转为 `CancelException`：

- `Thread.currentThread().isInterrupted()`；
- `CancelException`；
- cause 链中存在 `InterruptedException`。

只有真正的工具失败才转换成 `ToolExecutionResult.failure`，不会被当作普通工具错误继续推理。

## 7. 取消后的消息补偿（已实现）

目标：保持模型协议历史合法，而不是把半截模型输出伪装成正常结果。

所有补偿消息带结构化 attributes：

```text
agentforge.synthetic = true
agentforge.terminalState = CANCELLED
agentforge.executionId = ...
agentforge.reason = 用户主动停止
```

### 7.1 模型调用阶段取消

当最后一条消息是 `UserMessage`：

- 丢弃未完成的 partial token；
- 写入一个 synthetic `AiMessage("本次 Agent 执行已取消")`；
- 调用方通过 `onError(CancelException)` 和 `onStop(CANCEL)` 感知取消。

### 7.2 工具执行阶段取消

当最后一条工具调用 `AiMessage` 存在未完成 tool call：

- 已完成的工具保留真实结果；
- 对每个缺失结果的 tool request 补一条 `ToolExecutionResultMessage(isError=true)`；
- 若发生补齐，再追加一个 synthetic `AiMessage` 作为终止说明。

并发工具场景会遍历本批全部 tool request，不只处理一个。

### 7.3 当前实现限制

当前使用 `CancellationMessageCompensator`，通过读取 ChatMemory 末尾状态推断当前阶段：

- 优点：无需额外记账，覆盖常规取消路径；
- 限制：模型完成、工具完成与 cancel 的极窄竞态窗口，仍可能出现“最后一条消息判断”与真实阶段不一致。

后续建议引入 `PendingMessageLedger`：

```text
MODEL_PENDING
MODEL_COMPLETED(aiMessage)
TOOL_BATCH_PENDING(requestIds)
TOOL_COMPLETED(requestId)
TERMINAL
```

由 ledger 原子完成 `completeModel`、`completeTool`、`compensatePending`，彻底消除竞态。该能力当前未实现。

## 8. 流式与非流式的统一与差异

| 能力 | 非流式 `run` | 流式 `runStream` | 当前实现 |
| --- | --- | --- | --- |
| 运行标识 | `executionId + memoryId` | `executionId + memoryId` | 共用 `AgentRunRegistry` |
| 模型句柄 | `ChatCall` + HTTP call | `chatCancellable` + SSE handle | 都登记为 `modelHandle` |
| 工具句柄 | 并发 Future；顺序工具为执行线程 + interrupt | 并发 Future；顺序工具为执行线程 + interrupt | 共用 `AgentToolExecutor` |
| 取消后返回 | `ChatResult(CANCEL)` | error/terminal event | 共用 `AgentRunState.CANCEL` |
| 消息补偿 | 结束前同步执行 | 终态事件前执行 | 共用 `CancellationMessageCompensator` |
| 结束回调 | `onStop(CANCEL)` 一次 | `onStop(CANCEL)` 一次 | `finish` CAS + 各自收口 |
| 迟到回调 | 写消息前检查取消 | `acceptCallback` 栅栏 | 已实现 |

## 9. 取消时序（当前实现）

```text
调用 cancel 的线程                    Agent/模型/工具线程
    |                                      |
    | cancel(memoryId, executionId)        |
    |------------------------------------->|
    | status = CANCEL_REQUESTED             |
    | 标记 pending 工具                     |
    | 关闭 model handle                    |
    | cancel 所有 futures                   |
    | interrupt 执行线程                    |
    | 触发 cancelListener（流式）           |
    |                                      | 收到取消异常或回调被栅栏拒绝
    |                                      | phase = COMPENSATING
    |                                      | 补偿消息链
    |                                      | phase = FINALIZING
    |                                      | finish(CANCELLED, CANCEL)
    |                                      | onStop(CANCEL) 一次
    |<-------------------------------------| 状态变为 CANCELLED
```

`cancel` 返回值只表示框架是否受理，不等待不可控工具线程物理退出。

## 10. 竞态与幂等

1. **取消与正常完成竞争**：`AgentRunControl.finish` 的 `terminal` CAS 决定唯一终态；
2. **取消与模型错误竞争**：取消状态先落，错误路径统一识别为 CANCEL；若错误已先终态，则保持 FAILED；
3. **取消与工具完成竞争**：已完成的工具保留真实结果，未完成的补 synthetic 结果；
4. **旧运行迟到回调**：回调必须匹配当前 `executionId`，且通过 `!terminal && !cancellationRequested` 栅栏；
5. **重复取消**：第一次返回 true，后续返回 false，不重复关闭、补偿和通知；
6. **新一轮启动**：`prepareForNewRun` 已删除，同一 memoryId 通过 registry 活跃检查和 executionId 隔离。

## 11. 代码落点（当前）

```text
agentforge-agent-core
└── agent/
    ├── IAgent.java
    ├── BaseAgent.java
    ├── Agent.java
    ├── ReActAgent.java
    ├── stream/ReActTokenStream.java
    ├── tool/AgentToolExecutor.java
    └── component/state/
        ├── domain/
        │   ├── AgentExecutionState.java
        │   ├── AgentExecutionStatus.java
        │   ├── AgentExecutionPhase.java
        │   ├── RunningToolState.java
        │   └── ToolExecutionStatus.java
        ├── control/
        │   ├── AgentRunControl.java
        │   └── AgentRunRegistry.java
        ├── store/
        │   ├── AgentExecutionStateStore.java
        │   └── InMemoryAgentExecutionStateStore.java
        └── compensation/
            └── CancellationMessageCompensator.java

agentforge-model-api
├── model/cancel/CancellationHandle.java
├── chat/ChatCall.java
├── chat/ChatModel.java                 # newCall
├── chat/StreamingChatModel.java        # chatCancellable
└── http/HttpTransport.java             # newCall / executeStreamingCancellable

agentforge-model-core
└── http/JdkHttpTransport.java          # 保存 connection/stream/future 并主动关闭
```

## 12. 后续增强项

以下为尚未落地、但设计上已预留的能力：

1. `PendingMessageLedger`：消除消息补偿竞态；
2. `CancellationToken` / `CancellableToolExecutor` / `ToolExecutionContext`：给工具协作式取消能力；
3. `AgentToolExecutorService`：顺序工具也进入可取消 Future；
4. 独立 `AgentRunFinalizer`：把当前分散在 `Agent`/`ReActTokenStream` 的收口逻辑抽出；
5. 取消指标：cancel latency、late callback dropped、active leaked task；
6. 不可中断工具的进程隔离执行器；
7. `getExecutionState(memoryId)` 单参数便捷查询（按需）。

## 13. 验收用例

| 用例 | 状态 | 预期 |
| --- | --- | --- |
| 非流式模型调用中 cancel | 已覆盖 | 返回 `AgentRunState.CANCEL`，不会误报模型错误 |
| 流式输出部分 token 后 cancel | 已覆盖 | 后续 token/complete 被丢弃，只触发一次 `onStop(CANCEL)` |
| 非流式最大步数 | 已覆盖 | `MAX_STEPS`，`onStop(MAX_STEPS)` |
| 同 memoryId 新运行 | 已覆盖 | 旧 executionId 回调被忽略 |
| 流式重试退避时 cancel | 建议补充 | 重试任务被取消，不再发起请求 |
| 并发工具执行中 cancel | 建议补充 | 所有 Future 被取消，结果按取消处理 |
| 重复 cancel | 建议补充 | 幂等，不重复补偿、不重复通知 |
| 取消后消息链 | 建议补充 | 满足 tool call/result 配对 |
| 工具忽略 interrupt | 建议补充 | 需要 watchdog / 泄漏任务记录 |
| cancel latency 指标 | 建议补充 | 需要可观测性埋点 |
| 顺序工具 Future 化 | 后续实现 | 依赖 `AgentToolExecutorService` |
| 工具执行中进程崩溃恢复 | 后续实现 | 依赖 checkpoint + 幂等账本 |

## 14. 最终结论

当前实现已经完成“主动中断 + 迟到回调隔离 + 消息补偿 + 单次 onStop”的核心闭环，`cancelFlags` 已删除，取消状态以 `AgentRunControl` 为唯一真相源。

与最初设计稿的差异主要在增强项：`AgentRunFinalizer`、`PendingMessageLedger`、工具取消令牌、顺序工具 Future 化尚未落地；文档已按当前实现标注状态，后续可按第 12 节逐步补齐，不影响现有取消语义。

# AgentForge 一段式流式对话停止（Signal）设计实现

## 一、定位与结论

一段式对话（实时流式 + 一次性非流式）中，Agent 执行与本次请求绑定。停止采用 **signal 方式**：调用独立停止接口，通过共享注册表 `ChatStopRegistry` 找到该会话正在运行的 Agent，下发取消信号，由框架主动中断模型/工具并收口。

核心特征：

- 任务标识使用 `sessionId`（一段式没有独立任务寻址，以会话为粒度）；
- 停止信号在内存中维护，不在请求线程上轮询等待；
- 实时流式与一次性非流式调用共用同一份停止注册表；
- Agent 收到取消后主动关闭模型句柄、取消工具 Future、中断执行线程；
- 最终状态由 `SessionLifecycleMiddleware` 收口为 `CANCELLED`，会话回 `FINISHED`。

## 二、停止接口

```http
POST /api/chat/stop?sessionId={sessionId}
```

Controller 只做协议转换：

```java
@PostMapping("/api/chat/stop")
public void stopStream(@RequestParam String sessionId) {
    chatStreamService.stop(sessionId);
}
```

一个停止接口同时覆盖一段式的两种入口：

| 入口 | 说明 | 停止后行为 |
| --- | --- | --- |
| `/api/chat/stream` | 实时流式，Agent 与 SSE 连接绑定 | 下发取消信号并解绑 SSE |
| `/api/agent/chat` | 一次性非流式，调用线程阻塞等待 | 下发取消信号，当前 ask 以 CANCEL 收口 |

一段式停止接口不等待结果、不返回状态；它只负责“下发信号”。这与二段式停止接口明显不同（二段式以 `messageId` 为准、轮询等待终态、超时兜底 `TIMEOUT_CANCELLED`）。

## 三、停止信号注册

停止信号统一放在共享组件 `ChatStopRegistry`（`sessionId -> 取消动作`），一段式流式与一次性非流式调用共用：

```java
@Component
public class ChatStopRegistry {
    void register(String sessionKey, Runnable stop);
    void remove(String sessionKey);
    boolean cancel(String sessionKey);
}
```

**流式入口** `LegacyChatStreamServiceImpl.stream`：

```java
request.setExecutionId(manager.createExecutionId(agent));
stopRegistry.register(
        sessionId, () -> manager.cancel(agent, sessionId, request.getExecutionId()));
```

**一次性非流式入口** `AgentChatController.invoke`：

```java
String executionId = agentManager.createExecutionId(agent);
req.setExecutionId(executionId);
String sessionKey = resolveSessionKey(req);
stopRegistry.register(
        sessionKey, () -> agentManager.cancel(agent, sessionKey, executionId));
try {
    return agentManager.ask(agent, req);
} finally {
    stopRegistry.remove(sessionKey);
}
```

- key 为 `sessionId`；非流式未显式传 `sessionId` 时，按与 `StandardReActAgentManager` 相同的默认规则生成 `chat-/debug-<aid>`；
- value 是可执行取消动作，内部调用 `IAgentManager.cancel(agent, sessionId, executionId)`；
- `executionId` 在启动前由 `manager.createExecutionId(agent)` 生成，并贯穿本轮执行与消息落库（`SessionLifecycleMiddleware` 写入 `execution_id`）。

任务结束（正常或错误）时移除句柄：流式入口在 `onCompleteResponse/onError` 中 `stopRegistry.remove(sessionId)`；非流式入口在 `finally` 中移除。

## 四、停止执行流程

```text
前端 POST /api/chat/stop?sessionId=xxx
  → AgentChatController.stopStream
  → LegacyChatStreamService.stop(sessionId)
     1. stopRegistry.cancel(sessionId)（命中则执行 cancel handle）
     2. streamingMiddleware.unbind(sessionId)
  → manager.cancel(agent, sessionId, executionId)
  → IAgent.cancel(memoryId, executionId)
  → AgentRunControl.requestCancel(reason)
       ├─ status = CANCEL_REQUESTED
       ├─ 关闭模型句柄（HTTP / SSE）
       ├─ cancel 工具与重试 Future
       ├─ interrupt 执行线程
       └─ 触发流式取消监听
  → ReActTokenStream.handleCancel
       ├─ CancellationMessageCompensator 补消息链
       ├─ finish(CANCELLED, CANCEL)
       └─ ServiceStreamingMiddleware 发布 resp_end
  → SessionLifecycleMiddleware.onStop(CANCEL)
       ├─ message.status = CANCELLED
       └─ session.status = FINISHED
```

要点：

- 取消信号下发后，`ChatStopRegistry.cancel` 会移除句柄，让重复停止在第二次不再命中，天然幂等；
- `streamingMiddleware.unbind(sessionId)` 立即解绑 SSE 出口，停止后不再推送新事件；
- Agent 侧主动中断逻辑见《AgentForge ReAct Agent 主动停止与消息补偿设计方案》。

## 五、幂等与竞态

| 场景 | 行为 |
| --- | --- |
| 重复点击停止 | 第一次命中 `ChatStopRegistry` 并下发；后续未命中，不再下发 |
| 停止时任务刚好完成 | 句柄可能已被移除，停止为空操作，消息已是自然终态 |
| 停止与正常完成竞争 | 框架 `AgentRunControl.finish` 的 CAS 决定唯一终态 |
| 停止后迟到回调 | 运行栅栏 `!terminal && !cancellationRequested` 丢弃 |
| 连接已断开后停止 | 仍会尝试下发信号；但连接断开本身不代表任务停止 |

## 六、与二段式停止的差异

| 维度 | 一段式 signal（本文） | 二段式停止 |
| --- | --- | --- |
| 任务标识 | `sessionId` | `messageId`（+ `execution_id`） |
| 是否等待结果 | 否，只下发信号 | 是，轮询到终态再返回 |
| 权威来源 | 连接与内存状态 | 消息表 `status` |
| 取消中状态 | 无 | 消息/会话进入 `CANCELLING` |
| 超时兜底 | 无 | `TIMEOUT_CANCELLED` 条件 CAS |
| 多实例 | 仅本机 `ChatStopRegistry` | 预留 Redis 取消通道 |

## 七、状态落库

停止信号触发的 `onStop(CANCEL)` 复用 `SessionLifecycleMiddleware`：

- `SessionStatusTransfer.fromStopResult(CANCEL)` → `SessionMessageStatusEnum.CANCELLED`；
- AI 消息写入 `status=CANCELLED`、`content/events/elapsed_ms`、`finished_at`；
- 会话状态更新为 `FINISHED`；
- 若前端仍在连接，会先收到 `resp_end` 再进入终态。

## 八、边界与后续

1. **无超时兜底**：若底层完全吞掉取消、长时间不返回，一段式没有 `TIMEOUT_CANCELLED` 兜底；二段式已补齐；
2. **定位粒度粗**：`sessionId` 无法区分同一会话的并发轮次，二段式改用 `messageId` + `execution_id`；
3. **单实例限制**：`ChatStopRegistry` 为 JVM 内存，停止请求必须落在同一实例；
4. **静默解绑**：`stop` 会立即 `unbind`，若 Agent 仍在收口，迟到事件不会再推送。

一段式 signal 停止满足单实例、会话粒度、显式停止的基础需求；跨实例、可重连、可观测的停止统一迁移到二段式停止设计。

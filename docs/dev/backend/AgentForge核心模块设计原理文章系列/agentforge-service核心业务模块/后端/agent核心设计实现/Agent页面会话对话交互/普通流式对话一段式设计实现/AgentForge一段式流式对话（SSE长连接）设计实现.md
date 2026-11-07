# AgentForge 一段式流式对话（SSE 长连接）设计实现

## 一、定位与结论

一段式（legacy）流式对话：**一次请求即建立一条 SSE 长连接，Agent 执行与这条连接生命周期绑定**，事件直接写回当前连接。它用于灰度回退，二段式（submit + stream）是主链路。

核心特征：

- 一个 HTTP 请求同时承载“启动任务”和“消费事件”；
- 会话生命周期入口是 `LegacyChatStreamService#stream`；
- 事件出口是 `ServiceStreamingMiddleware`，状态落库是 `SessionLifecycleMiddleware`；
- 停止以 `sessionId` 为标识，通过内存 `activeStops` 定位本机 Agent。

## 二、接口

| 接口 | 方法 | 说明 |
| --- | --- | --- |
| `/api/chat/stream` | `POST`，`text/event-stream` | 一段式实时流式对话，`ChatStreamRequest` |
| `/api/chat/stop` | `POST`，`?sessionId=` | 停止一段式流式对话 |
| `/api/agent/chat` | `POST` | 一次性非流式调用，等待完整结果 |
| `/api/chat/tasks/*` | — | 二段式接口（提交 / 订阅 / 停止） |

`AgentChatController` 只做协议转换：

```java
@PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(@RequestBody ChatStreamRequest body) {
    return chatStreamService.stream(body, currentUserId());
}
```

## 三、核心流程

```text
前端 POST /api/chat/stream
  → AgentChatController.stream
  → LegacyChatStreamService.stream(body, userId)
     1. validate(body)
     2. runtimeConfigResolver.resolve(aid, debug)
     3. sessionService.initChat(...)              创建会话 + USER 消息 + AI 占位消息(CREATED)
     4. agentManager.getAgentAndInitAgent(...)    初始化/复用 Agent 实例
     5. new SseEmitter(0L)                        永不超时的长连接
     6. streamingMiddleware.bind(sessionId, emitter)   绑定本轮事件出口
     7. request.setExecutionId(manager.createExecutionId(agent))
     8. activeStops.put(sessionId, () -> manager.cancel(agent, sessionId, executionId))
     9. emitter.onCompletion/onTimeout → streamingMiddleware.unbind(sessionId)
    10. executor.execute(() -> start(...))        提交后台线程执行
  → start(): manager.stream(agent, request)
        .onCompleteResponse(r -> activeStops.remove(sessionId))
        .onError(e -> activeStops.remove(sessionId))
        .ignoreErrors()
        .start()
```

要点：

- `SseEmitter(0L)` 表示不主动超时，连接由客户端或 Agent 结束；
- Agent 执行放在独立线程池，避免阻塞请求线程；
- `bind` 在启动前完成，保证 `onInitComplete` 之后的首个事件就能写出；
- `ignoreErrors()` 保证流式错误通过回调处理，不向调用线程抛出。

## 四、组件职责

| 组件 | 职责 |
| --- | --- |
| `AgentChatController` | SSE 接口与停止接口入口 |
| `LegacyChatStreamServiceImpl` | 编排初始化、绑定、执行、停止 |
| `AgentRuntimeConfigResolver` | 解析 aid + debug 对应的 Agent 配置 |
| `AgentManagerFactory` / `IAgentManager` | 创建/复用 Agent，`stream`/`cancel` 统一协议 |
| `ServiceStreamingMiddleware` | 事件出口：把各阶段回调转成 `StreamEventResponse` 写入 SSE |
| `SessionLifecycleMiddleware` | 状态与落库：AI 消息 CREATED→RUNNING→终态，会话 RUNNING→FINISHED，写 `execution_id` |

## 五、事件流

`ServiceStreamingMiddleware` 按 Agent 回调产生事件，直接 `Sink.publish` 到当前 SSE：

| event | 产生时机 |
| --- | --- |
| `session` | `onInitComplete` |
| `think` / `resp` | 模型 reasoning / 回答增量 |
| `tool_call` | 工具开始、完成、失败 |
| `retry` | 模型调用重试 |
| `compact` | 上下文压缩 |
| `user_message` | 运行中追加消息 |
| `resp_end` | `onStop`（正常/取消/最大步数） |
| `error` | `onStopWithError` |

事件协议细节见《Agent会话、消息状态与流式事件一览表》。

## 六、状态落库

一段式与二段式共用同一套中间件：

- `SessionLifecycleMiddleware.onInitComplete`：AI 消息 `CREATED → RUNNING`，写 `started_at` 与 `execution_id`，会话 `→ RUNNING`；
- `onStop`：按 `StopResultState` 映射消息终态（`NORMAL→SUCCEEDED`、`CANCEL→CANCELLED`、`MAX_STEPS→FAILED`），一次性写 `content/events/elapsed_ms`，会话 `→ FINISHED`；
- `onStopWithError`：消息 `FAILED`。

## 七、与二段式的差异

| 维度 | 一段式（本文） | 二段式 |
| --- | --- | --- |
| 请求模型 | 一个请求同时启动与消费 | `submit` 启动，`stream` 独立订阅 |
| 连接语义 | 连接即任务，断开即失去实时消费 | 连接与任务解耦，可重连重放 |
| 任务标识 | `sessionId` | `messageId`（AI 消息主键） |
| 停止接口 | `/api/chat/stop?sessionId=` | `/api/chat/tasks/stop?messageId=` |
| 停止权威来源 | 连接与内存状态 | 消息表状态 + 超时 CAS |
| 断线重连 | 不支持重放 | 支持 `afterSeq` 重放 |
| 多实例 | `activeStops` 单机内存 | 预留 Redis 取消通道 |

二段式是主链路，一段式保留用于灰度回退。

## 八、边界与限制

1. **连接即生命周期**：SSE 断开后前端无法继续实时消费，只能依赖后续历史查询；
2. **停止定位粗**：以 `sessionId` 标识，同一会话并发新一轮时语义不精确；
3. **无超时兜底**：停止只下发取消信号，没有 `TIMEOUT_CANCELLED` 兜底收口；
4. **单实例**：`activeStops` 是 JVM 内存，跨实例停止无法直接命中；
5. **无重放**：事件不落可重放游标，断线丢失实时增量。

这些限制正是二段式设计要解决的问题；一段式文档用于说明现有实现与迁移边界。

# AgentForge 二段式流式对话停止与超时取消设计方案

## 一、设计结论

二段式流式对话中，`submit` 启动后台 Agent Run，`stream` 只消费事件，因此关闭 SSE 连接不能表示停止任务。前端停止生成时应调用独立停止接口：

```http
POST /api/chat/tasks/stop?messageId={messageId}
```

停止接口执行三个步骤：

1. 校验 `messageId` 对应 AI 消息归当前用户所有，并且消息仍处于可取消状态。
2. 从消息记录读取 `execution_id`，定位本机 Agent Run，调用 `agent.cancel(memoryId, executionId)` 下发取消信号。
3. 轮询等待 `SessionLifecycleMiddleware.onStop(CANCEL)` 将消息正常收口；超时后通过 CAS 将仍为运行态的消息更新为 `TIMEOUT_CANCELLED`。

手动停止必须精确到某次执行，因此 `ai_agent_session_message` 需要持久化本轮的 `execution_id`：消息初始化时为空，Agent Run 进入 `onInitComplete` 时由 `SessionLifecycleMiddleware` 从 `AgentChatContext.getExecutionId()` 写入。

```text
前端点击停止
  → POST /api/chat/tasks/stop?messageId=102
  → 校验消息归属与状态
  → 读取 message.execution_id
  → 查找本机 cancel handle
  → agent.cancel(memoryId, executionId)
  → ReAct Agent 检测取消状态并主动中断
  → Middleware.onStop(CANCEL)
      ├─ ServiceStreamingMiddleware 发布 resp_end
      └─ SessionLifecycleMiddleware
           ├─ message.status = CANCELLED
           └─ session.status = FINISHED
  → 停止接口轮询观察到终态
  → 返回 CANCELLED

若等待超时：
  → CAS: CREATED/RUNNING → TIMEOUT_CANCELLED
  → session.status = FINISHED
  → 返回 TIMEOUT_CANCELLED
```

消息表状态是取消结果的唯一权威来源。停止接口不能因为 `cancel()` 返回成功就立即宣称消息已取消；`cancel()` 只表示取消信号已被接受，最终状态必须等待 Agent Middleware 收口或超时 CAS。

## 二、目标与边界

### 2.1 目标

| 目标 | 实现方式 |
| --- | --- |
| 明确停止指定的一轮回答 | 使用 AI `messageId`，不使用容易混淆轮次的 `sessionId` |
| 真正停止 Agent 执行 | 消息持久化 `execution_id`，通过本地 cancel handle 调用 `agent.cancel(memoryId, executionId)` |
| 调用方同步拿到确定结果 | 停止接口轮询等待消息进入终态后返回 |
| Agent 未及时响应取消时兜底 | 超时后 CAS 更新为 `TIMEOUT_CANCELLED` |
| 保留停止前已经产生的内容 | 不删除已写入 Store 的事件，正常取消补发 `resp_end` |
| 避免取消覆盖正常完成 | 所有状态更新使用运行态条件 CAS |

### 2.2 当前边界

- 当前 `activeStops` 位于启动任务的 JVM 内存中，第一阶段只保证单实例或粘滞路由下真正调用到 Agent。
- SSE 订阅连接与停止接口相互独立。停止时前端应继续消费事件，直到收到 `resp_end` 或订阅自然结束。
- `TIMEOUT_CANCELLED` 表示服务端在限定时间内没有观察到 Agent 的正常取消收口，不等同于底层模型请求一定已经物理停止。
- JVM 崩溃恢复、跨实例取消通知和 Redis cancel command 属于后续扩展。

## 三、状态模型

会话、消息、Agent 停止状态与流式事件的完整对照见相邻目录《Agent会话、消息状态与流式事件一览表.md》。

### 3.1 消息状态扩展

`SessionMessageStatusEnum` 增加取消中与超时取消：

```java
TIMEOUT_CANCELLED(6, "超时取消");
CANCELLING(7, "取消中")
```

完整状态：

| 状态 | 编码 | 是否终态 | 说明 |
| --- | ---: | --- | --- |
| `CREATED` | 1 | 否 | AI 占位消息已创建 |
| `RUNNING` | 2 | 否 | Agent 正在执行 |
| `FAILED` | 3 | 是 | Agent 执行失败 |
| `CANCELLED` | 4 | 是 | Agent 正常响应取消并由 Middleware 收口 |
| `SUCCEEDED` | 5 | 是 | Agent 正常完成 |
| `TIMEOUT_CANCELLED` | 6 | 是 | 取消等待超时，由停止接口兜底收口 |
| `CANCELLING` | 7 | 否 | 已受理停止请求，等待 Agent 收口 |

`CANCELLING` 是取消意图状态，不是最终结果。发起停止时先把消息从 `CREATED/RUNNING` 条件更新为 `CANCELLING`（谁先 CAS 成功谁负责下发 `agent.cancel`），随后等待 Middleware 收口为 `CANCELLED`，或超时兜底为 `TIMEOUT_CANCELLED`。这样重复停止请求看到 `CANCELLING` 只会等待，不会重复下发取消。

会话状态 `SessionStatusEnum` 同步增加：

```java
CANCELLING(4, "取消中")
```

停止受理时，`ai_agent_session.status` 从 `RUNNING` 置为 `CANCELLING`；消息终态收口后回到 `FINISHED`。

`TIMEOUT_CANCELLED` 必须加入所有终态判断，包括：

- `SessionStatusTransfer.isTerminal`
- `ReconnectableChatStreamService.isTerminal`
- 历史详情与前端消息状态映射
- RUNNING 超时扫描的排除条件

### 3.2 状态转换

```text
CREATED ──┬──→ RUNNING ──┬──→ SUCCEEDED
          │              ├──→ FAILED
          │              └──→ CANCELLED（正常取消收口）
          │
          └──→ CANCELLING ──┬──→ CANCELLED（Agent 正常收口）
      （受理停止请求）        ├──→ SUCCEEDED / FAILED（自然终态获胜）
                            └──→ TIMEOUT_CANCELLED（等待超时兜底）
```

停止接口只允许对 `CREATED/RUNNING` 发起取消：条件 CAS 到 `CANCELLING`。若消息已经是 `CANCELLING`，只等待不重复下发；若已经是终态，直接返回当前终态，实现接口幂等。

## 四、接口设计

### 4.1 请求

```http
POST /api/chat/tasks/stop?messageId=102
```

`messageId` 必须是 AI 消息主键。接口需要完成以下校验：

1. 消息存在且未删除。
2. `role_type = AI`。
3. 消息所属会话归当前用户所有。
4. 消息状态允许取消，或者已经是可直接返回的终态。

### 4.2 返回结构

新增返回对象 `AgentChatTaskStopVo`：

```json
{
  "messageId": 102,
  "cancelRequested": true,
  "status": 4,
  "statusName": "CANCELLED",
  "timedOut": false
}
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `messageId` | 本轮 AI 消息 ID |
| `cancelRequested` | 是否向本地 Agent 下发过取消信号 |
| `status` | 接口返回时数据库中的最终消息状态 |
| `statusName` | 状态枚举名，便于调试和日志排查 |
| `timedOut` | 是否由等待超时路径收口为 `TIMEOUT_CANCELLED` |

停止接口返回 HTTP 200 表示请求已完成处理，业务结果以 `status` 为准。消息已先一步成功或失败也返回 200 和真实终态，不把正常竞态转成接口异常。

## 五、服务端核心流程

### 5.1 Controller

`AgentChatTaskController#stop` 只负责协议转换：

```java
@PostMapping("/stop")
public AgentChatTaskStopVo stop(@RequestParam Long messageId) {
    return agentChatTaskService.stop(messageId, currentUserId());
}
```

归属校验、cancel handle 查找、轮询和超时 CAS 全部由 Service 实现。

### 5.2 Service 流程

```text
stop(messageId, userId)
  1. requireOwnedAiMessage(messageId, userId)
  2. 如果消息已终态，直接返回当前状态
  3. CAS: CREATED/RUNNING → CANCELLING(7)
     ├─ 成功（firstCanceller=true）：session → CANCELLING
     │    - activeStops.remove(messageId)
     │      ├─ 命中：执行 cancel handle，cancelRequested=true
     │      └─ 未命中：cancelRequested=false，仍进入等待
     └─ 失败：说明已有请求在取消中或已终态，不重复下发
  4. 在 waitTimeoutMs 内轮询消息状态
     ├─ CANCELLED：正常取消完成
     ├─ SUCCEEDED/FAILED：取消与自然终态竞争，自然终态获胜
     ├─ TIMEOUT_CANCELLED：其他请求已经执行超时收口
     └─ CREATED/RUNNING/CANCELLING：继续等待
  5. 超时后执行 timeoutCancelIfRunning(messageId)
     ├─ CAS 成功：更新 session=FINISHED，返回 TIMEOUT_CANCELLED
     └─ CAS 失败：重新查询消息，返回竞争获胜的真实终态
```

即使没有命中本地 cancel handle，也不能立即返回失败。可能原因包括任务刚好完成、停止请求重复到达，或任务运行在其他实例；接口仍应等待状态变化，最后由终态或超时 CAS 给出确定结果。

### 5.3 轮询等待

建议通过 `EnvironmentContext` 提供：

```yaml
agentforge:
  chat:
    cancel:
      wait-timeout-ms: ${AGENTFORGE_CHAT_CANCEL_WAIT_TIMEOUT_MS:15000}
      poll-interval-ms: ${AGENTFORGE_CHAT_CANCEL_POLL_INTERVAL_MS:200}
```

对应方法：

```java
long getChatCancelWaitTimeoutMs();
long getChatCancelPollIntervalMs();
```

约束：

- `wait-timeout-ms` 必须大于 0。
- `poll-interval-ms` 必须大于 0 且小于等待超时。
- 使用单调时间计算截止点，避免系统时间回拨影响等待。
- 线程被中断时恢复中断标记，并提前进入超时收口判断。
- 轮询只查询单条消息状态，不持有数据库事务。

伪代码：

```java
long deadline = System.nanoTime() + timeoutNanos;
while (System.nanoTime() < deadline) {
    Message message = messageMapper.selectById(messageId);
    if (isTerminal(message.getStatus())) {
        return resultOf(message, cancelRequested);
    }
    LockSupport.parkNanos(pollIntervalNanos);
    if (Thread.currentThread().isInterrupted()) {
        break;
    }
}
return timeoutCancel(messageId, cancelRequested);
```

## 六、Agent 与 Middleware 收口

### 6.1 取消信号

当前 ReAct Agent 已支持主动中断，取消以 `(memoryId, executionId)` 精确定位：

```text
manager.cancel(agent, sessionId, executionId)
  → IAgent.cancel(memoryId, executionId)
  → AgentRunRegistry.cancel(...) 命中当前执行
  → AgentRunControl.requestCancel(reason)
      ├─ status = CANCEL_REQUESTED
      ├─ 关闭模型句柄（HTTP / SSE）
      ├─ cancel 工具与重试 Future
      └─ interrupt 执行线程
  → ReActTokenStream 循环或回调栅栏检测取消
  → StopResultState.CANCEL
  → AgentMiddlewareManager.triggerOnStop(...)
```

`execution_id` 由 `SessionLifecycleMiddleware.onInitComplete` 从 `AgentChatContext.getExecutionId()` 写入消息，保证停止请求能命中正确的那次执行，而不是同一会话的新一轮。

不新增单独 `onCancel`。统一使用 `onStop`，通过 `StopResultState` 区分：

| `StopResultState` | `StopResult` 字段 | 消息终态 |
| --- | --- | --- |
| `NORMAL` | `result` | `SUCCEEDED` |
| `CANCEL` | `reason` | `CANCELLED` |
| `MAX_STEPS` | `reason` | `FAILED` |

### 6.2 事件和状态顺序

Middleware 注册顺序必须保证：

```text
ServiceStreamingMiddleware.onStop(CANCEL)
  → Sink.publish(resp_end)
  → Sink.close/flush
      happens-before
SessionLifecycleMiddleware.onStop(CANCEL)
  → message.status = CANCELLED
  → session.status = FINISHED
```

`SessionLifecycleMiddleware` 同时在历史事件集合末尾补充 `resp_end`，保证实时消费和历史重放都能识别回答结束。

## 七、超时取消 CAS

### 7.1 为什么必须使用 CAS

停止等待超时的一瞬间，Agent 可能正好自然完成或刚完成正常取消。如果直接执行无条件 UPDATE，会把 `SUCCEEDED/CANCELLED/FAILED` 覆盖成 `TIMEOUT_CANCELLED`。

必须限制旧状态：

```sql
UPDATE ai_agent_session_message
SET status = #{status},
    finished_at = #{finishedAt},
    elapsed_ms = #{elapsedMs},
    modify_time = #{modifyTime}
WHERE id = #{messageId}
  AND status IN (1, 2, 7)
  AND is_deleted = 0;
```

`elapsedMs` 由服务端用 `finished_at - COALESCE(started_at, create_time)` 计算后传入，避免 MySQL 与 SQLite 的时间函数方言差异；MySQL 与 SQLite 共用同一段条件更新语义。允许 `7(CANCELLING)` 是为了兜底已经在取消中、但 Agent 未及时收口的消息。

- 影响行数为 1：超时取消成功。
- 影响行数为 0：其他终态已经获胜，必须重新查询并返回真实状态。

### 7.2 会话状态更新

消息 CAS 成功后，将所属 session 更新为 `FINISHED(2)`。更新时仍需防止覆盖同会话可能存在的新一轮 RUNNING 消息：

```text
若该 session 不存在其他 CREATED/RUNNING AI 消息
    → session.status = FINISHED
否则
    → 保持 RUNNING
```

第一阶段已限制同一会话只能有一个运行消息时，可以直接更新；放开并发会话前必须增加上述条件检查。

### 7.3 迟到回调

超时 CAS 成功后，旧 Agent 仍可能产生迟到的 `onStop`。因此正常收口的 `finish` 也必须是条件更新：

```sql
UPDATE ai_agent_session_message
SET status = #{status}, ...
WHERE id = #{id}
  AND status IN (1, 2, 7)
  AND is_deleted = 0;
```

只有仍处于 `CREATED/RUNNING/CANCELLING` 的消息才允许进入终态，迟到的正常收口不能覆盖 `TIMEOUT_CANCELLED`。

事件 Sink 也需要终止保护：超时后应关闭对应 Sink 或把运行上下文标记为 finished，拒绝继续追加业务事件。当前本地实现可在超时收口时调用任务注册表的清理方法；未来 Redis 实现可通过 execution lease/generation 防止旧任务继续写入。

## 八、并发与幂等

| 场景 | 结果 |
| --- | --- |
| 重复点击停止 | 第一次下发 cancel；后续请求读取并返回相同终态 |
| cancel 与正常完成竞争 | 先完成状态 CAS 的一方获胜，停止接口返回真实终态 |
| cancel handle 未找到 | 继续等待；超时后 CAS `TIMEOUT_CANCELLED` |
| 两个停止请求同时超时 | 只有一个 CAS 成功，另一个重读相同终态 |
| Agent 正常取消发生在超时 CAS 前 | `CANCELLED` 获胜 |
| Agent 回调发生在超时 CAS 后 | 不得覆盖 `TIMEOUT_CANCELLED` |
| stream 正在订阅 | 继续读取已产生事件；正常取消会收到 `resp_end` |
| stream 已断开 | 不影响 cancel 和状态收口，重连后可继续重放 |

## 九、多实例与 Redis 扩展

当前 `activeStops` 是本机 `messageId → Runnable`，停止请求如果落到其他实例将无法直接调用 Agent。Redis 阶段增加取消命令通道：

```text
停止接口
  → SET agentforge:chat:message:{messageId}:cancel = 1 EX ttl
  → PUBLISH agentforge:chat:cancel {messageId}

所有实例监听
  → 本机 TaskRegistry 命中 messageId
  → 执行 cancel handle

后台 Agent Run
  → 在安全点检查 cancel key，作为 Pub/Sub 丢失的兜底
```

Pub/Sub 只负责低延迟通知，带 TTL 的 cancel key 保存取消意图。停止接口的轮询与超时 CAS 不依赖 Pub/Sub 一定送达，因此通知丢失也不会让消息永久停留在 RUNNING。

未来 Redis 扩展仍复用：

- `onStop(CANCEL)` 的事件和状态收口。
- `messageId` 作为任务标识。
- 消息表作为最终状态权威来源。
- 停止接口轮询和超时 CAS 语义。

## 十、前端交互

1. 用户点击停止后调用 `/api/chat/tasks/stop`，按钮进入“停止中”状态，禁止重复快速提交。
2. 二段式 stream 不应立即 abort，继续接收服务端 `resp_end` 和已经落入 Store 的尾事件。
3. 停止接口返回 `CANCELLED` 后展示“用户已停止”。
4. 返回 `TIMEOUT_CANCELLED` 时展示“停止超时，任务已强制收口”，并停止 loading。
5. 返回 `SUCCEEDED/FAILED` 表示自然终态赢得竞争，页面按真实状态展示。
6. 接口网络失败时不要本地伪造终态，应重新查询消息详情确认状态。

## 十一、代码改造清单

| 位置 | 改造 |
| --- | --- |
| `ai_agent_session_message` 增量 SQL | 新增 `execution_id`（MySQL `sql/increment`、SQLite `sql/sqllite`） |
| `AgentSessionMessagePojo/Mapper/XML` | 读写 `execution_id`；`finish` 增加运行态条件；新增 `timeoutCancelIfRunning` |
| `SessionLifecycleMiddleware` | `onInitComplete` 写入 `executionId`；终态收口使用运行态条件更新 |
| `SessionMessageStatusEnum` | 新增 `CANCELLING(7)` 与 `TIMEOUT_CANCELLED(6)` |
| `SessionStatusEnum` | 新增 `CANCELLING(4)` |
| `SessionStatusTransfer` | 终态判断纳入 `TIMEOUT_CANCELLED`，`CANCELLING` 不算终态 |
| `AgentSessionMessageMapper` | 新增 `markCancelling` 条件更新 |
| `EnvironmentContext` | 增加取消等待超时与轮询间隔配置 |
| `AgentChatTaskController` | `stop` 返回 `AgentChatTaskStopVo` |
| `AgentChatTaskService` | `stop(messageId, userId)` 返回确定停止结果 |
| `AgentChatTaskServiceImpl` | 归属校验、读取 executionId、下发 cancel、状态轮询和超时 CAS |
| `AgentSessionMapper` | 消息超时收口后安全更新会话状态 |
| `ReconnectableChatStreamService` | 将 `TIMEOUT_CANCELLED` 识别为终态 |
| 前端 Transport/UI | 停止时继续订阅，并处理 CANCELLED/TIMEOUT_CANCELLED |

## 十二、测试与验收

### 12.1 正常取消

1. 运行中调用 stop，Agent 收到 cancel。
2. Store 最后一个业务事件为 `resp_end`。
3. message 变为 `CANCELLED`，session 变为 `FINISHED`。
4. stop 返回 `cancelRequested=true/status=CANCELLED/timedOut=false`。

### 12.2 超时取消

1. 使用不响应 cancel 的测试 Agent。
2. 等待超时后 CAS 将 message 更新为 `TIMEOUT_CANCELLED`。
3. stop 返回 `timedOut=true`。
4. 迟到的 Agent 回调不能覆盖状态。

### 12.3 竞态与幂等

1. stop 与正常完成同时发生，最终状态只能有一个。
2. 两个 stop 并发调用，最多下发一次本地 cancel，返回同一终态。
3. 已 `SUCCEEDED/FAILED/CANCELLED/TIMEOUT_CANCELLED` 的消息再次 stop，直接返回原状态。
4. cancel handle 未找到时仍会等待并按规则收口。
5. 消息不属于当前用户时拒绝操作。

### 12.4 流式消费

1. 正常取消时在线 stream 收到 `resp_end` 后结束。
2. stream 断开不影响停止接口执行。
3. 停止后从 `afterSeq=0` 重连，可以重放停止前内容。
4. 超时取消没有尾事件时，消费者观察终态并完成尾读后仍能结束。

## 十三、必须保持的设计不变量

1. `agent.cancel` 只表示下发信号，消息状态才是最终取消结果。
2. 正常取消必须由 `onStop(CANCEL)` 统一发布结束事件并更新状态。
3. 超时取消只能从 `CREATED/RUNNING` 条件更新，不能覆盖其他终态。
4. 取消不删除已经产生的事件和正文。
5. 停止接口以 `messageId` 标识任务，并校验消息归属。
6. SSE 连接生命周期不参与任务取消判断。
7. 迟到 Agent 回调不能覆盖 `TIMEOUT_CANCELLED`。
8. 多实例通知失败不能阻止超时兜底收口。

# AgentForge Agent State 持久化设计实现

## 1. 设计目标与边界

AgentForge 需要为每一次 Agent 执行建立统一、可查询、可持久化的状态模型。该模型要回答三个问题：

1. 这次执行现在处于什么生命周期（运行中、取消中、已结束）？
2. 它当前在 ReAct 的哪个阶段（模型调用、重试等待、工具执行、补偿、收口）？
3. 结束后是什么结果（成功、取消、模型错误、步长上限），失败或取消的原因是什么？

设计边界：

- Framework 负责状态语义、内存控制对象和状态存取 SPI；
- 平台层负责真正的持久化介质（MySQL、Redis 等）、任务表和恢复编排；
- 状态模块不读取平台数据库，也不承担跨进程恢复职责；
- 审核中断等挂起场景不在本文范围，见恢复专题文档。

> 异常停止后的恢复行为与实现细节，见同级文档：`AgentForge Agent运行恢复设计方案.md`。

## 2. 状态模型

状态由三层组成，职责单一，不能互相替代：

| 层次 | 类型 | 作用 |
| --- | --- | --- |
| 生命周期 | `AgentExecutionStatus` | 任务整体是运行中、取消中还是已结束 |
| 执行阶段 | `AgentExecutionPhase` | 当前处于 ReAct 的哪个位置 |
| 状态快照 | `AgentExecutionState` | 对外查询和持久化的一次完整不可变结果 |

### 2.1 `AgentExecutionStatus`

```java
public enum AgentExecutionStatus {
    CREATED,
    RUNNING,
    CANCEL_REQUESTED,
    COMPLETED,
    CANCELLED,
    FAILED
}
```

| 状态 | 含义 | 是否终态 |
| --- | --- | --- |
| `CREATED` | 运行控制已创建，尚未进入 ReAct 循环 | 否 |
| `RUNNING` | 正在执行 ReAct 循环 | 否 |
| `CANCEL_REQUESTED` | 已受理取消，正在关闭句柄、中断任务并补偿消息 | 否 |
| `COMPLETED` | 正常结束，或达到最大步数后按既定结果结束 | 是 |
| `CANCELLED` | 取消收口完成，消息补偿与 `onStop(CANCEL)` 已完成 | 是 |
| `FAILED` | 未被框架转化为正常结果的异常终止 | 是 |

`MAX_STEPS`、`MODEL_CALL_ERROR` 等具体原因不放进生命周期枚举，而是放在 `AgentRunState`/`resultState`。

### 2.2 `AgentExecutionPhase`

```java
public enum AgentExecutionPhase {
    INITIALIZING,
    BEFORE_LOOP,
    MODEL_CALL,
    MODEL_RETRY_WAIT,
    TOOL_CALL,
    BETWEEN_STEPS,
    COMPENSATING,
    FINALIZING,
    FINISHED
}
```

| 阶段 | 含义 |
| --- | --- |
| `INITIALIZING` | 已创建运行控制，尚未进入循环；流式场景对应“已返回 TokenStream 但未 start” |
| `BEFORE_LOOP` | 一轮循环开始前：beforeLoop 中间件、刷新系统消息、构建请求 |
| `MODEL_CALL` | 正在请求模型：同步等待响应或流式接收 token |
| `MODEL_RETRY_WAIT` | 模型失败后的退避等待，尚未发起下一次请求 |
| `TOOL_CALL` | 正在执行本批工具，包含顺序和并发执行 |
| `BETWEEN_STEPS` | 工具批次完成，准备进入下一轮 think |
| `COMPENSATING` | 已取消，正在补齐 ChatMemory 消息链 |
| `FINALIZING` | 已得到终态结果，正在收口中间件与状态写入 |
| `FINISHED` | 收口完成；具体终态由 status 决定 |

状态与阶段是两个维度：

- `RUNNING + MODEL_CALL`：模型调用中；
- `RUNNING + TOOL_CALL`：工具执行中；
- `CANCEL_REQUESTED + TOOL_CALL`：已请求取消，正在中断工具；
- `CANCEL_REQUESTED + COMPENSATING`：正在补偿消息链；
- `CANCELLED + FINISHED`：取消收口完成。

### 2.3 `RunningToolState` 与 `ToolExecutionStatus`

```java
public final class RunningToolState {
    private final String requestId;
    private final String toolName;
    private final ToolExecutionStatus status;
    private final long startedAtMillis;
    private final Long finishedAtMillis;
}
```

```java
public enum ToolExecutionStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    CANCEL_REQUESTED,
    CANCELLED,
    FAILED
}
```

并发工具调用时，`runningTools` 记录本批全部工具，而不是只记录一个。状态中不暴露工具参数和返回内容，避免复制大对象和泄漏敏感数据。

### 2.4 `AgentExecutionState`

```java
public final class AgentExecutionState {
    private final String executionId;
    private final AgentExecutionStatus status;
    private final AgentExecutionPhase phase;
    private final int currentStep;
    private final List<RunningToolState> runningTools;
    private final AgentRunState resultState;
    private final String message;
    private final long updatedAtMillis;
}
```

| 字段 | 说明 |
| --- | --- |
| `executionId` | 一次执行的唯一标识，用于隔离同一 memoryId 的上一轮迟到回调 |
| `status` | 整体生命周期 |
| `phase` | 当前 ReAct 执行阶段 |
| `currentStep` | 当前步数 |
| `runningTools` | 当前工具批次状态快照 |
| `resultState` | 结束后的 `AgentRunState`，运行中为 null |
| `message` | 取消原因或失败摘要，运行中为 null |
| `updatedAtMillis` | 最近一次状态更新时间 |

设计约束：

- 不可变：不提供 setter，`runningTools` 构造时防御性复制并包装为不可修改集合；
- 只包含可持久化的简单数据（String、枚举、数值、只读列表）；
- 不暴露 `Future`、线程、HTTP 连接、Atomic 类型和异常对象；
- `memoryId` 不放进快照，它是 registry/store 的检索键，不是状态本身；
- 取消状态可由 `status == CANCEL_REQUESTED/CANCELLED` 判断，不再冗余布尔字段。

## 3. 状态唯一真相源

### 3.1 `AgentRunControl`

`AgentRunControl` 是 Framework 内部的可变控制对象，负责：

- 维护 status/phase/step/tools；
- 登记当前模型调用句柄、工具/重试 Future、执行线程；
- 受理取消并在 `finish` 中完成唯一一次终态收口。

并发约定：

- 除 `terminal` 外，所有可变字段由同一把 `lock` 保护；
- 锁内只做状态赋值和句柄快照，不执行关闭连接、等待 Future、调用工具或用户回调；
- `terminal` 是 CAS 闸门，正常完成、取消和异常只有一方能写终态。

对外只暴露 `snapshot()` 生成的不可变快照。

### 3.2 `AgentRunRegistry`

```java
AgentRunControl register(String executionId, Object memoryId);
AgentRunControl current(Object memoryId);
boolean isCurrent(Object memoryId, String executionId);
Optional<AgentExecutionState> snapshot(Object memoryId, String executionId);
boolean cancel(Object memoryId, String executionId, String reason);
void complete(AgentRunControl control);
void clear(Object memoryId);
```

职责：

- `active`：memoryId -> 当前活跃 `AgentRunControl`；
- 查询：活跃执行直接读 `control.snapshot()`，已结束执行从 `stateStore` 读取；
- `complete`：把最终快照写入 store，并从 active 移除；
- `clear`：先取消活跃执行，再清理该 memoryId 的状态记录。

`BaseAgent` 持有 registry，`IAgent#getExecutionState` 直接委托 registry。

## 4. 状态持久化

### 4.1 Store SPI

```java
public interface AgentExecutionStateStore {
    void save(Object memoryId, AgentExecutionState state);

    Optional<AgentExecutionState> get(Object memoryId, String executionId);

    void delete(Object memoryId, String executionId);

    void deleteByMemoryId(Object memoryId);
}
```

初版实现为 `InMemoryAgentExecutionStateStore`，使用 `ConcurrentHashMap<memoryId, Map<executionId, state>>`。平台可以实现 JDBC/Redis 版本替换它。

### 4.2 存储键

- 主键：`(memoryId, executionId)`；
- `memoryId` 标识会话，`executionId` 标识一次具体运行；
- 同一会话快速开始下一轮时，旧 executionId 的迟到回调或查询不会命中新执行。

### 4.3 保存时机

| 时机 | 动作 |
| --- | --- |
| `AgentRunRegistry.register` | 保存初始快照（CREATED + INITIALIZING） |
| `AgentRunRegistry.complete` | 保存最终快照（COMPLETED/CANCELLED/FAILED） |
| 运行中查询 | 直接读取活跃 `AgentRunControl#snapshot()`，不落库 |
| `clearContext` | 删除该 memoryId 的全部状态记录 |

### 4.4 可持久化字段与运行时字段

可持久化：

- `executionId/status/phase/currentStep/runningTools/resultState/message/updatedAtMillis`
- `memoryId`（作为存储键）
- `ChatMemory` 消息（恢复的必要条件，由平台持久化）

不持久化：

- `AgentRunControl`、`AtomicBoolean`、`lock`
- `Future`、`Thread`、`CancellationHandle`
- `cancelListener`、模型连接、SSE 输入流

这些对象只在当前 JVM、当前执行内有意义，重启后天然失效。

### 4.5 终态保留与清理

- 活跃执行结束后从 `active` 移除；
- 最终快照保留在 store，供完成瞬间查询；
- 当前内存实现不做 TTL，由平台存储实现决定保留时间和容量；
- 建议生产实现使用有界 TTL，例如保留最近 N 分钟或 N 条，避免无限增长。

### 4.6 自定义持久化建议

- 表结构至少包含：`memory_id`、`execution_id`、`status`、`phase`、`current_step`、`result_state`、`message`、`updated_at`、`finished_at`（可选）；
- `running_tools` 可序列化为 JSON 子表/字段；
- 写入使用 `(memory_id, execution_id)` 唯一键 upsert；
- 不要把 `ChatMemory` 状态和任务表混在同一张表，消息需要按会话顺序读写。

## 5. 运行阶段与状态迁移

### 5.1 非流式 `run`

| 代码边界 | status / phase |
| --- | --- |
| 创建运行控制 | `CREATED / INITIALIZING` |
| 循环开始 | `RUNNING / BEFORE_LOOP` |
| 请求模型前 | `RUNNING / MODEL_CALL` |
| 模型失败重试等待 | `RUNNING / MODEL_RETRY_WAIT` |
| 登记并执行工具 | `RUNNING / TOOL_CALL` |
| 工具完成、准备下一轮 | `RUNNING / BETWEEN_STEPS` |
| 取消补偿 | `CANCEL_REQUESTED / COMPENSATING` |
| 终态收口 | `CANCELLED|COMPLETED|FAILED / FINALIZING -> FINISHED` |

### 5.2 流式 `runStream`

- 返回 TokenStream 但未 `start()`：`CREATED / INITIALIZING`；
- `start()`：`RUNNING / BEFORE_LOOP`；
- 发起流式请求：`RUNNING / MODEL_CALL`；
- partial token：只更新 `updatedAtMillis`，不改变 phase；
- 完整响应包含工具调用：`RUNNING / TOOL_CALL`；
- 工具完成：`RUNNING / BETWEEN_STEPS`，随后进入下一轮；
- 无工具调用：进入 `FINALIZING`，最终 `COMPLETED / FINISHED`。

### 5.3 取消迁移

```text
RUNNING / MODEL_CALL 或 TOOL_CALL
              |
              | cancel(memoryId, executionId)
              v
CANCEL_REQUESTED / 保留当前 phase
              |
              | 关闭模型/工具句柄、中断线程
              v
CANCEL_REQUESTED / COMPENSATING
              |
              | ChatMemory 补偿 + onStop(CANCEL)
              v
CANCELLED / FINISHED
```

规则：

1. 受理取消时立即写入 `CANCEL_REQUESTED`，调用方无需等待线程退出；
2. 刚取消时保留原 phase，便于判断取消发生在模型还是工具阶段；
3. 补偿前切到 `COMPENSATING`；
4. 只有统一收口完成后才进入 `CANCELLED / FINISHED`；
5. 重复取消不重复迁移，也不覆盖第一次取消原因。

### 5.4 状态/阶段组合速查

| status | phase | 含义 |
| --- | --- | --- |
| `CREATED` | `INITIALIZING` | 已创建，未启动 |
| `RUNNING` | `BEFORE_LOOP` | 一轮开始前 |
| `RUNNING` | `MODEL_CALL` | 等模型 |
| `RUNNING` | `MODEL_RETRY_WAIT` | 模型重试退避 |
| `RUNNING` | `TOOL_CALL` | 工具执行中 |
| `RUNNING` | `BETWEEN_STEPS` | 工具完成，准备下一轮 |
| `CANCEL_REQUESTED` | `TOOL_CALL` | 已取消，正在中断工具 |
| `CANCEL_REQUESTED` | `COMPENSATING` | 正在补消息 |
| `COMPLETED` | `FINISHED` | 正常结束 |
| `CANCELLED` | `FINISHED` | 取消收口完成 |
| `FAILED` | `FINISHED` | 异常终止 |

## 6. 状态查询

### 6.1 查询接口

```java
public interface IAgent {
    Optional<AgentExecutionState> getExecutionState(Object memoryId, String executionId);
}
```

语义：

- 只读取 Framework 状态，不读平台数据库；
- `Optional.empty()` 表示当前没有匹配记录；
- 查询不触发 middleware，不改变取消状态，不等待模型或工具；
- 当前实现提供按 `(memoryId, executionId)` 的精确查询；单参数便捷查询可按需补充。

### 6.2 快照一致性

`AgentRunControl.snapshot()` 在同一把锁内复制所有字段和工具列表，保证：

- 调用方看到的是同一时刻的一致状态；
- 工具列表是防御性副本，外部修改不影响内部；
- 读取快照不会阻塞模型/工具线程的长时间操作。

### 6.3 迟到回调隔离

所有模型/工具回调在更新状态或写 ChatMemory 前校验：

```text
registry.isCurrent(memoryId, executionId) && !control.isTerminal()
```

上一 executionId 的迟到回调不能更新新执行状态，也不能把 `CANCELLED` 改回 `RUNNING`。

## 7. 与恢复设计的衔接

状态持久化是恢复的前提，但恢复行为不放在本文：

- 本文保证：状态快照可持久化、可按 `(memoryId, executionId)` 查询、终态可读取；
- 恢复文档保证：根据 ChatMemory 和 `AgentRunState` 继续执行、生成新 executionId、修复消息链。

平台需要同时持久化：

1. `ChatMemory` 消息（恢复的主要内容）；
2. 任务/执行记录（sessionId、executionId、runState、状态）；
3. 可选的 `AgentExecutionState` 快照（用于观测和判断终态）。

## 8. 代码落点

```text
agentforge-agent-core
└── agent/
    ├── IAgent.java
    ├── BaseAgent.java
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
```

## 9. 验收要点

| 用例 | 预期 |
| --- | --- |
| 创建流但未 start | `CREATED + INITIALIZING` |
| 非流式模型阻塞 | `RUNNING + MODEL_CALL` |
| 模型重试退避 | `RUNNING + MODEL_RETRY_WAIT` |
| 工具执行中 | `RUNNING + TOOL_CALL`，runningTools 完整 |
| 取消模型阶段 | 立即可见 `CANCEL_REQUESTED + MODEL_CALL`，最终 `CANCELLED + FINISHED` |
| 取消工具阶段 | 立即可见 `CANCEL_REQUESTED + TOOL_CALL`，工具状态转为取消 |
| 正常完成与取消竞争 | 只出现一个终态，终态后不回退 |
| 同会话新执行 | 新旧 executionId 隔离，旧回调不能更新新状态 |
| 查询不存在执行 | `Optional.empty()` |
| 修改查询结果集合 | 集合不可修改，不影响内部状态 |

## 10. 结论

`AgentExecutionState` 是 Agent 执行状态的唯一对外快照，采用 `executionId + status + phase + currentStep + runningTools + resultState + message + updatedAtMillis` 的轻量结构，既满足查询和可观测性，又可以直接序列化落库。

`AgentRunControl/AgentRunRegistry` 是状态与取消的唯一真相源；`AgentExecutionStateStore` 提供可替换的持久化 SPI。平台层只需实现 store、持久化 ChatMemory 和任务记录，并在异常停止后按恢复文档调用 `resume`，即可完成从状态观测到快速恢复的闭环。

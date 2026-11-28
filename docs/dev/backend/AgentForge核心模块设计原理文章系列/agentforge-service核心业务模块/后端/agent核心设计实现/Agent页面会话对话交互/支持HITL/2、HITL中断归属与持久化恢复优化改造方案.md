# HITL 中断归属与持久化恢复优化

## 一、问题与结论

原实现同时依赖 MySQL 快照和 `AgentInterruptRuntimeRegistry`。Registry 保存归属、状态及 Agent 运行对象，导致：

1. Registry 和 MySQL 成为双数据源；
2. 归属校验依赖当前 JVM；
3. 服务重启后 Registry 丢失，用户无法继续审批。

最终方案：

> MySQL Store 是中断批次的唯一数据源；Service 不再维护 HITL Registry。

`AgentInterruptRuntimeRegistry` 已删除。恢复时根据 Snapshot 中的 `aid/debug` 通过 AgentManager 获取或创建 Agent。

## 二、最终架构

| 组件 | 职责 |
| --- | --- |
| `ServiceAgentContextAccessor` | 统一读写 Service 执行上下文 |
| `MysqlAgentInterruptStore` | 保存、查询、claim、完成和取消中断快照 |
| `PermissionInterruptModule` | 在 Framework 中产生和恢复中断 |
| `AgentChatTaskServiceImpl` | 校验业务归属，获取或创建 Agent，提交恢复 |
| `ServiceStreamingMiddleware` | 只转换和发送中断事件 |
| `MysqlChatMemoryStore` | 按 `memoryId` 持久化 Agent 记忆 |

恢复时序：

```text
用户提交 batchId + responses
  -> MysqlAgentInterruptStore.get(batchId)
  -> 从 Snapshot 读取 userId/messageId/sessionId/aid/debug
  -> 校验当前用户、消息和会话归属
  -> AgentRuntimeConfigResolver.resolve(aid, debug)
  -> manager.getAgentAndInitAgent(...)
       -> 已缓存：复用 Agent
       -> 进程重启：重新创建 Agent
  -> 提交 resumeInterruptStream(command)
  -> Framework 从 MySQL Store 原子 claim
  -> Framework 从 MysqlChatMemoryStore 恢复记忆
  -> 应用审批结果并继续 ReAct
  -> MysqlAgentInterruptStore.complete(batchId)
```

## 三、统一 Service 执行上下文

执行级字段统一使用 `agentforge.execution.*`：

```java
public static final String USER_ID = "agentforge.execution.userId";
public static final String AI_MESSAGE_ID = "agentforge.execution.aiMessageId";
public static final String SESSION_ID = "agentforge.execution.sessionId";
public static final String AID = "agentforge.execution.aid";
public static final String DEBUG = "agentforge.execution.debug";
```

旧的 `session.*` 和 `agentforge.trace.*` 执行键直接删除，不保留别名。

```java
public final class ServiceAgentExecutionContext {
    private final Long userId;
    private final Long aiMessageId;
    private final String sessionId;
    private final String aid;
    private final Boolean debug;
}
```

`ServiceAgentContextAccessor` 统一完成：

- 写入 `AgentRequest.extensions`；
- 从 `AgentChatContext` 读取；
- 转换为 Snapshot 属性；
- 从 Snapshot 恢复强类型上下文；
- 缺失 HITL 必填字段时 fail-closed。

只持久化上述五个字段，不整体保存 extensions，避免把密钥、运行对象或无关数据写入快照。

## 四、Framework Store 获取 ChatContext

旧的 `save(snapshot)` 已删除：

```java
public interface AgentInterruptStore {
    void save(AgentInterruptSnapshot snapshot, AgentChatContext context);

    AgentInterruptSnapshot get(String batchId);

    void claim(String batchId);

    void complete(String batchId);

    AgentInterruptSnapshot cancelPendingByMemoryId(Object memoryId);
}
```

Framework 的 `PermissionInterruptModule.preflight` 直接调用：

```java
store.save(snapshot, context);
```

Framework 不理解 `userId/messageId/aid`，只把 `AgentChatContext` 交给 Store。MySQL Store 通过 Accessor 提取业务上下文：

```java
public void save(AgentInterruptSnapshot snapshot, AgentChatContext context) {
    ServiceAgentExecutionContext executionContext =
            contextAccessor.requireForHitl(context);

    persist(snapshot.withContextAttributes(
            contextAccessor.toSnapshotAttributes(executionContext)));
}
```

Snapshot Codec schema 升级为 v2：

```json
{
  "schemaVersion": 2,
  "contextAttributes": {
    "agentforge.execution.userId": "10001",
    "agentforge.execution.aiMessageId": "9012",
    "agentforge.execution.sessionId": "2001",
    "agentforge.execution.aid": "agent_demo",
    "agentforge.execution.debug": "false"
  }
}
```

非 v2 快照直接拒绝解码，不回退到内存归属校验。

## 五、Service 恢复只保留必要编排

```java
AgentInterruptSnapshot snapshot = interruptStore.get(request.getBatchId());
ServiceAgentExecutionContext context = contextAccessor.requireForHitl(snapshot);

verifyUser(context.getUserId(), currentUserId);
verifyMessageAndSession(
        context.getAiMessageId(), snapshot.getMemoryId(), currentUserId);

InterruptBatchResumeCommand command = toResumeCommand(snapshot, request);
ResumeRuntime runtime = createResumeRuntime(snapshot, context);
taskExecutor.execute(() -> resume(runtime, command));
```

会话校验需要区分两种 ID：

- `AgentSessionMessagePojo.sessionId` 是 `ai_agent_session.id` 内部主键；
- `snapshot.memoryId` 和 `context.sessionId` 是 `ai_agent_session.session_id` 对外会话标识。

因此必须先根据消息的内部会话主键查询 `AgentSessionPojo`，再比较对外 `sessionId`，不能直接将 Long 主键与 `memoryId` 比较。

Service 不再：

- 保存中断批次；
- 维护内存批次状态；
- 通过 Registry 判断批次是否存在；
- 在 Service 中重复 claim；
- 依赖重启前的 `manager/agent` Java 对象。

恢复 Agent 的逻辑只是复用现有 Manager：

```java
AgentRuntimeConfig config =
        runtimeConfigResolver.resolve(context.getAid(), context.getDebug());

IAgentManager<Object, Object> manager =
        agentManagerFactory.getAgentManagerByType(config.getType());

Object agent = manager.getAgentAndInitAgent(
        AgentInitContext.builder()
                .aid(config.getAid())
                .agentName(config.getName())
                .agentConfig(config.getAgentConfig())
                .debug(context.getDebug())
                .channelId(AgentInitContext.WEBSITE_CHANNEL_ID)
                .build());
```

`getAgentAndInitAgent` 已具有“有缓存则复用、无缓存则创建”语义，所以不需要 HITL RuntimeRegistry。

Snapshot 中的 `memoryId` 用于恢复 MySQL ChatMemory，`sourceExecutionId` 用于继续原执行链路。

`ServiceStreamingMiddleware` 只发送 `requested/resuming/resumed/failed` 事件，并委托 `SessionLifecycleMiddleware` 更新消息生命周期。它不读写 Store，不维护批次。

## 六、数据库影响

现有 `ai_agent_interrupt_snapshot` 表已满足需求，本次不新增字段和索引：

- 归属上下文保存在 `snapshot_json.contextAttributes`；
- `schema_version` 从 1 升级为 2；
- 状态为 `PENDING / RESUMING / COMPLETED / CANCELLED`；
- claim 和 cancel 通过 MySQL 条件更新保证原子性。

等待 HITL 时取消：

```text
cancelPendingByMemoryId(memoryId)
  -> PENDING 更新为 CANCELLED
  -> 发送 cancelled 事件
  -> 收口会话消息状态
```

## 七、重启恢复边界

当前已支持“中断后服务重启，用户再提交审批”，前提是：

1. Snapshot 已写入 MySQL；
2. ChatMemory 使用 `MysqlChatMemoryStore`；
3. `aid/debug` 对应的 Agent 配置仍可解析；
4. 对应模型和 Tool 仍可用。

当前使用恢复时的 Agent 配置。如果后续必须使用中断当时的配置，再在 Snapshot 中固化配置版本标识。

## 八、改造结果

```text
改造前
Service Registry + MySQL Store
  -> 双归属源
  -> 双状态源
  -> 重启后不可恢复

改造后
MySQL Interrupt Store + MySQL ChatMemory
  -> batchId 唯一定位
  -> Snapshot 完成归属校验
  -> MySQL 完成原子 claim
  -> AgentManager 获取或创建 Agent
  -> Service 只保留简单编排
```

项目版本升级为 `1.0.2-SNAPSHOT`。

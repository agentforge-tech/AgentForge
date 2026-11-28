---
title: Agent 运行恢复：resume / resumeStream
sidebar_label: Agent 运行恢复
description: 基于已持久化的 ChatMemory 与执行状态，使用 IResumableAgent 从历史继续执行，并查询运行状态
sidebar_position: 3
displayed_sidebar: sdkSidebar
---

# Agent 运行恢复：resume / resumeStream

当模型调用异常、达到最大步数，或服务重启导致一次执行「干净地终止」后，AgentForge 允许业务层基于已持久化的会话记忆（ChatMemory）和来源执行 `executionId`，显式把这一轮继续跑下去。

恢复能力放在独立接口 `IResumableAgent` 中，与 `cancel` 对称，不影响基础 `IAgent`：

```java
public interface IResumableAgent extends IAgent {
    ChatResult resume(Object memoryId, String executionId);

    TokenStream resumeStream(Object memoryId, String executionId);
}
```

核心约定：

- 恢复入口使用 `(memoryId, executionId)` 精确定位来源执行；
- 复用已有 ChatMemory，**不会重复追加用户问题**；
- 恢复成功后生成新的 `executionId`，来源执行只作为历史记录；
- 框架不主动扫描重启任务，「谁需要恢复」由业务层决定；
- 中断审批（HITL）有独立接口，不走这里的 `resume`。

## 依赖（v1.0.0）

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-agent-core</artifactId>
    <version>1.0.1-SNAPSHOT</version>
</dependency>
```

## 1. 哪些场景可以恢复

恢复的判断依据是来源执行的 `AgentRunState`：

| 来源 `AgentRunState` | 是否恢复 | 恢复动作 |
| --- | --- | --- |
| `SUCCESS` | 不需要 | 直接返回历史结果，不调用模型 |
| `MODEL_CALL_ERROR` | 可以 | 消息链合法时重新请求模型 |
| `MAX_STEPS` | 可以 | 从第 1 步重新计数，获得新的步数预算 |
| `CANCEL` | 仅显式调用 | 业务层主动发起才恢复，框架不自动恢复 |
| `WAITING_INTERRUPT` | 不可以 | 走 HITL 中断恢复接口 |
| `null` | 不支持 | 交给后续检查点方案 |

两个前提条件必须满足：

1. **ChatMemory 已持久化**：否则重启后没有可恢复的历史消息；
2. **已记录来源 `executionId`**：恢复必须用它定位，不能用 `memoryId` 猜。

## 2. 第一步：记录 executionId 与 ChatMemory

恢复所需的最小信息是 `memoryId + executionId + ChatMemory`。`executionId` 的获取方式：

```java
// 非流式：执行完成后从结果中读取
ChatResult result = agent.run(AgentRequest.builder()
        .memoryId("session-001")
        .question("继续分析昨天的问题")
        .build());
String executionId = result.getExecutionId();

// 流式：句柄返回后、start() 之前即可读取
TokenStream stream = agent.runStream(AgentRequest.builder()
        .memoryId("session-001")
        .question("继续分析昨天的问题")
        .build());
String streamExecutionId = stream.executionId();
stream.onPartialResponse(System.out::print).start();
```

也可以由业务层预先生成并写入请求，避免再回读：

```java
String executionId = UUID.randomUUID().toString();
agent.runStream(AgentRequest.builder()
        .memoryId("session-001")
        .question("继续分析昨天的问题")
        .executionId(executionId)
        .build());
```

业务层需要把 `memoryId`、`executionId`、`AgentRunState` 和 ChatMemory 一起落库。ChatMemory 的持久化方式见 [会话记忆](./memory)。

## 3. 第二步：查询执行状态

`IAgent#getExecutionState` 返回不可变状态快照，用于判断是否需要恢复、当前执行到哪一步：

```java
Optional<AgentExecutionState> state =
        agent.getExecutionState("session-001", executionId);

state.ifPresent(s -> {
    System.out.println(s.getStatus());        // CREATED / RUNNING / CANCEL_REQUESTED / COMPLETED / CANCELLED / FAILED
    System.out.println(s.getPhase());         // MODEL_CALL / TOOL_CALL / ...
    System.out.println(s.getCurrentStep());
    System.out.println(s.getResultState());   // 结束后为 AgentRunState，运行中为 null
    System.out.println(s.getMessage());       // 取消原因 / 失败摘要
});
```

- `Optional.empty()` 表示当前没有匹配记录；
- 运行中的执行读取的是内存快照，不落库；
- 状态查询不会触发中间件，也不改变取消状态。

## 4. 第三步：非流式恢复

```java
IResumableAgent resumable = (IResumableAgent) agent;

ChatResult resumed = resumable.resume("session-001", sourceExecutionId);
String newExecutionId = resumed.getExecutionId();

System.out.println(resumed.getRes());
System.out.println(resumed.getRunState());
```

返回值与普通 `run()` 一致：

- `getRes()`：最终回答，或取消/步长限制的说明；
- `getRunState()`：`SUCCESS`、`MODEL_CALL_ERROR`、`CANCEL`、`MAX_STEPS`；
- `getExecutionId()`：本次恢复产生的新执行 ID。

如果来源历史其实已经完整结束，`resume` 会直接返回既有回答，不调用模型。

## 5. 第四步：流式恢复

```java
IResumableAgent resumable = (IResumableAgent) agent;

TokenStream stream = resumable.resumeStream("session-001", sourceExecutionId);
String newExecutionId = stream.executionId();

stream.onPartialThinking(thinking -> System.out.print("[think] " + thinking.text()))
        .onPartialResponse(token -> System.out.print(token))
        .onToolExecuted(execution -> System.out.println("[tool] " + execution.request().name()))
        .onCompleteResponse(response -> System.out.println("\n[done] " + response.aiMessage().text()))
        .onError(error -> System.err.println("[error] " + error.getMessage()))
        .start();
```

流式恢复与普通 `runStream()` 的回调完全一致。历史已结束时，`resumeStream` 返回 `CompletedTokenStream`，`start()` 会直接下发既有回答。

## 6. 恢复后的取消使用新 executionId

恢复会产生新的 `executionId`，后续取消、查询都以新 ID 为准：

```java
String newExecutionId = stream.executionId();

// 取消恢复后的这一轮
agent.cancel("session-001", newExecutionId);

// 查询恢复后的状态
agent.getExecutionState("session-001", newExecutionId);
```

业务层应维护「业务任务 ID -> 当前 executionId」的映射，恢复后把映射从来源 ID 更新为新 ID。旧 `executionId` 的迟到回调会被忽略，不会污染新执行。

## 7. 恢复内部做了什么

```text
resume(memoryId, executionId)
      │
      ▼
prepareResume：校验 → 读 ChatMemory → 判断是否已结束 → 修复消息链 → 生成新 executionId
      │
      ├── 历史已结束 → 直接返回既有结果，不调用模型
      │
      └── 需要继续   → 注册新执行 → 不追加 UserMessage → 继续 ReAct 循环
```

具体行为：

- 自动补齐「AI 带了 tool call 但没有结果」的悬空消息链；
- 复用以存在的工具结果，已完成的工具不会重复执行；
- 取消补偿写入的 synthetic AI 消息在恢复时会被剔除，避免误判为已结束；
- 同一 `memoryId` 已有活跃执行时拒绝恢复，抛出明确异常。

## 8. 常见约束

| 现象 / 场景 | 说明与处理 |
| --- | --- |
| 恢复后多出一条用户消息 | 说明误用了 `submit`/`run`；恢复必须走 `resume` |
| 恢复抛「memoryId 已存在活跃执行」 | 上一轮尚未收口，等其结束或先取消 |
| 恢复抛「无 ChatMemory」 | 来源执行的历史未持久化，无法恢复 |
| `SUCCESS` 恢复后内容不变 | 预期行为，历史已完整，直接返回 |
| 应用重启后 `getExecutionState` 为空 | 默认状态存储是进程内实现，重建正确的恢复依据是业务库中的 ChatMemory + executionId |
| 工具执行到一半进程崩溃 | 当前不支持自动恢复，需要检查点与工具幂等账本 |

## 9. 相关设计文档

- 《AgentForge Agent 运行恢复设计方案》
- 《AgentForge Agent State 持久化设计实现》

下一步：了解执行过程中的 [消息事件](./message-event)。

---
title: 权限设计 & HITL：权限体系、中断事件与恢复
sidebar_label: 权限设计 & HITL
description: 从设计视角理解 AgentForge 的权限判定、审核中断、AskUser 与恢复机制，并完成 SDK 集成与自定义扩展
sidebar_position: 7
displayed_sidebar: sdkSidebar
---

# 权限设计 & HITL：权限体系、中断事件与恢复

AgentForge 把「能不能执行」与「执行什么」分开：权限系统只输出决策，工具执行器只执行已经获准的调用。当决策结果为 `ASK`，或者模型调用 `ask_user` 需要补充信息时，Agent 不阻塞线程，而是把本轮工具调用冻结成批次、正常收口为 `SUSPENDED`，由业务层稍后提交结构化响应并恢复。

这套机制同时覆盖权限审核与 Human-in-the-Loop（HITL）。两者共享同一个中断内核，区别只在请求内容、响应格式和恢复动作。

> 当前实现边界：一个中断批次只允许恢复一次，通过 `batchId` 与 `PENDING -> RESUMING -> COMPLETED` 状态机防止重复消费。当前恢复命令是 `batchId + responses`，尚未提供幂等键与恢复结果重放；本文会在相关位置标注演进方向，避免把规划能力当成已实现接口。

## 1. 设计定位

权限与中断不是一个模块里的两个分支，而是三个协作层：

| 层 | 职责 | 当前实现 |
| --- | --- | --- |
| 权限判定层 | 对每个 Tool Call 输出 `ALLOW / DENY / ASK` | `PermissionEngine`、`PermissionRule`、`PermissionMode` |
| 中断运行时 | 冻结对象、保存快照、暂停、校验响应、恢复 | `PermissionInterruptModule`、`AgentInterruptSnapshot`、`AgentInterruptStore` |
| 交互适配层 | 把权限审核和 AskUser 的 payload 转成工具结果 | `InterruptType`、`AskUserSupport`、调用方 UI |

四条设计原则贯穿实现：

1. **决策与执行分离**：权限系统不执行工具，`AgentToolExecutor` 只接收已放行的调用。
2. **中断对象不可漂移**：恢复消费的是暂停时冻结的原始 Tool Call，UI 不能回传或改写参数。
3. **暂停不占线程**：产生中断时本次 invocation 正常结束为 `SUSPENDED`，不通过阻塞 Future 等待用户。
4. **默认 fail-closed**：响应缺失、类型不匹配、批次不存在或状态不允许时拒绝恢复，而不是猜测执行。

只有 `ASK` 与 `ASK_USER` 会进入挂起态。`DENY` 不是中断：它在当前批次中为对应 Tool Call 写入失败结果，其余调用继续执行，然后进入下一轮 think。

## 2. 核心权限体系

### 2.1 三态决策

```java
public enum PermissionDecision {
    ALLOW,
    DENY,
    ASK
}
```

| 决策 | 含义 | 后续动作 |
| --- | --- | --- |
| `ALLOW` | 当前调用可以直接执行 | 进入 ToolExecutor |
| `DENY` | 当前调用被策略拒绝 | 不执行，写失败 Tool Result，继续后续调用 |
| `ASK` | 需要用户确认 | 生成 `PERMISSION_APPROVAL` 中断，冻结本轮批次 |

`ASK` 是权限系统与中断内核之间唯一的连接点。权限系统不负责暂停，中断内核也不解释权限规则。

### 2.2 规则与规则处理器

权限规则采用「规则类型 + 类型处理器」的扩展模型。规则对象只描述类型和命中后的决策，匹配逻辑交给处理器：

```java
public interface PermissionRule {
    String getId();
    String getType();
    PermissionDecision getDecision();
}

public interface PermissionRuleHandler {
    String type();
    boolean matches(PermissionRule rule, PermissionContext context);
}
```

内置类型只有 `regex`，对应 `RegexPermissionRule` 与 `RegexPermissionRuleHandler`：

- `toolRegex` 对工具名做全匹配；
- `argumentRegex` 对原始 JSON 参数做包含匹配；
- 规则按注册顺序执行，第一个命中规则生效；
- 构造模块时校验每条规则都有对应处理器，未知类型直接失败，不会静默放行；
- 同一类型只允许一个处理器，内置 `regex` 不能被覆盖。

常用规则通过 Fluent API 注册：

```java
PermissionInterruptModule module =
        PermissionInterruptModule.safeDefaults()
                .allow("(read_file|list_files|search_files)", ".*")
                .ask("(write_file|edit_file|delete_file|shell)", ".*")
                .deny("shell", "(?i).*(rm\\s+-rf|git\\s+push\\s+--force).*")
                .build();
```

> 正则规则适合快速起步，不适合做路径安全判断。结构化参数、工作区越界、软链接逃逸等风险应由工具自身校验，或通过自定义规则处理器解析参数后判断。

### 2.3 权限模式：解释规则结果，而不是替代规则

`PermissionMode` 是场景级策略，不参与规则匹配。它的作用是在规则产出候选决策后，决定该场景如何落地：

```java
public enum PermissionMode {
    DEFAULT,
    BYPASS,
    DONT_ASK
}
```

| 模式 | 规则命中 `DENY` | 规则命中 `ASK` | 规则命中 `ALLOW` | 无规则命中 |
| --- | --- | --- | --- | --- |
| `DEFAULT` | `DENY` | `ASK`（进入中断） | `ALLOW` | `ALLOW` |
| `BYPASS` | `DENY` | `ALLOW` | `ALLOW` | `ALLOW` |
| `DONT_ASK` | `DENY` | `DENY` | `ALLOW` | `DENY` |

三种模式可以概括为：

- **`DEFAULT`：规则为准。** 命中规则按其决策执行；未命中直接放行。它不会自动把未知操作变成审核，敏感工具必须显式配置 `ask`。
- **`BYPASS`：只拦红线。** 显式 `DENY` 仍然有效，其余统一放行，不产生权限中断。
- **`DONT_ASK`：只放白名单。** 只有显式 `ALLOW` 放行，其余拒绝，且不产生任何中断，适用于无人值守任务。

`BYPASS` 不是关闭权限系统，`DONT_ASK` 也不是全部拒绝。两者都保留显式 `DENY`，区别只是如何处理 `ASK` 和未命中规则。

更细的场景策略通过 `PermissionModePolicy` 扩展，而不是继续增加枚举：

```java
public interface PermissionModePolicy {

    PermissionDecision decide(PermissionContext context);

    default PermissionDecision decide(
            PermissionContext context, PermissionDecision candidate) {
        return candidate == null ? decide(context) : candidate;
    }
}
```

### 2.4 判定顺序

`PermissionEngine` 固定为两阶段：

```text
Tool Call
   │
   ▼
PermissionRule + PermissionRuleHandler
   │  第一个命中规则 -> candidate
   ▼
PermissionModePolicy / PermissionMode
   │  结合 candidate 做最终决策
   ▼
ALLOW / DENY / ASK
```

源码层面可以概括为：

```java
PermissionDecision candidate = ruleDecision(context);
return effectiveModePolicy.decide(context, candidate);
```

这条顺序保证了三件事：

1. 规则负责细粒度覆盖，Mode 只负责场景解释；
2. 自定义 `PermissionModePolicy` 注入后完全接管最终决策，不存在两套判定分支；
3. 同一 Tool Call 在暂停前后不会重新评估成另一组参数，恢复只消费冻结对象。

### 2.5 统一装配入口

`ReActAgent.Builder` 只接收一个 `PermissionInterruptModule`，权限模式、规则、处理器、中断 Store 和 AskUser 开关都在模块内装配：

```java
PermissionInterruptModule module =
        PermissionInterruptModule.safeDefaults()
                .enableAskUser()
                .allow("(read_file|list_files|search_files)", ".*")
                .ask("(write_file|edit_file|delete_file|shell)", ".*")
                .deny("shell", "(?i).*(rm\\s+-rf|git\\s+push\\s+--force).*")
                .build();

ReActAgent agent = ReActAgent.builder()
        .chatModel(chatModel)
        .streamingChatModel(streamingChatModel)
        .chatMemoryProvider(chatMemoryProvider)
        .toolService(toolService)
        .permissionInterruptModule(module)
        .build();
```

模块对外只暴露三个运行入口：

| 方法 | 作用 |
| --- | --- |
| `preflight(step, requests, context)` | 按原始顺序评估整批 Tool Call，返回放行、拒绝或挂起结果 |
| `resolve(command)` | 校验整批响应并原子占有快照 |
| `complete(batch)` | 冻结批次应用完成后标记批次结束 |

`safeDefaults()` 的语义需要明确：

- 使用 `PermissionMode.DEFAULT`；
- 不预置任何权限规则，因此未命中的调用直接放行；
- 注册内置 `regex` 规则处理器；
- 使用默认 `InMemoryAgentInterruptStore`；
- AskUser 默认关闭，调用 `enableAskUser()` 后才启用，并由 `ReActAgent` 自动注册 `AskUserTool`。

`APPROVE_ALWAYS` 在当前实现中是在模块实例内保存「工具名 + 原始参数」指纹，后续完全相同的调用直接放行。它不持久化、不跨节点、不跨 Workspace 实例。多 Workspace 场景应各自持有独立的 Module。

## 3. 中断事件与恢复

### 3.1 两类中断

```java
public enum InterruptType {
    PERMISSION_APPROVAL,
    ASK_USER
}
```

| 类型 | 请求内容 | 用户响应 | 恢复动作 |
| --- | --- | --- | --- |
| `PERMISSION_APPROVAL` | 冻结的原始 Tool Call、工具名、参数 | `APPROVE_ONCE / APPROVE_ALWAYS / REJECT` | 批准执行原调用；拒绝写入拒绝结果 |
| `ASK_USER` | `ask_user` 原 Tool Call、结构化问题 | 选择项或文本答案 | 校验答案并包装为 `ask_user` 的工具结果，不执行其他业务工具 |

两类中断共享同一套批次、快照、状态机和恢复入口。权限审核不能被伪装成 `ask_user` 绕过权限引擎，`ask_user` 也不能用来执行工具。

### 3.2 批次模型

一次 AI Call 可能返回多个 Tool Call，其中可能同时存在直接放行、直接拒绝、权限审核和 AskUser。因此暂停与恢复的最小事务边界不是单个中断项，而是整个 **Interrupt Batch**。

```text
InterruptBatchRequest
  batchId = batch_001
  sourceExecutionId = exec_old
  requests[0] interruptId = int_001  type = PERMISSION_APPROVAL
  requests[1] interruptId = int_002  type = ASK_USER
  requests[2] interruptId = int_003  type = PERMISSION_APPROVAL
```

关键 ID 的职责保持单一：

| ID | 标识对象 | 用途 |
| --- | --- | --- |
| `batchId` | 同一次 AI Call 产生的一组中断 | 定位快照、提交整批响应 |
| `interruptId` | 批次内的单个中断项 | 关联请求与响应 |
| `sourceExecutionId` | 产生中断的原始执行 | 追溯来源，恢复时作为 previousExecutionId |
| `toolCallId` | 模型生成的原始 Tool Call | 绑定审批决定或 AskUser 答案 |

单个中断只是 `requests.size() == 1` 的批次特例，不另建接口。

### 3.3 快照与状态机

`AgentInterruptSnapshot` 冻结本轮完整信息：

- `batchId`、`memoryId`、`sourceExecutionId`、`step`；
- 原始 Tool Call 顺序 `toolRequests`；
- 待响应中断项 `interrupts`；
- 直接拒绝项 `denials`；
- 批次状态 `status`。

状态流转固定为：

```text
创建：PENDING
恢复抢占：PENDING -> RESUMING
结果写回：RESUMING -> COMPLETED
```

`resolve` 在应用任何工具副作用之前调用 `store.claim(batchId)`。内存实现通过同步块完成状态 CAS：只有 `PENDING` 能推进为 `RESUMING`；已经是 `COMPLETED` 或 `RESUMING` 时直接抛出异常。

这保证两个窗口同时提交时，只有一个请求能消费批次，另一个不会再次执行工具。

### 3.4 挂起时调用方看到什么

**非流式**：

```java
ChatResult result = agent.run(request);

if (result.getChatResultState() == ChatResultState.SUSPENDED) {
    AgentSuspendInfo suspendInfo = result.getSuspendInfo();
    String batchId = suspendInfo.getBatchId();
    List<InterruptRequest> interrupts = suspendInfo.getInterrupts();
    // 渲染审批卡片或 AskUser 表单
}
```

挂起时：

- `getChatResultState()` 为 `SUSPENDED`；
- `getRunState()` 为 `WAITING_INTERRUPT`；
- `getSuspendInfo()` 是恢复的唯一入口凭证；
- 它不是失败，也不是业务完成，线程与资源已经释放。

**流式**：

```java
agent.runStream(request)
        .onPartialResponse(ui::appendText)
        .onSuspended(ui::showInterrupt)
        .onCompleteResponse(ui::showFinalAnswer)
        .onError(ui::showError)
        .start();
```

`onSuspended` 是本次流的最后一个事件。它触发后不会再有 `onCompleteResponse`、`onError` 或 `onToolExecuted`。恢复必须创建新的流，不能重启原流。

**中间件观测**：

如果不希望在业务调用处散落中断判断，可以注册 `IAgentMiddleware`：

```java
IAgentMiddleware bridge = new IAgentMiddleware() {
    @Override
    public void onInterruptRequested(
            int currentStep, AgentSuspendInfo suspendInfo, AgentChatContext chatContext) {
        eventBus.publish(chatContext.getMemoryId(), suspendInfo);
    }
};
```

`onInterruptRequested` 只用于观察和推送。真正的恢复仍然必须调用 `resumeInterrupt` 或 `resumeInterruptStream`，不能在中间件里直接执行冻结工具。

### 3.5 事件协议设计

当前版本对外的中断事件观测入口是：

- 非流式：`ChatResultState.SUSPENDED` + `AgentSuspendInfo`；
- 流式：`TokenStream.onSuspended` / `suspendInfo()`；
- 中间件：`onInterruptRequested`、`onBeforeInterruptResume`、`onAfterInterruptResume`、`onInterruptResumeError`。

统一事件信封与 `agent.interrupt.requested / responded / expired / cancelled` 事件名是协议演进方向，用于未来对接 SSE、WebSocket、消息队列和多节点调度。演进后的信封会包含：

```text
eventId          事件自身幂等 ID
eventType        事件类型
schemaVersion    协议版本
interruptBatchId 所属批次
memoryId         会话标识
executionId      来源执行
sequence         同一 run 内单调递增
occurredAt       发生时间
payload          类型专属内容
```

设计上仍坚持一点：通用生命周期事件通过 payload 内的 `type` 区分 `PERMISSION_APPROVAL` 与 `ASK_USER`，权限模块可以派生审计事件，但恢复主流程不能依赖另一套平行状态机。

### 3.6 恢复：先整批校验，再应用

恢复统一使用一个命令：

```java
public final class InterruptBatchResumeCommand {
    private final String batchId;
    private final List<InterruptResponse> responses;
}
```

`resolve` 会依次校验：

1. `batchId` 对应的快照存在；
2. 响应没有重复的 `interruptId`；
3. 快照中的每个 `interruptId` 都有且只有一个响应；
4. 响应数量与中断项数量一致，不能提交批次之外的多余项；
5. 权限项必须提供 `ApprovalAction`；
6. AskUser 项必须提供结构化 `AskUserResponse`，并通过必填、选项、数量和长度校验；
7. 全部校验通过后，才执行 `PENDING -> RESUMING` 状态抢占。

只有校验和状态抢占都成功，才按原始 Tool Call 顺序应用结果：

```text
for each frozen toolRequest in original order:
    直接拒绝项 -> 写入拒绝 Tool Result
    ASK_USER  -> 校验答案 -> 写入 ask_user Tool Result
    REJECT    -> 写入用户拒绝 Tool Result
    APPROVE   -> 执行冻结的原始 Tool Call -> 写入真实结果
    ALLOW     -> 执行冻结的原始 Tool Call -> 写入真实结果

全部结果写回 ChatMemory
batch -> COMPLETED
生成新的 executionId，继续下一轮 think
```

恢复不会把「用户已批准」或 AskUser 答案追加成一条普通 UserMessage。它必须写回对应 `toolCallId` 的 Tool Result，保持 Function Call 消息链闭合。

恢复后的执行使用新的 `executionId`，原执行只作为 `sourceExecutionId` 保留，旧执行不会被复活。

### 3.7 流式恢复的同步语义

```java
TokenStream stream = agent.resumeInterruptStream(command);
stream.onPartialResponse(ui::appendText)
      .onSuspended(ui::showInterrupt)
      .onCompleteResponse(ui::showFinalAnswer)
      .onError(ui::showError)
      .start();
```

需要注意：`resumeInterruptStream` 在方法调用阶段就同步应用冻结批次并执行已批准的 Tool Call，然后返回后续 ReAct 的流式句柄。冻结批次的工具执行不会经过该 `TokenStream` 的 `onToolExecuted` 回调。

### 3.8 并发、重复点击与幂等边界

当前批次状态机已经能阻止并发恢复，但调用方仍应做 UI 防重：

| 场景 | 当前行为 | 调用方处理 |
| --- | --- | --- |
| 缺少、重复、未知 `interruptId` | 抛 `IllegalArgumentException`，批次仍为 `PENDING` | 修正表单后重新提交 |
| AskUser 答案不合法 | 抛 `IllegalArgumentException`，批次仍为 `PENDING` | 标注字段错误，重新提交 |
| 不同页面同时恢复 | 后到请求看到 `RESUMING` / `COMPLETED`，抛 `IllegalStateException` | 刷新状态，不重复提交 |
| 相同批次完成后重试 | 抛 `IllegalStateException` | 视为已处理，查询结果而不是再次执行 |
| 恢复应用阶段工具异常 | 批次停留在 `RESUMING` | 记录故障并人工介入，不要用新请求盲目重试 |

首期没有 `idempotencyKey` 与恢复结果重放。调用方在恢复超时且无法确认结果时，应先查询执行或批次状态，而不是直接重试。

### 3.9 AskUser 的协议细节

`AskUserTool` 是模型可见的结构化交互工具，工具名固定为 `ask_user`。它只负责向模型暴露参数 Schema，不负责阻塞等待：

```text
LLM 生成 ask_user Tool Call
      │
      ▼
PermissionInterruptModule.preflight 识别 tool_name = ask_user
      │
      ▼
AskUserSupport.parse 校验问题结构
      │
      ▼
生成 ASK_USER 中断，冻结批次
      │
      ▼
SUSPENDED
```

问题结构约束：

- 一次最多 10 题；
- `id` 在单次调用内唯一且稳定；
- `title` 必填，最长 200 字符；
- 选择题必须提供 2—20 个选项，option `id` 唯一；
- `required` 缺省为必填；
- 文本最长 4000 字符；
- 单选提交预设项或自定义内容，二选一；
- 多选可以同时提交多个选项与补充内容，数量受 `minSelections / maxSelections` 约束。

恢复时使用结构化答案：

```java
AskUserResponse answer =
        AskUserResponse.of(
                AskUserAnswer.single("env", "prod"),
                AskUserAnswer.multiple("modules", Arrays.asList("api", "web")),
                AskUserAnswer.text("note", "先灰度发布"));

InterruptResponse response =
        InterruptResponse.askUser(interrupt.getInterruptId(), answer);
```

Core 会重新校验答案，并按问题顺序生成稳定的工具结果：

```json
{
  "status": "answered",
  "interactionId": "int_xxx",
  "answers": [
    {
      "questionId": "env",
      "selectedOptionIds": ["prod"],
      "customValue": null
    }
  ]
}
```

模型下一轮看到的是 `ask_user` 的 Tool Result，而不是脱离 Tool Call 上下文的普通用户消息。

## 4. 快速集成

### 4.1 依赖

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-agent-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

权限、中断、AskUser 和中间件回调契约都在 `agentforge-agent-core` 中。

### 4.2 装配模块

```java
PermissionInterruptModule module =
        PermissionInterruptModule.safeDefaults()
                .enableAskUser()
                .allow("(read_file|list_files|search_files)", ".*")
                .ask("(write_file|edit_file|delete_file|shell)", ".*")
                .deny("shell", "(?i).*(rm\\s+-rf|git\\s+push\\s+--force).*")
                .build();

ReActAgent agent =
        ReActAgent.builder()
                .agentName("workspace-agent")
                .systemPrompt("缺少必要信息时使用 ask_user；不得伪造用户答案。")
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .toolService(toolService)
                .permissionInterruptModule(module)
                .build();
```

规则顺序很重要：具体 `deny` 必须放在宽泛的 `ask` / `allow` 之前，因为第一个命中规则生效。

### 4.3 非流式：运行、挂起、恢复

```java
AgentRequest request = AgentRequest.builder()
        .memoryId("workspace:1:conversation:1")
        .executionId(UUID.randomUUID().toString())
        .question(userMessage)
        .build();

ChatResult result = agent.run(request);

while (result.getChatResultState() == ChatResultState.SUSPENDED) {
    AgentSuspendInfo suspendInfo = result.getSuspendInfo();

    // UI 根据 suspendInfo.getInterrupts() 收集完整响应
    List<InterruptResponse> responses = ui.collect(suspendInfo);

    result = agent.resumeInterrupt(
            new InterruptBatchResumeCommand(suspendInfo.getBatchId(), responses));
}

ui.showFinalAnswer(result.getRes());
```

`ui.collect` 必须覆盖批次内全部中断项：

```java
List<InterruptResponse> responses = new ArrayList<InterruptResponse>();

for (InterruptRequest item : suspendInfo.getInterrupts()) {
    if (item.getType() == InterruptType.PERMISSION_APPROVAL) {
        ApprovalAction action = ui.readApproval(item.getInterruptId());
        responses.add(new InterruptResponse(item.getInterruptId(), action, null));
    } else if (item.getType() == InterruptType.ASK_USER) {
        AskUserResponse answer = ui.readAskUserAnswer(item.getInterruptId());
        responses.add(InterruptResponse.askUser(item.getInterruptId(), answer));
    }
}
```

一次恢复后仍可能在新的 ReAct step 产生下一批中断，因此恢复结果也要继续判断 `SUSPENDED`。

### 4.4 流式：用新流恢复

```java
agent.runStream(request)
        .onPartialResponse(ui::appendText)
        .onPartialThinking(ui::showThinking)
        .onSuspended(ui::showInterrupt)
        .onCompleteResponse(ui::showFinalAnswer)
        .onError(ui::showError)
        .start();
```

用户提交后：

```java
agent.resumeInterruptStream(
                new InterruptBatchResumeCommand(suspendInfo.getBatchId(), responses))
        .onPartialResponse(ui::appendText)
        .onSuspended(ui::showInterrupt)
        .onCompleteResponse(ui::showFinalAnswer)
        .onError(ui::showError)
        .start();
```

流式与非流式共用同一个 `PermissionInterruptModule`、`AgentSuspendInfo` 和恢复命令，不要为流式另建审批协议。

### 4.5 AskUser UI 映射

| 问题类型 | UI 控件 | 回复构造 |
| --- | --- | --- |
| `SINGLE_CHOICE` | 单选框/下拉框，并默认提供「自定义回答」 | 预设 `optionId` 或 `customValue`，二选一 |
| `MULTIPLE_CHOICE` | 多选框，并默认提供「补充回答」 | 多个 `optionId`，可同时带 `customValue` |
| `TEXT` | 单行或多行输入框 | `AskUserAnswer.text(questionId, text)` |

必须回传 option ID，不要回传界面展示的 label。多题场景采用单题分页，切换题目只修改前端草稿，不消费批次；所有问题完成后一次性提交。

### 4.6 生命周期

恢复需要找到创建中断时的同一个模块实例、同一个 `AgentInterruptStore`、同一个 `ChatMemoryProvider` 和同一套工具实例。应按 Workspace 或会话维度复用 `ReActAgent`，不要在每次恢复时新建一个空 Agent。

```text
WorkspaceRuntime
  ├── ToolService
  ├── PermissionInterruptModule
  ├── ReActAgent
  └── ChatMemoryProvider
```

`memoryId` 表示稳定会话，`executionId` 表示单次执行。恢复会生成新的 `executionId`，不要继续复用旧值。

## 5. 自定义场景集成

### 5.1 自定义规则类型：理解结构化参数

当正则无法表达业务风险时，新增规则类型和处理器。例如只拦截特定 Shell 命令：

```java
public final class ShellCommandRule implements PermissionRule {

    private final String id;
    private final String command;
    private final PermissionDecision decision;

    public ShellCommandRule(String id, String command, PermissionDecision decision) {
        this.id = id;
        this.command = command;
        this.decision = decision;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getType() {
        return "shell_command";
    }

    @Override
    public PermissionDecision getDecision() {
        return decision;
    }

    public boolean matches(ToolExecutionRequest request) {
        return "shell".equals(request.name()) && request.arguments().contains(command);
    }
}
```

```java
public final class ShellCommandRuleHandler implements PermissionRuleHandler {

    @Override
    public String type() {
        return "shell_command";
    }

    @Override
    public boolean matches(PermissionRule rule, PermissionContext context) {
        return rule instanceof ShellCommandRule
                && ((ShellCommandRule) rule).matches(context.getRequest());
    }
}
```

注册：

```java
PermissionInterruptModule module =
        PermissionInterruptModule.safeDefaults()
                .ruleHandler(new ShellCommandRuleHandler())
                .rule(new ShellCommandRule(
                        "rule-shell-rm", "rm -rf", PermissionDecision.DENY))
                .build();
```

构建期会校验规则类型是否已注册处理器、处理器是否重复、决策是否为 null。未注册类型不会被静默忽略。

### 5.2 自定义模式策略：场景级放行规则

内置 Mode 不够表达时，实现 `PermissionModePolicy`。例如「读取放行，写入必须审核，显式 DENY 不可绕过」：

```java
public final class ReviewWritesPolicy implements PermissionModePolicy {

    @Override
    public PermissionDecision decide(PermissionContext context) {
        return PermissionDecision.ASK;
    }

    @Override
    public PermissionDecision decide(
            PermissionContext context, PermissionDecision candidate) {
        if (candidate == PermissionDecision.DENY) {
            return PermissionDecision.DENY;
        }
        if (context.getRequest().name().startsWith("write_")) {
            return PermissionDecision.ASK;
        }
        return candidate == null ? PermissionDecision.ALLOW : candidate;
    }
}
```

```java
PermissionInterruptModule module =
        PermissionInterruptModule.builder()
                .permissionModePolicy(new ReviewWritesPolicy())
                .build();
```

自定义策略注入后完全接管最终决策，内置 `DEFAULT / BYPASS / DONT_ASK` 不再参与。因此策略本身必须显式保留 `DENY` 的优先级。

### 5.3 自定义中断 Store：跨进程与集群恢复

默认 `InMemoryAgentInterruptStore` 只适用于单进程。需要跨重启或多节点恢复时，实现 `AgentInterruptStore`：

```java
public interface AgentInterruptStore {
    void save(AgentInterruptSnapshot snapshot);
    AgentInterruptSnapshot get(String batchId);
    void claim(String batchId);
    void complete(String batchId);
}
```

数据库实现的核心是原子状态推进：

```sql
UPDATE agent_interrupt_batch
SET status = 'RESUMING'
WHERE batch_id = ?
  AND status = 'PENDING';
```

受影响行数为 0 时必须抛出冲突，不能继续应用工具。`save` 应使用 `batchId` 唯一约束，`complete` 标记为 `COMPLETED`。

```java
PermissionInterruptModule module =
        PermissionInterruptModule.builder()
                .interruptStore(new JdbcAgentInterruptStore(dataSource))
                .build();
```

替换 Store 不能改变状态机语义，尤其不能让两个恢复请求同时从 `PENDING` 进入 `RESUMING`。跨进程恢复还要求 ChatMemory 同步持久化。

### 5.4 工具层边界：权限规则不能替代路径校验

权限系统是纵深防御的一层，不是沙箱的替代品。文件工具在执行前必须自己完成路径规范化与越界检查：

```java
Path root = workspaceRoot.toRealPath();
Path target = root.resolve(userPath).normalize();

if (!target.startsWith(root)) {
    throw new SecurityException("path escapes workspace: " + userPath);
}
```

对已存在的目标还需要 `toRealPath()`，防止软链接逃逸。推荐分级：

| 操作 | 建议决策 | 说明 |
| --- | --- | --- |
| Workspace 内读取、搜索、列目录 | `ALLOW` | 工具仍要规范化路径 |
| Workspace 内写入、覆盖、删除、Shell | `ASK` | UI 展示冻结的工具名和参数 |
| Workspace 外访问、敏感路径、不可逆高危命令 | `DENY` 或工具层拒绝 | 不要把全部风险都交给人工确认 |

### 5.5 审批事件桥接

桌面端、IDE 插件或 Web 后端可以通过中间件统一推送中断事件，而不侵入业务调用：

```java
IAgentMiddleware bridge = new IAgentMiddleware() {

    @Override
    public void onInterruptRequested(
            int currentStep, AgentSuspendInfo suspendInfo, AgentChatContext chatContext) {
        eventBus.publishInterrupt(
                chatContext.getMemoryId(), currentStep, suspendInfo);
    }

    @Override
    public void onBeforeInterruptResume(
            InterruptResumeContext resumeContext, AgentChatContext chatContext) {
        auditLog.recordResume(resumeContext.getSnapshot(), resumeContext.getResponses());
    }

    @Override
    public void onInterruptResumeError(
            InterruptResumeContext resumeContext,
            Throwable error,
            AgentChatContext chatContext) {
        alertService.notifyResumeFailure(resumeContext.getSnapshot().getBatchId(), error);
    }
};
```

```java
ReActAgent agent =
        ReActAgent.builder()
                .middleware(bridge)
                .permissionInterruptModule(module)
                // 其他配置
                .build();
```

中间件只做观察、审计和推送，不能修改冻结的 Tool Call，也不能替代 `resumeInterrupt`。

## 6. 边界与最佳实践

### 6.1 模式选择

| 场景 | 推荐模式 | 原因 |
| --- | --- | --- |
| 桌面编码助手、IDE 插件 | `DEFAULT` | 有人在现场处理审批，按工具粒度配置 `ask` |
| 可信沙箱、可回滚测试环境 | `BYPASS` | 只保留显式 `DENY` 红线 |
| 夜间巡检、CI、无人值守任务 | `DONT_ASK` | 只走白名单，不允许任何等待 |

`DEFAULT` 期望「无规则自动询问」是常见反模式。未命中规则会直接放行，敏感工具必须显式配置 `ask`。`DONT_ASK` 配置大量 `ask` 规则同样危险：它们最终都会变成 `DENY`，规则意图与运行结果不一致。

### 6.2 规则配置检查

1. `deny` 是否放在更宽泛的 `ask` / `allow` 之前；
2. 敏感工具是否显式配置 `ask` 或 `deny`；
3. 正则是否只匹配工具名与原始 JSON，而不是依赖路径字符串判断；
4. 自定义规则类型是否注册了对应处理器；
5. 自定义 `PermissionModePolicy` 是否保留显式 `DENY`。

### 6.3 中断与恢复检查

1. 是否把 `SUSPENDED` 当作正常等待态，而不是失败；
2. 恢复响应是否覆盖批次内全部 `interruptId`，无缺失、重复或多余项；
3. AskUser 是否使用 option ID 与结构化 `AskUserResponse`；
4. 是否在用户提交后禁用按钮，避免重复恢复；
5. 是否用新的 `executionId` 继续消费恢复结果；
6. 流式挂起后是否创建新流，而不是重启原流；
7. 是否按 Workspace / 会话复用 Module 与 Store。

### 6.4 安全红线

- 权限引擎异常不应放行工具，异常应向上抛出并终止当前执行；
- `DENY` 与 `REJECT` 都必须写入 Tool Result，保证消息链闭合，但不执行工具；
- `APPROVE_ALWAYS` 不能用于危险路径、破坏性命令等高风险操作；当前实现只在模块实例内生效；
- UI 不能回传或改写 Tool arguments，恢复始终使用冻结的原始调用；
- 工具自身必须保留参数校验与工作区边界检查，权限审核不能替代沙箱和业务鉴权。

## 下一步

- 了解 Agent 主循环与流式入口：[Agent 智能体](./agent)
- 了解中断事件如何与中间件协作：[Middleware 设计](./middleware)
- 了解工具声明与执行器契约：[Tool 扩展接口设计](./tool)
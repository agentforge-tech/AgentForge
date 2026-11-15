# Agent 页面 HITL 中断事件与恢复设计方案

## 一、背景与目标

AgentForge Framework 已经具备统一中断能力：

- `ASK_USER`：Agent 在任务信息不足时主动询问用户；
- `PERMISSION_APPROVAL`：工具执行前等待用户批准或拒绝；
- `AgentSuspendInfo`：返回挂起批次及全部中断项；
- `resumeInterrupt / resumeInterruptStream`：提交整批响应并继续 ReAct；
- `IAgentMiddleware`：提供中断、恢复前、恢复后和恢复异常回调。

agentforge-service 目前的调试对话和发布对话仍只处理 `session / think / tool_call / resp / resp_end / error` 等事件，尚未把 Framework 的挂起与恢复语义暴露给页面。

本方案的目标是：

1. 在 Agent 能力配置中增加“询问用户”开关，默认关闭；
2. 开启后，调试模式和发布模式使用同一套 HITL 协议；
3. 设计统一 `interrupt` 事件，前端可通过实时 SSE 与历史重放恢复执行过程；
4. 支持用户提交 AskUser 结构化答案或权限审批动作，再从冻结位置继续运行；
5. 不破坏现有调试/发布配置隔离、会话持久化、事件序列和断线重连机制。

### 1.1 首期边界

首期优先完成 AskUser 闭环，同一事件协议预留权限审核类型。

- 一个 `batchId` 只允许恢复一次；
- 不实现 `idempotencyKey` 和恢复结果重放；
- 默认使用进程内 `AgentInterruptStore`，服务重启后旧批次不可恢复；
- 数据库保存页面展示、归属校验和审计信息，不伪造 Framework 恢复快照；
- 跨进程持久化恢复作为二期能力。

## 二、总体设计结论

### 2.1 一套执行协议，两种页面表达

调试模式和发布模式不分叉 Agent 执行和 HITL 恢复逻辑：

| 维度 | 调试模式 | 发布模式 |
| --- | --- | --- |
| 配置来源 | `ai_agent_config` 工作副本 | `ai_agent_config_published` 发布快照 |
| 实例缓存 | debug runtime | published runtime |
| 事件协议 | 统一 `interrupt` 事件 | 统一 `interrupt` 事件 |
| 恢复接口 | 统一 resume service | 统一 resume service |
| UI | 展示完整 ID、工具参数和恢复时间线 | 展示易理解的问题/审批卡片 |
| 历史回放 | 同一 `events` 序列 | 同一 `events` 序列 |

差异只体现在配置快照和渲染密度，不体现在事件语义。

### 2.2 中断不是错误，也不是最终完成

中断后当前 Agent invocation 结束，但当前 AI 消息仍是“等待人工输入”状态：

```text
RUNNING
  -> WAITING_HITL
  -> RESUMING
  -> RUNNING
  -> COMPLETED
  -> FAILED
```

所以：

- 不发送 `error`；
- 不发送代表最终答案的 `resp_end`；
- 要发送 `interrupt/requested`；
- 要把会话任务与 AI 消息更新为 `WAITING_HITL`；
- 恢复后继续向同一条 AI 消息追加事件，不新建一条伪用户消息。

### 2.3 优先对接可重连的二段式流

HITL 可能等待几分钟甚至更久，不应让原 SSE 连接一直存活。推荐以现有二段式接口为主入口：

```text
POST /api/chat/tasks/submit
GET  /api/chat/tasks/stream?messageId=...&afterSeq=...
```

中断时事件已写入 `MessageEventStore`，SSE 可正常断开。用户提交后由后台继续执行，前端用原 `messageId + afterSeq` 恢复订阅。

`POST /api/chat/stream` 可保留兼容，但挂起后应发出 `interrupt/requested` 并关闭连接；恢复仍走独立接口，不尝试复用原 HTTP 连接。

#### 2.3.1 暂停点与续流边界

`WAITING_HITL` 不是终态，但必须是**当前订阅的暂停点**。`ReconnectableChatStreamService` 在事件排空后要主动 `complete()` 关闭 SSE，否则等待期间会持续占用轮询线程，且前端无法区分“仍在生成”和“等待用户”。

续流必须满足：

1. 前端收到 `interrupt_requested` 并观察到本次 SSE 结束，进入 `waiting`，**不得自动重连**；
2. 用户提交成功后才重新订阅，`afterSeq` 取该 `interrupt_requested` 的 `eventSeq`；
3. 服务端只推送 `eventSeq > afterSeq` 的事件，确保不重放首次执行、也不漏掉恢复后的 `interrupt_resuming`；
4. 若续流连接先于恢复后台任务建立，可能只看到 `WAITING_HITL` 而立即收口；前端应做短间隔重试，直到收到 `interrupt_resuming` 或后续事件；
5. 历史回放时旧批次的 `interrupt_requested` 不应截断续流，只有“流在该事件后结束且无后续事件”才判定为暂停；
6. 恢复后再次中断会生成新 `batchId`，以新批次卡片整体替换旧批次，续流边界同样取新 `interrupt_requested.eventSeq`。

## 三、Agent 能力配置

### 3.1 新增默认关闭的 AskUser 开关

在 `AgentCapabilityConfig` 中增加：

```java
public class AgentCapabilityConfig {

    private Boolean planEnabled = true;

    /** 是否允许 Agent 主动询问用户，默认关闭。 */
    private Boolean askUserEnabled = false;
}
```

`StandardAgentConfig` 提供 fail-closed 读取方法：

```java
public boolean isAskUserEnabled() {
    return capabilityConfig != null
            && Boolean.TRUE.equals(capabilityConfig.getAskUserEnabled());
}
```

与当前任务计划的默认开启语义不同，AskUser 必须显式开启：

| JSON | 最终值 |
| --- | --- |
| 无 `capabilityConfig` | `false` |
| 无 `askUserEnabled` | `false` |
| `askUserEnabled: null` | `false` |
| `askUserEnabled: false` | `false` |
| `askUserEnabled: true` | `true` |

这样旧 Agent 不会因升级而突然具备主动询问能力。

### 3.2 配置页交互

在“任务计划”下方增加同级能力开关：

```text
任务计划    [开关]
开启后 Agent 可在多步骤任务中调用 todoplan 维护任务列表

询问用户    [开关，默认关闭]
开启后 Agent 可在信息不足时暂停执行，等待用户选择或输入后继续
```

前端 `form` 增加 `askUserEnabled: false`，保存时写入：

```json
{
  "capabilityConfig": {
    "planEnabled": true,
    "askUserEnabled": true
  }
}
```

版本对比中增加“询问用户”一项；切换历史版本时仅用于预览，只有保存才修改工作副本。

### 3.3 调试与发布快照

- 编辑页调试始终读取 `ai_agent_config` 中的当前 `askUserEnabled`；
- 发布时将完整 `capabilityConfig` 写入 `ai_agent_config_published`；
- 发布页只读发布快照，编辑页后续关闭 AskUser 不会影响已发布 Agent；
- 保存工作副本后清理 debug runtime；
- 重新发布后清理 published runtime，使新开关生效。

### 3.4 ReActAgentFactory 装配

仅开启 AskUser 时，不应让普通 HTTP/MCP 工具进入权限审核。`PermissionMode.DEFAULT` 是规则驱动模式：命中规则按其决策执行，未命中规则时直接放行，因此首期装配保持：

```java
PermissionInterruptModule interruptModule =
        PermissionInterruptModule.builder()
                .activePermissionMode(PermissionMode.DEFAULT)
                .enableAskUser()
                .build();

builder.permissionInterruptModule(interruptModule);
```

`ask_user` 是 Framework 特殊交互工具，启用后直接生成 `ASK_USER` 中断；`DEFAULT` 只影响普通工具的规则判定。

如后续同时开启 Workspace 权限审核，继续使用 `DEFAULT`，并显式配置 `ALLOW / ASK / DENY` 规则；需要审核的工具必须配置 `ASK` 规则，不要再依赖“无规则默认询问”。

当前 Service 还配置了一条硬拦截示例：工具名包含 `openreach`（忽略大小写）时直接 `DENY`，不进入审批中断：

```java
.deny("(?i).*openreach.*", ".*")
```

`RegexPermissionRule` 对工具名做全匹配、对参数做包含匹配，因此该规则覆盖所有 `openreach__*` 工具。

## 四、统一中断事件协议

### 4.1 事件命名

在 `StreamEventEnums` 中新增一个 Event，通过 Type 表达状态转换：

```java
INTERRUPT(
        "interrupt",
        "HITL 中断事件",
        TypeEnum.INTERRUPT_REQUESTED,
        TypeEnum.INTERRUPT_RESUMING,
        TypeEnum.INTERRUPT_RESUMED,
        TypeEnum.INTERRUPT_RESUME_FAILED,
        TypeEnum.INTERRUPT_CANCELLED)
```

```java
INTERRUPT_REQUESTED("interrupt_requested", "等待用户处理"),
INTERRUPT_RESUMING("interrupt_resuming", "正在恢复执行"),
INTERRUPT_RESUMED("interrupt_resumed", "已恢复执行"),
INTERRUPT_RESUME_FAILED("interrupt_resume_failed", "恢复失败"),
INTERRUPT_CANCELLED("interrupt_cancelled", "中断已取消")
```

不拆成四个 Event 的原因是：它们是同一张中断卡片的状态变化，前端和历史重放可按 `batchId` 合并。

### 4.2 requested 事件

```json
{
  "event": "interrupt",
  "type": "interrupt_requested",
  "title": "等待用户处理",
  "isFinish": false,
  "eventSeq": 18,
  "content": {
    "schemaVersion": 1,
    "messageId": 9012,
    "executionId": "exec_01",
    "batchId": "batch_01",
    "currentStep": 3,
    "status": "waiting",
    "items": [
      {
        "interruptId": "int_01",
        "interruptType": "ask_user",
        "toolCallId": "call_07",
        "toolName": "ask_user",
        "askUser": {
          "reason": "发布前需要确认",
          "submitLabel": "继续发布",
          "questions": [
            {
              "id": "env",
              "title": "请选择部署环境",
              "type": "single_choice",
              "required": true,
              "options": [
                {"id": "test", "label": "测试"},
                {"id": "prod", "label": "生产"}
              ]
            }
          ]
        }
      }
    ]
  }
}
```

权限审核项使用同一 item 形状：

```json
{
  "interruptId": "int_02",
  "interruptType": "permission_approval",
  "toolCallId": "call_08",
  "toolName": "write_file",
  "permission": {
    "description": "写入 Workspace 文件",
    "argumentsPreview": "{\"path\":\"src/App.vue\"}",
    "allowedActions": ["approve_once", "reject"]
  }
}
```

事件只输出经过脱敏的参数预览，恢复时也不允许页面回传或改写 Tool arguments。

### 4.3 resuming 事件

服务端完成归属、状态和响应结构校验后，在调用 Framework 恢复入口前发出：

```json
{
  "event": "interrupt",
  "type": "interrupt_resuming",
  "title": "正在恢复执行",
  "isFinish": false,
  "eventSeq": 19,
  "content": {
    "messageId": 9012,
    "batchId": "batch_01",
    "status": "resuming",
    "submittedAt": "2026-11-15T10:00:00+08:00"
  }
}
```

### 4.4 resumed 事件

Framework `onAfterInterruptResume` 表示冻结批次已应用并写回 ChatMemory。此时发出：

```json
{
  "event": "interrupt",
  "type": "interrupt_resumed",
  "title": "已恢复执行",
  "isFinish": true,
  "eventSeq": 20,
  "content": {
    "messageId": 9012,
    "batchId": "batch_01",
    "status": "resolved",
    "executionId": "exec_01",
    "resolvedAt": "2026-11-15T10:00:01+08:00"
  }
}
```

`isFinish=true` 表示该中断卡片状态已收口，不表示整条 AI 消息已完成。整条消息仍以 `resp_end` 收口。当前 Framework 恢复实现复用中断来源 `executionId`；若后续改为新执行 ID，再通过协议版本增加 `resumedExecutionId`。

### 4.5 resume_failed 事件

```json
{
  "event": "interrupt",
  "type": "interrupt_resume_failed",
  "title": "恢复失败",
  "isFinish": false,
  "eventSeq": 20,
  "content": {
    "messageId": 9012,
    "batchId": "batch_01",
    "status": "resume_failed",
    "errorCode": "INVALID_INTERRUPT_RESPONSE",
    "errorMessage": "问题 env 不支持选项 staging",
    "retryable": true
  }
}
```

只输出安全的业务错误，不输出堆栈、密钥、完整本地路径和未脱敏参数。

### 4.6 cancelled 事件

用户在等待 HITL 时点击“停止”、Agent 被删除，或业务明确取消待办时，发出：

```json
{
  "event": "interrupt",
  "type": "interrupt_cancelled",
  "title": "已停止等待",
  "isFinish": true,
  "eventSeq": 19,
  "content": {
    "messageId": 9012,
    "batchId": "batch_01",
    "status": "cancelled",
    "reason": "user_stopped"
  }
}
```

取消后批次不可恢复，AI 消息进入 `CANCELLED`，会话进入 `FINISHED`。

## 五、恢复 API 设计

### 5.1 请求模型

```java
public class AgentInterruptResumeReq {
    private Long messageId;
    private String batchId;
    private List<AgentInterruptResponseReq> responses;
}

public class AgentInterruptResponseReq {
    private String interruptId;
    private String approvalAction;
    private List<AskUserAnswerReq> answers;
}

public class AskUserAnswerReq {
    private String questionId;
    private List<String> selectedOptionIds;
    private String customValue;
}
```

AskUser 响应只传结构化 `answers`；权限审核只传 `approvalAction`。两者互斥。

### 5.2 推荐接口

```http
POST /api/chat/tasks/interrupts/resume
Content-Type: application/json
```

```json
{
  "messageId": 9012,
  "batchId": "batch_01",
  "responses": [
    {
      "interruptId": "int_01",
      "answers": [
        {
          "questionId": "env",
          "selectedOptionIds": ["prod"]
        }
      ]
    }
  ]
}
```

响应：

```json
{
  "messageId": 9012,
  "batchId": "batch_01",
  "accepted": true,
  "status": "resuming",
  "lastEventSeq": 19
}
```

前端提交成功后继续使用：

```http
GET /api/chat/tasks/stream?messageId=9012&afterSeq=18
```

这里的 `18` 必须是本次 `interrupt_requested` 的 `eventSeq`，不能取“当前最新位置”或 `afterSeq=0`：取最新位置可能漏掉恢复瞬间已写入的 `interrupt_resuming`，取 0 则会把首次执行事件重新推给页面。

恢复接口不直接长时间返回模型结果，只负责校验、抢占批次和提交后台恢复任务。

### 5.3 归属与安全校验

恢复前必须校验：

1. `messageId` 归属当前用户；
2. `batchId` 归属该 `messageId`；
3. Agent 未删除，对应 debug/published runtime 仍存在；
4. 当前批次为 `PENDING`；
5. responses 完整覆盖全部 `interruptId`；
6. 没有缺失、重复或未知中断项；
7. AskUser 的 questionId、optionId、必填、数量与文本长度合法；
8. 权限项的 action 在原事件 `allowedActions` 中；
9. 页面未回传 Tool arguments；
10. 同一会话不存在其他正在运行的执行。

### 5.4 恢复输入映射

```java
List<InterruptResponse> responses = new ArrayList<InterruptResponse>();
for (AgentInterruptResponseReq item : request.getResponses()) {
    if (isPermission(item.getInterruptId())) {
        responses.add(
                new InterruptResponse(
                        item.getInterruptId(), mapApprovalAction(item), null));
    } else {
        responses.add(
                InterruptResponse.askUser(
                        item.getInterruptId(), mapAskUserResponse(item.getAnswers())));
    }
}

InterruptBatchResumeCommand command =
        new InterruptBatchResumeCommand(request.getBatchId(), responses);
```

映射完成后根据原运行模式调用 `resumeInterruptStream`。即使原请求来自非流式接口，页面会话的后台恢复也建议统一使用流式事件通道，避免再维护一套恢复结果轮询协议。

## 六、Service 层组件设计

### 6.1 扩展 ServiceStreamingMiddleware

无需再新增 `ServiceInterruptMiddleware`。现有 `ServiceStreamingMiddleware` 已实现 `IStreamingIAgentMiddleware`，而 `IStreamingIAgentMiddleware` 本身属于 `IAgentMiddleware`，因此可以直接承接 Framework 的中断回调，并复用现有 `memoryId -> EventSink` 发布链路：

```java
public final class ServiceStreamingMiddleware implements IStreamingIAgentMiddleware {

    @Override
    public void onInterruptRequested(
            int currentStep,
            AgentSuspendInfo suspendInfo,
            AgentChatContext chatContext) {
        interruptApplicationService.suspend(currentStep, suspendInfo, chatContext);
    }

    @Override
    public void onBeforeInterruptResume(
            InterruptResumeContext resumeContext,
            AgentChatContext chatContext) {
        interruptApplicationService.resuming(resumeContext, chatContext);
    }

    @Override
    public void onAfterInterruptResume(
            InterruptResumeContext resumeContext,
            AgentChatContext chatContext) {
        interruptApplicationService.resumed(resumeContext, chatContext);
    }

    @Override
    public void onInterruptResumeError(
            InterruptResumeContext resumeContext,
            Throwable error,
            AgentChatContext chatContext) {
        interruptApplicationService.failed(resumeContext, error, chatContext);
    }
}
```

这些回调只负责将 Framework 中断转换为 Service 状态和事件，不直接执行工具，不修改审批结果。复杂的归属校验、批次持久化和状态转移仍委托给 `AgentInterruptApplicationService`；`ServiceStreamingMiddleware` 仅作为 Framework 回调的统一入口和事件发布器。

### 6.2 InterruptEventProcessor

与 `AiToolCallProcessor / AiRetryProcessor` 风格保持一致：

```java
public final class InterruptEventProcessor {

    public static StreamEventResponse requested(InterruptEventContent content) { ... }

    public static StreamEventResponse resuming(InterruptResumeEventContent content) { ... }

    public static StreamEventResponse resumed(InterruptResumeEventContent content) { ... }

    public static StreamEventResponse failed(InterruptResumeFailedContent content) { ... }
}
```

所有事件通过现有 `ServiceStreamingMiddleware.publish(memoryId, event)` 进入统一 Sink，由 Sink 负责 `eventSeq`、SSE 发送和 MySQL 存储。不允许中断模块绕过 Sink 自己生成序号。

### 6.3 AgentInterruptApplicationService

职责：

- 保存批次与 `messageId / aid / sessionId / debug / userId` 的归属；
- 保存用于页面重建的脱敏中断请求；
- 管理 `PENDING -> RESUMING -> COMPLETED/FAILED/CANCELLED/EXPIRED` Service 状态；
- 将前端 DTO 映射为 Framework `InterruptResponse`；
- 根据 debug/published 标识找到原 Runtime；
- 提交恢复任务并发布事件；
- 隔离业务归属校验与 Framework 内核恢复逻辑。

### 6.4 AgentManager 扩展

`IAgentManager` 增加类型化恢复能力，避免 Controller 强制转换 `ReActAgent`：

```java
TokenStream resumeInterruptStream(T agent, InterruptBatchResumeCommand command);
```

`StandardReActAgentManager` 负责调用：

```java
return agent.resumeInterruptStream(command);
```

不支持 HITL 的 Agent 类型应返回明确的 `HITL_NOT_SUPPORTED`，不允许在 Controller 中静默忽略。

### 6.5 SessionLifecycleMiddleware 改造

当前会话生命周期主要以 `onStop / onStopWithError` 收口。引入 HITL 后需要区分：

| 场景 | AI 消息状态 | 会话状态 | 是否最终收口 |
| --- | --- | --- | --- |
| 正常完成 | `SUCCEEDED` | `FINISHED` | 是 |
| 运行失败 | `FAILED` | `FINISHED` | 是 |
| HITL 挂起 | `WAITING_HITL` | `WAITING_HITL` | 否 |
| 正在恢复 | `RUNNING` | `RUNNING` | 否 |

挂起时需立即 flush 已收集的 events，但不将 AI 消息写成终态。恢复后继续对同一 `aiMessageId` 追加事件和最终 content。

### 6.6 Runtime 实例保留

默认 `InMemoryAgentInterruptStore` 依赖创建中断时的 `PermissionInterruptModule` 实例。因此在批次为 `PENDING/RESUMING` 时：

- 不能清理对应 Agent runtime；
- 保存 Agent 配置、重新发布或服务下线时，必须将旧批次标记为 `EXPIRED`；
- 调试批次不能用发布 runtime 恢复，反之亦然；
- 多节点部署下，首期需要粘性路由或按 runtime 所在节点转发恢复请求。

二期实现持久化 `AgentInterruptStore` 后，再支持跨重启和任意节点恢复。

### 6.7 恢复时复用 RunState 并重绑定 EventSink

首期不需要新增 `ResumeRuntimeContextRegistry`。Framework 进入 HITL 挂起时不会触发 `onStop / onStopWithError`，因此 `SessionLifecycleMiddleware` 中以 `memoryId` 为键的原 `RunState` 仍然存在。它已经保存了原 `aiMessageId`、`sessionId`、`sessionPk`、累积 content 和 events，恢复时不应再创建一份重复上下文。

`sessionPk` 仅是 `ai_agent_session` 的数据库数字主键，供 Mapper 更新会话状态；`sessionId` 才是对外的稳定会话标识。`sessionPk` 已被封装在 `RunState` 内，不需要进入恢复 DTO、中断事件或新的 Registry。

恢复顺序为：

```text
1. 根据 batchId 读取批次，校验归属、状态、runtime 与 memoryId
2. 为本次恢复构建 EventSink
3. ServiceStreamingMiddleware.bind(memoryId, eventSink)
4. agent.resumeInterruptStream(command)
5. TokenStream.start()
6. SessionLifecycleMiddleware 按 memoryId 复用原 RunState
7. 正常完成/失败时按原流程收口；再次挂起时继续保留 RunState
```

需要重绑定的是新一段 SSE/后台任务所使用的 `EventSink`，而不是会话生命周期上下文。若 Service 进程已重启，Framework 中的挂起快照和 Service 中的 `RunState` 都已丢失，单独持久化一个 Resume Registry 也无法恢复执行。因此首期应将该批次标记为 `EXPIRED`，跨重启恢复留到持久化 Framework 快照的后续阶段。

### 6.8 等待 HITL 时的停止语义

现有 `/api/chat/stop` 和 `/api/chat/tasks/stop` 需要识别 `WAITING_HITL`：

```text
WAITING_HITL + stop
  -> CAS 中断批次 PENDING -> CANCELLED
  -> 发布 interrupt_cancelled
  -> AI 消息 -> CANCELLED
  -> 会话 -> FINISHED
  -> 释放 runtime 保留、RunState 和 EventSink binding
```

此时没有正在运行的 Agent 线程，所以不能只调用 `agent.cancel(...)`；Service 必须直接取消待恢复批次。

### 6.9 Trace 采集与 aid 回退

Trace 属于 best-effort 旁路，写库失败不能影响主流程。恢复执行时 `ReActAgent.applyInterruptBatch` 会新建 `AgentChatContext`，不会自动带回首次执行的 `AID / DEBUG / SESSION_ID` 等 extensions，因此 trace 快照可能出现 `aid=null`。

落地策略：

1. `aid` 解析顺序：`AgentForgeTraceSnapshot.aid` → 上下文属性 `agentforge.trace.aid` → 按 `sessionId` 查询 `ai_agent_session.aid`；
2. `debug` 同样回退 `ai_agent_session.is_debug`；
3. 仍为空时记录 warning 并跳过该条 trace，避免 `Column 'aid' cannot be null` 次生 SQL 异常；
4. 根因修复仍建议在中断快照/恢复命令中携带标量扩展，避免恢复上下文长期丢元数据。

## 七、数据模型

### 7.1 AI 消息状态

建议在现有状态枚举末尾追加，不改动旧数值：

```java
// ai_agent_session_message.status
WAITING_HITL(8, "等待人工处理")

// ai_agent_session.status
WAITING_HITL(5, "等待人工处理")
```

`SessionStatusTransfer.isTerminal(...)` 对 `WAITING_HITL` 返回 false。任务超时扫描不应把人工等待时间当作普通运行超时，HITL 过期应由独立策略处理。

### 7.2 中断批次表

建议新增 `ai_agent_interrupt_batch`：

| 字段 | 说明 |
| --- | --- |
| `id` | 主键 |
| `batch_id` | Framework 批次 ID，唯一 |
| `message_id` | 归属 AI 消息 |
| `session_id` | 会话 ID |
| `aid` | Agent ID |
| `user_id` | 归属用户 |
| `is_debug` | 调试/发布运行时隔离 |
| `source_execution_id` | 产生中断的执行 ID |
| `resume_execution_id` | 恢复使用的执行 ID；当前与 source_execution_id 一致 |
| `status` | `PENDING/RESUMING/COMPLETED/FAILED/CANCELLED/EXPIRED` |
| `request_json` | 脱敏后的中断请求，用于 UI 重建 |
| `response_json` | 用户提交的结构化答案/审批结果 |
| `error_code/error_message` | 恢复错误 |
| `created_at/submitted_at/resolved_at` | 时间字段 |
| `version` | CAS 版本 |

约束：

- `uk_batch_id(batch_id)`；
- `idx_message_status(message_id, status)`；
- 恢复抢占使用 `status=PENDING AND version=?` 的条件更新；
- `request_json` 仅用于页面展示和业务校验，不取代 Framework 快照。

### 7.3 事件序列

中断和恢复事件继续写入现有消息事件表，使用同一 `messageId` 和单调递增 `eventSeq`：

```text
17 think/text
18 interrupt/interrupt_requested
19 interrupt/interrupt_resuming
20 interrupt/interrupt_resumed
21 think/text
22 resp/text
23 resp_end
```

实时 SSE、断线重连和 `sessionReplay.js` 使用完全相同的事件序列。

## 八、前端交互与渲染

### 8.1 统一 interrupt part

`AgentDebugPanel.vue`、`PublishedAgentChatPage.vue` 和 `sessionReplay.js` 共用一个 `interruptEventReducer.js`：

```javascript
function reduceInterruptEvent(message, event) {
  const batchId = event.content.batchId
  let part = message.parts.find(
    item => item.type === 'interrupt_card' && item.batchId === batchId
  )

  if (!part) {
    part = {
      type: 'interrupt_card',
      batchId,
      status: 'PENDING',
      interrupts: [],
      eventSeq: 0,
      error: ''
    }
    message.parts.push(part)
  }

  applyInterruptEvent(part, event)
}
```

状态对应：

| 事件 Type | part.status | UI |
| --- | --- | --- |
| `interrupt_requested` | `PENDING` | 展示可编辑表单/审批按钮，并记录 `eventSeq` |
| 本地提交中 | `submitting` 标记 | 禁用所有控件，显示提交中 |
| `interrupt_resuming` | `RESUMING` | 显示正在继续执行 |
| `interrupt_resumed` | `COMPLETED` | 固化用户选择，卡片收起 |
| `interrupt_resume_failed` 可重试 | `PENDING` | 恢复表单并展示字段错误 |
| `interrupt_resume_failed` 不可重试 | `FAILED` | 禁用卡片，提示重新发起任务 |
| `interrupt_cancelled` | `CANCELLED` | 禁用卡片，显示已停止等待 |

页面级状态与中断卡片状态需要分开维护：

| 页面状态 | 触发 | 行为 |
| --- | --- | --- |
| `running` | 提交 / 续流中 | 禁止发起新问题，显示停止按钮 |
| `waiting` | `interrupt_requested` | 保留运行锁和 `messageId`，停止输入，等待确认 |
| `running`（续流） | Resume 成功 | 以 `afterSeq` 新建订阅，旧订阅必须先取消 |
| `success/error/cancelled` | `resp_end/error` 或停止接口 | 清理 `controller` 与 `messageId`，恢复输入 |

关键约束：

- 同一时刻只允许一个 SSE 消费者；Resume 续流前要 abort 旧 controller；
- `waiting` 期间页面没有活动订阅，停止操作后无法再收到 `interrupt_cancelled`，前端要根据停止结果本地把 `PENDING/RESUMING` 卡片收口为 `CANCELLED`；
- 页面刷新时，`WAITING_HITL(8)` 回放为 `waiting`，直接用历史事件重建卡片，不自动续流；用户再次提交后才继续。

### 8.2 AskUser 控件

| 问题类型 | 控件 | 回传值 |
| --- | --- | --- |
| `single_choice` | Radio / Select + “自定义回答”输入框 | option ID 或 `customValue`，二选一 |
| `multiple_choice` | Checkbox Group + “补充回答”输入框 | option ID 列表，可同时带 `customValue` |
| `text` | Input / Textarea | `customValue` |

前端必须回传 option ID，不能回传 label。必填、最小/最大选择数和文本长度可先在前端校验，后端和 Framework 仍必须再次校验。

自定义输入是页面对所有选择题的默认增强，不需要 AskUser Tool Schema 增加开关，也不需要模型感知：

- 单选的“自定义回答”表示预设选项都不满足用户；选中预设项时清空自定义内容，输入自定义内容时取消预设单选；
- 多选的“补充回答”表示在已选内容之外继续补充；它与预设复选项不互斥；
- 前端提交前先校验单选互斥和多选数量，Service/Framework 仍以冻结问题为准再次校验；
- Framework 把预设选项稳定写入 `selectedOptionIds`，把自定义/补充文本写入 `customValue`，一起包装为原 `ask_user` Tool Result。

AskUser 与权限审批卡都悬浮在聊天输入区上方（见 8.7），不追加到消息列表底部。多步骤采用单项分页，支持上一项、下一项、进度点切换和返回修改；切换步骤只修改本地草稿，最后一次性提交整个批次。

### 8.3 调试模式

当前调试态展示密度：

- 审批卡展示工具名、类型徽标、`允许 / 拒绝` 两个动作（默认允许），以及“调用参数”面板；
- 参数面板默认收起，头部显示单行摘要，点击展开格式化 JSON（原始参数可为 JSON 字符串或对象）；
- 执行过程额外展示 `batchId / interruptId / ASK_USER|PERMISSION_APPROVAL`、问题类型、选项 ID、工具参数（截断 480 字符）；
- 不展示内部堆栈与完整事件 payload。

### 8.4 发布模式

发布态只保留判断所需信息：

- 问题、选项、自定义输入与必答提示；
- 审批项展示工具名、参数摘要（默认收起，可展开）与 `允许 / 拒绝` 两个动作；
- “等待确认 / 正在恢复 / 已恢复 / 已取消”状态；
- 不展示内部 ID、事件序号、堆栈与完整事件体。

### 8.5 多窗口和重复点击

- 点击提交后立即进入本地 `submitting`；
- 同一批次的所有按钮与表单统一禁用；
- 服务端 CAS 是最终权威；
- 其他窗口收到 `interrupt_resuming/resumed` 后同步收口；
- 已被处理的批次返回 `BATCH_ALREADY_CONSUMED`，前端刷新事件而不重复提交。

### 8.6 多中断组件化与混合分页

一张 `InterruptCard` 负责编排，不直接堆叠所有确认项。渲染拆分为：

- `AskUserQuestionStep.vue`：单个问题卡（单选/多选/填空/自定义回答）；
- `PermissionApprovalStep.vue`：单个工具审批卡（工具名/参数/审批动作）；
- `InterruptCard.vue`：收集步骤、进度点、上一项/下一项、校验与最终提交。

步骤顺序严格按 `interrupts` 原始顺序展开：`ASK_USER` 的每个问题各占一步，`PERMISSION_APPROVAL` 每项占一步。因此同一批次可以同时包含问题和审批，并统一左右切换。

交互约定：

- 进度文案：纯问题时为“问题 x / y”，含审批时为“确认 x / y”；
- 校验逐项进行，未完成的必答/审批步骤会阻止“下一项”和最终提交；
- 页面开放两个审批动作并固定一行两列：`允许 / 拒绝`；`允许` 默认对应 `APPROVE_ONCE`，`APPROVE_ALWAYS` 暂不对外开放；
- 提交仍是一次性提交整个 batch，不按步骤发送。

### 8.7 确认卡的悬浮与折叠

- 卡片绝对定位在输入框上方，左右边界与输入框对齐，覆盖执行过程底部，不占据消息区高度；
- 发布页 `.composer-interrupt` 与调试页 `.agent-debug-interrupt` 均使用悬浮定位；
- 卡片右上角提供整卡折叠按钮：默认**展开**，用户可手动收起；新批次中断到达时恢复展开；
- 折叠箭头遵循“卡片向上生长”的物理方向：收起状态指向 ↑，展开状态指向 ↓；
- `waiting` 状态下执行过程保持展开，不因等待用户而自动收拢；
- 消息流底部保留 72px 缓冲，避免悬浮卡完全覆盖“等待确认”等过程文案。

### 8.8 执行过程追溯

`InterruptTraceItem.vue` 同时服务调试与发布模式：

- 发布态：问题标题 + 选项标签/自定义内容；审批项展示工具名 + 用户动作；折叠后只显示“已答 x/y 题 · 已处理 m/n 项审批”；
- 调试态：额外展示 `batchId`、`interruptId`、`ASK_USER / PERMISSION_APPROVAL`、问题类型、选项 ID、工具参数（截断）；
- 用户提交时前端同时写入 `part.answers` 与 `part.approvalActions`；恢复后可从 `ask_user` Tool Result 还原答案；
- 历史回放缺少审批动作快照时显示“已处理（历史未记录动作）”，不伪造用户选择；
- 再次中断生成新 `batchId`，旧卡片保留为已完成，新卡片接管交互。

### 8.9 完成态与复制按钮

- 复制按钮只在消息 `status === 'success'` 且正文渲染完成后出现；
- `running / waiting / cancelled / error` 均不显示复制按钮，避免在等待确认或失败态误复制不完整内容；
- 等待确认期间输入框保持“运行锁”，停止操作仍需走 `WAITING_HITL` 取消批次语义。

## 九、端到端时序

### 9.1 AskUser 挂起与恢复

```text
User -> Debug/Published Page: 发送消息
Page -> ChatTaskService: submit
ChatTaskService -> ReActAgent: runStream
ReActAgent -> LLM: think
LLM -> ReActAgent: ask_user Tool Call
ReActAgent -> AgentInterruptStore: save snapshot
ReActAgent -> ServiceStreamingMiddleware: onInterruptRequested
ServiceStreamingMiddleware -> DB/EventSink: PENDING + interrupt_requested
EventSink -> Page: interrupt_requested
Page: 渲染 AskUser 表单

User -> Page: 提交答案
Page -> InterruptResumeController: messageId + batchId + responses
Controller -> InterruptApplicationService: validate + claim
InterruptApplicationService -> EventSink: interrupt_resuming
InterruptApplicationService -> ReActAgent: resumeInterruptStream
ReActAgent -> ChatMemory: ask_user Tool Result
ReActAgent -> ServiceStreamingMiddleware: onAfterInterruptResume
ServiceStreamingMiddleware -> EventSink: interrupt_resumed
ReActAgent -> LLM: continue think
EventSink -> Page: think / tool_call / resp / resp_end
```

### 9.2 权限审核

```text
LLM -> ReActAgent: write_file Tool Call
ReActAgent -> PermissionEngine: ASK
ReActAgent -> Service: interrupt_requested(permission_approval)
Page -> User: 展示权限卡片
User -> Page: approve_once / reject
Page -> Service: resume
Service -> ReActAgent: resumeInterruptStream

approve_once -> 执行冻结的 write_file -> 写入 Tool Result
reject       -> 不执行 write_file -> 写入拒绝 Tool Result

ReActAgent -> LLM: continue think
```

## 十、错误码与处理策略

| 错误码 | 场景 | 是否可重新提交 |
| --- | --- | --- |
| `INVALID_INTERRUPT_RESPONSE` | 缺少响应、optionId 错误、文本超长 | 是，批次仍为 PENDING |
| `INTERRUPT_BATCH_NOT_FOUND` | 批次不存在 | 否 |
| `INTERRUPT_BATCH_EXPIRED` | Runtime 被重建或服务重启 | 否 |
| `BATCH_ALREADY_CONSUMED` | 重复点击或其他窗口已处理 | 否，刷新事件 |
| `BATCH_RESUMING` | 批次正在恢复 | 否，等待事件 |
| `HITL_NOT_ENABLED` | Agent 配置未开启 | 否 |
| `HITL_NOT_SUPPORTED` | Agent 类型不支持恢复 | 否 |
| `INTERRUPT_OWNER_MISMATCH` | 用户/会话/Agent 归属不一致 | 否 |
| `INTERRUPT_RESUME_FAILED` | Framework 恢复或工具执行失败 | 默认否，人工介入 |

## 十一、分阶段实施计划

### P0：配置与协议

1. `AgentCapabilityConfig` 增加 `askUserEnabled=false`；
2. `StandardAgentConfig.isAskUserEnabled()`；
3. Agent 配置页新增开关、保存映射和版本对比；
4. `ReActAgentFactory` 按开关装配 `DEFAULT + enableAskUser()`；
5. `StreamEventEnums` 增加 interrupt Event/Type；
6. 新增 `InterruptEventProcessor` 与协议单测。

### P1：挂起、持久化与页面渲染

1. 扩展现有 `ServiceStreamingMiddleware` 对接 Framework 中断回调；
2. `ai_agent_interrupt_batch` 表与 Service Store；
3. AI 消息增加 `WAITING_HITL`；
4. 挂起时 flush event，但不发 `resp_end`；
5. `interruptEventReducer.js`；
6. 调试和发布页共用 AskUser/审批卡片；
7. `sessionReplay.js` 支持 interrupt 历史重放。

### P2：恢复闭环

1. resume DTO、Controller 和 ApplicationService；
2. `IAgentManager.resumeInterruptStream`；
3. 按 debug/published 归属找到原 runtime；
4. 复用原 `messageId` 继续写 eventSeq；
5. 完成 `resuming / resumed / resume_failed`事件；
6. 停止、重复点击和过期批次处理。

### P3：权限审核与生产增强

1. Workspace 权限配置与安全规则；
2. 权限卡片和审计日志；
3. 持久化 `AgentInterruptStore`；
4. 跨重启、跨节点恢复；
5. 待处理 HITL 列表与过期策略。

## 十二、测试与验收

### 12.1 配置测试

1. 旧配置缺失 `askUserEnabled` 时为 false；
2. null/false 不注册 AskUser；
3. true 时 debug runtime 生效；
4. 未重新发布前，published runtime 不受工作副本影响；
5. 重新发布后 published runtime 按新快照生效。

### 12.2 Framework-Service 集成测试

1. AskUser 非流式挂起与恢复；
2. AskUser 流式挂起与恢复；
3. 权限审核非流式批准/拒绝；
4. 权限审核流式批准/拒绝；
5. 挂起时发送 requested，不发 error/resp_end；
6. 恢复后事件顺序为 resuming -> resumed -> 后续 Agent 事件；
7. 恢复后再次挂起时产生新 batchId；
8. 重复恢复不会重复执行工具。
9. `WAITING_HITL` 时停止会取消批次，且之后无法恢复。

### 12.3 事件与前端测试

1. `InterruptEventProcessor` 生成的 event/type 组合合法；
2. `eventSeq` 在挂起和恢复跨次执行后仍单调递增；
3. 实时 SSE 和历史重放得到相同 interrupt part；
4. requested -> resuming -> resumed 始终更新同一 batchId 卡片；
5. AskUser 单选、多选、文本和校验错误均可正确渲染；单选自定义回答与预设项互斥，多选补充回答可与预设项并存；
6. 重复点击后只有一次恢复成功；
7. 断线重连从 `afterSeq` 继续，不重复或丢失中断事件；
8. 调试页和发布页共用事件语义，仅展示密度不同；
9. `WAITING_HITL` 时服务端关闭订阅，暂停期间不产生空轮询；
10. Resume 续流只收到边界之后的事件，且恢复竞态下客户端重试后能收到 `interrupt_resuming`；
11. 恢复后再次中断使用新 `batchId`，旧卡片保留为已完成，新卡片接管交互；
12. `waiting` 期间停止后，前端把未完成中断卡片收口为 `cancelled`。

## 十三、开放问题

1. 保存 Agent 配置时，已挂起的 debug 批次是立即过期，还是允许按旧 runtime 完成；
2. 首期多节点部署采用粘性路由还是 runtime owner 节点转发；
3. 权限审核首期是否只开放 `APPROVE_ONCE / REJECT`，暂不开放进程内 `APPROVE_ALWAYS`；
4. HITL 等待超时时间和过期后的页面文案；
5. 非流式 `/api/agent/chat` 首期是否暴露恢复结果 DTO，还是将页面 HITL 统一限定在二段式任务协议。

## 十四、推荐的首期决策

1. AskUser 开关默认 false，只在用户显式开启后注册；
2. 调试与发布共用 `interrupt` 事件和 resume API；
3. 首期页面 HITL 只走可重连二段式流，legacy SSE 只做兼容；
4. 一次挂起/恢复始终属于同一 `messageId`，`eventSeq` 连续递增；
5. 挂起是 `WAITING_HITL`，不是 completed/error；
6. 仅开启 AskUser 时使用 `PermissionMode.DEFAULT + enableAskUser()`；
7. 首期权限审核只开放 `APPROVE_ONCE / REJECT`，不向页面开放 `APPROVE_ALWAYS`；
8. 首期保留原 runtime，服务重启或 runtime 失效后批次进入 EXPIRED；
9. 二期再引入持久化 `AgentInterruptStore` 和跨节点恢复。

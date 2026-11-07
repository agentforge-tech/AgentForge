# AgentForge Agent 运行恢复设计方案

## 1. 结论与目标

初版只解决一类恢复：**Agent 运行过程中异常停止，或服务重启后，平台根据已持久化的 `AgentRunState` 和 ChatMemory，显式调用 `resume`，从已有历史继续执行。**

核心原则：

- 恢复入口与 `cancel` 对称，使用 `(memoryId, executionId)` 精确定位来源执行；
- 复用已有 ChatMemory，不重复追加用户问题；
- 恢复使用新的 `executionId` 继续运行，旧执行只作为来源记录；
- 恢复能力放在独立接口 `IResumableAgent`，不污染基础 `IAgent`；
- 平台负责持久化和恢复编排，框架不主动扫描重启任务；
- 审核中断属于权限审批场景，单独接口扩展，不混入本方案。

> 状态模型、Phase 流转和持久化 SPI 见同级文档：`AgentForge Agent State 持久化设计实现.md`。

## 2. 适用范围

| 场景 | 是否支持 | 说明 |
| --- | --- | --- |
| 模型调用失败后继续 | 支持 | 历史完整，重新请求模型 |
| 达到最大步数后继续 | 支持 | 恢复运行从第 1 步重新计数，获得新预算 |
| 正常结束后再次恢复 | 直接返回 | 不重复调用模型 |
| 用户主动取消后继续 | 仅显式调用 | 平台不得自动恢复 |
| 服务重启 / 进程异常退出 | 支持，需平台持久化 runState | 平台启动后按 `(memoryId, executionId)` 调 `resume` |
| 工具执行中崩溃且无终态/账本 | 不支持 | 需要检查点与工具幂等账本，后续实现 |
| 审核中断 | 不支持 | 权限审批场景，单独接口扩展 |

前提条件：

- ChatMemory 已持久化，否则重启后没有可恢复的消息；
- 平台已持久化来源执行的 `executionId` 和 `AgentRunState`；
- 恢复由平台显式触发，框架不做后台扫描。

## 3. 与审核中断的区别

| 维度 | RunState 恢复（本文） | 审核中断恢复（后续） |
| --- | --- | --- |
| 触发方式 | 异常/重启/预算耗尽后由平台触发 | 流程按权限规则主动挂起，等待审批 |
| 是否计划内 | 否 | 是 |
| 运行状态 | `COMPLETED/FAILED/CANCELLED` | 需要新增 `SUSPENDED` |
| 恢复依据 | ChatMemory + AgentRunState | approvalId + 审批结论 |
| 消息处理 | 检查并修复消息链 | 把审批结论写成真实工具结果 |
| 工具执行 | 终态时无在途工具，直接继续 | 审批通过后才执行工具 |
| 接口归属 | `IResumableAgent` | 单独扩展接口 |
| 典型风险 | 模型重复调用 | 审批与工具执行的一致性 |

结论：初版只做左侧。`SUSPENDED`、审批决策、工具执行闸门都不在本次实现内，通过独立接口留好扩展位。

## 4. 异常停止场景与 runState 判定

### 4.1 异常停止的三种形态

| 形态 | 框架内状态 | 平台可观测信息 | 是否可恢复 |
| --- | --- | --- | --- |
| 模型/工具异常，框架正常收口 | `FAILED` 或 `MODEL_CALL_ERROR` | 终态快照 + runState | 可以 |
| 达到步数上限 | `COMPLETED` + `MAX_STEPS` | 终态快照 + runState | 可以 |
| 进程崩溃 / 服务重启 | 内存状态丢失 | 平台任务表 + 已落 ChatMemory | 需要已持久化 runState |

前两种框架已经写好终态；第三种依赖平台在启动时识别“上次运行中的任务”。

### 4.2 runState 决策表

| `AgentRunState` | 默认行为 | 恢复动作 |
| --- | --- | --- |
| `SUCCESS` | 不恢复 | 直接返回已有结果 |
| `MODEL_CALL_ERROR` | 允许 | 消息链合法则重新请求模型 |
| `MAX_STEPS` | 允许 | 新的步数预算，继续循环 |
| `CANCEL` | 不自动恢复 | 仅允许调用方显式发起 |
| `null` | 不支持 | 交给后续检查点方案 |

### 4.3 为什么 runState 恢复是安全的

产生上述终态时，ReAct 循环已经退出，不存在正在运行的模型连接、工具 Future 或补偿任务。因此：

- 不需要工具幂等账本；
- 不需要完整执行检查点；
- 只需保证 ChatMemory 的消息链合法。

真正需要检查点和幂等的是“进程崩溃在工具执行中且没有终态记录”的场景，留给后续。

## 5. 接口设计

### 5.1 独立恢复接口

```java
public interface IResumableAgent extends IAgent {
    ChatResult resume(Object memoryId, String executionId);

    TokenStream resumeStream(Object memoryId, String executionId);
}
```

- `(memoryId, executionId)` 与 `cancel` 对称，避免同会话新执行被误恢复；
- 不接收新的 question，继续依据历史消息；
- `MAX_STEPS` 不需要额外步数参数，恢复循环从第 1 步重新计数；
- `IAgent` 保持不变，避免强制所有 Agent 实现恢复能力。

### 5.2 恢复后的执行身份

`executionId` 参数表示“要恢复的来源执行”，不是恢复后的运行 ID：

- 恢复成功后生成新的 `executionId`，通过 `ChatResult.executionId` / `TokenStream.executionId()` 返回；
- 来源 ID 记录为 `previousExecutionId`；
- 新 ID 的作用是隔离旧执行的迟到回调；
- 平台若使用稳定任务 ID，应维护“任务 ID -> 当前 executionId”的映射。

### 5.3 返回语义

- `resume` 返回新的 `ChatResult`；
- 历史已正常结束：直接返回既有回答，不调用模型；
- 来源不存在、无 ChatMemory、或同 memoryId 正在运行：抛出明确 `AgentException`。

## 6. 恢复实现细节

### 6.1 同步恢复 `Agent.resume`

```text
resume(memoryId, executionId)
      |
      v
prepareResume：校验 / 读取历史 / 判断是否已结束 / 修复消息
      |
      +-- 已结束 -> 登记一个 COMPLETED 执行 -> 返回既有回答
      |
      v
注册新 AgentRunControl（新 executionId）
      |
      v
executeLoop：不追加 UserMessage，直接进入 ReAct
      |
      v
统一收口，返回 ChatResult
```

### 6.2 `prepareResume` 的七个步骤

1. **参数校验**：`memoryId`、`executionId` 非空；
2. **并发保护**：同 memoryId 已有非终态执行时拒绝恢复；
3. **读取 ChatMemory**：空历史直接拒绝；
4. **判断是否已结束**：
   - 最后是真实 AI 回答且无工具调用 -> 已结束；
   - 取消补偿写入的 synthetic AiMessage 不算结束；
5. **修复消息链**：`MessageChainRepairer.repairDanglingToolCalls` 补齐缺失的 tool 结果；
6. **构建恢复上下文**：生成新 executionId，写入 `resume/previousExecutionId/resumeSource` extensions；
7. **刷新系统消息并剔除 synthetic AI**：恢复后的模型只看到真实历史。

### 6.3 流式恢复 `Agent.resumeStream`

- 历史已结束：返回 `CompletedTokenStream`，`start()` 时直接下发既有回答；
- 需要继续：注册新执行后返回 `ReActTokenStream`，由 `start()` 驱动后续循环；
- 其余校验、消息修复、新 executionId 规则与同步恢复完全一致。

### 6.4 消息链判定

| 最后一条消息 | 含义 | 恢复动作 |
| --- | --- | --- |
| `UserMessage` | 模型尚未返回 | 直接请求模型 |
| `AiMessage` 带工具调用但缺结果 | 消息链不完整 | 补齐 synthetic 工具结果后继续 |
| `ToolExecutionResultMessage` | 工具已完成 | 直接请求模型 |
| `AiMessage` 无工具调用且非 synthetic | 已正常结束 | 直接返回 |
| synthetic `AiMessage` | 取消通知 | 恢复时剔除，继续真实历史 |

### 6.5 关键类映射

| 实现 | 作用 |
| --- | --- |
| `IResumableAgent` | 恢复能力接口 |
| `Agent.resume/resumeStream` | 恢复入口 |
| `Agent.prepareResume` | 校验、历史判定、修复、上下文构建 |
| `BaseAgent.prepareMessagesForResume` | 恢复前的消息准备钩子 |
| `ReActAgent.prepareMessagesForResume` | 刷新系统消息并剔除 synthetic AI |
| `MessageChainRepairer` | 补齐悬空 tool call、识别 synthetic AI |
| `CompletedTokenStream` | 已结束历史的流式返回 |
| `AgentRunRegistry` | 注册新执行、隔离旧 executionId |

## 7. 异常停止后的快速恢复流程（平台 + 框架）

### 7.1 平台需要持久化

1. 任务/执行表：`sessionId/memoryId`、`executionId`、`runState`、状态、更新时间；
2. ChatMemory：按会话持久化的完整消息链；
3. 可选：`AgentExecutionState` 终态快照，用于观测和判断。

### 7.2 启动恢复时序

```text
服务启动
   |
   v
平台扫描 RUNNING / 未收口任务
   |
   v
标记为 INTERRUPTED（或保留最后一次 runState）
   |
   v
读取该执行的 memoryId + executionId
   |
   v
agent.resume(memoryId, executionId)
   |
   +-- 历史已结束 -> 直接得到结果
   |
   +-- 需要继续 -> 框架生成 newExecutionId，继续 ReAct
   |
   v
平台把任务的 currentExecutionId 更新为 newExecutionId
   |
   v
persist 新 runState / 状态快照
```

要点：

- 框架不负责发现“哪些任务需要恢复”，只提供幂等、显式的 `resume`；
- 平台必须用来源 `executionId` 调用，不能用 memoryId 猜；
- 恢复返回新 ID 后，后续取消、查询都使用新 ID；
- 同一任务重复恢复时，框架会因“已有活跃执行”拒绝。

## 8. 与工具配合的使用方式

### 8.1 恢复不会重复执行已完成的工具

ChatMemory 中已有的 `ToolExecutionResultMessage` 是恢复的输入：

- 恢复时直接把完整历史发给模型；
- 已完成的工具不会再次提交；
- `MAX_STEPS` 恢复时，最后通常是工具结果，模型基于结果继续推理。

### 8.2 缺失工具结果的防御处理

如果消息链出现“AI 带了 tool call 但没有结果”：

- `MessageChainRepairer` 补一条 synthetic、`isError=true` 的工具结果；
- 该结果只用于闭合协议，不会真的执行工具；
- 随后模型基于“工具中断”的结果继续。

### 8.3 工具副作用的幂等建议

RunState 恢复本身是安全的，但平台仍需为工具设计幂等：

| 工具类型 | 建议 |
| --- | --- |
| HTTP 工具 | 支持幂等键（如 `Idempotency-Key`），崩溃重试不产生重复副作用 |
| 本地工具 | 以 `toolRequestId` 为幂等键，先查结果账本再执行 |
| 命令/进程工具 | 保存 `Process`，取消时 `destroy`，恢复前检查是否已产生结果 |
| MCP 工具 | 支持 request cancellation；恢复时优先复用已落结果 |

### 8.4 工具执行中崩溃（后续能力）

当前初版不支持“工具执行到一半进程崩溃”的自动恢复，因为：

- 没有终态 runState；
- 无法判断工具是否已经产生副作用；
- 重新执行可能重复下单/转账。

后续需要：

1. `AgentExecutionCheckpoint`：记录 step、pendingToolRequestIds；
2. 工具结果账本：`toolRequestId -> result`；
3. `INTERRUPTED` 状态；
4. 恢复时先查账本，命中则复用，未命中才重跑；
5. 对不可幂等工具禁止自动恢复。

### 8.5 工具与取消/恢复的关系

- 取消：主动关闭连接、取消 Future、interrupt 线程，工具应遵守中断协议；
- 恢复：不重新执行已完成工具，只让模型基于历史继续；
- 审核中断（未来）：审批通过后把 decision 写成真实工具结果，属于另一套接口。

## 9. 竞态与幂等

1. **来源校验**：`executionId` 对应来源执行，且当前没有活跃执行；
2. **重复恢复**：同 memoryId 正在运行时拒绝；
3. **旧执行回调**：恢复使用新 executionId，旧回调无法写入新执行；
4. **模型调用**：重新请求模型无业务副作用，属于安全重试；
5. **工具副作用**：runState 终态下无在途工具，初版不需要工具幂等；
6. **取消竞争**：恢复后的运行按普通运行处理，取消规则不变；
7. **合成消息**：取消补偿写入的 synthetic AiMessage 在恢复时剔除，避免误判结束。

## 10. 未来扩展

| 能力 | 需要补充 |
| --- | --- |
| 工具执行中崩溃恢复 | `AgentExecutionCheckpoint`、`INTERRUPTED` 状态、工具结果账本 |
| 审核中断恢复 | 单独接口（如 `IApprovalResumableAgent`）、`SUSPENDED` 状态、`approvalId/decision`、工具执行闸门 |
| 用户取消后继续 | 显式 `USER_CONTINUE` 语义，默认禁止自动恢复 |
| 恢复来源追踪 | `previousExecutionId`、`resumeSource` 持久化 |
| 恢复指标 | 恢复次数、来源 runState、恢复后成功/失败、恢复后步数 |

## 11. 代码落点

```text
agentforge-agent-core
└── agent/
    ├── IResumableAgent.java
    ├── BaseAgent.java
    │   └── prepareMessagesForResume(...)
    ├── ReActAgent.java
    │   └── prepareMessagesForResume(...)  # 刷新系统消息、剔除 synthetic AI
    ├── Agent.java
    │   ├── resume / resumeStream
    │   ├── prepareResume
    │   ├── executeLoop
    │   └── finishCompletedResume
    ├── stream/
    │   └── CompletedTokenStream.java
    └── component/state/
        └── compensation/
            └── MessageChainRepairer.java
```

## 12. 验收测试

| 用例 | 预期 |
| --- | --- |
| `MODEL_CALL_ERROR` 后恢复 | 不重复追加用户消息，重新请求模型并正常结束 |
| `MAX_STEPS` 后恢复 | 使用新步数预算，从已有工具结果继续 |
| `SUCCESS` 后恢复 | 直接返回历史结果，不调用模型 |
| executionId 不属于该 memoryId | 拒绝并抛出明确异常 |
| 无 ChatMemory 时恢复 | 抛出明确异常，不创建空执行 |
| 同 memoryId 正在运行时恢复 | 拒绝，不影响活跃执行 |
| 恢复过程中 cancel | 使用新 executionId 精确取消 |
| 最后是缺结果的 tool call | 补齐 synthetic 结果后继续 |
| 取消补偿后的 synthetic AI | 恢复时被剔除，不会误判结束 |
| 恢复后旧执行迟到回调 | 旧 executionId 回调被忽略 |

## 13. 最终建议

把恢复做成独立能力 `IResumableAgent`：平台持久化 ChatMemory 和 runState，异常停止后按 `(memoryId, executionId)` 调用 `resume`；框架复用历史、生成新 executionId、修复消息链并继续 ReAct。

初版覆盖“干净终止”的 runState 恢复；工具执行中崩溃、审核中断等场景先不实现，通过“独立接口 + 新执行 ID + 消息修复器 + 状态持久化 SPI”留好扩展位。

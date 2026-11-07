# AgentForge Agent 会话、消息状态与流式事件一览表

## 一、文档目的

本文集中说明 AgentForge 对话链路中的会话状态、消息状态、Agent 停止状态和流式事件，作为后端状态更新、前端渲染、停止接口和历史重放的统一对照表。

状态分为四层：

| 层级 | 权威对象 | 解决的问题 |
| --- | --- | --- |
| Agent 执行层 | `StopResultState` / `StopResult` | Agent 为什么停止、产生了什么结果 |
| 会话层 | `ai_agent_session.status` | 当前会话是否存在运行中的回答 |
| 消息层 | `ai_agent_session_message.status` | 某一轮 AI 回答的最终业务状态 |
| 事件层 | `StreamEventResponse.event/type` | 流式过程如何实时展示和历史重放 |

标记说明：

| 标记 | 含义 |
| --- | --- |
| 已实现 | 当前代码中已经存在并生效 |
| 待落地 | 已进入设计，但尚未实现 |

取消链路（`CANCELLING`、`TIMEOUT_CANCELLED`、停止接口）当前已落地。

## 二、会话状态一览

对应枚举：`SessionStatusEnum`；对应字段：`ai_agent_session.status`。

| 枚举 | 编码 | 中文说明 | 是否终态 | 进入条件 | 允许的后续状态 | 当前状态 |
| --- | ---: | --- | --- | --- | --- | --- |
| `IDLE` | 0 | 空闲 | 否 | 会话创建完成，尚无 Agent Run | `RUNNING` | 已实现 |
| `RUNNING` | 1 | 运行中 | 否 | AI 消息开始处理，Agent Run 已启动 | `CANCELLING`、`FINISHED`、`FAILED` | 已实现 |
| `FINISHED` | 2 | 完成 | 是（针对当前轮） | AI 消息成功、取消、超时取消或其他已收口终态 | 新一轮对话时可重新进入 `RUNNING` | 已实现 |
| `FAILED` | 3 | 失败 | 是（针对当前轮） | 会话级初始化或无法归属到具体消息的异常 | 新一轮对话时可重新进入 `RUNNING` | 已实现 |
| `CANCELLING` | 4 | 取消中 | 否 | 停止请求受理，消息 CAS 到 `CANCELLING` | `FINISHED` | 已实现 |

会话状态表示“会话当前是否还有运行回答”，不替代消息状态。取消原因、失败原因和成功结果必须查看具体 AI 消息。`CANCELLING` 仅表示已受理停止、等待 Agent 收口，最终仍以消息终态为准。

## 三、消息角色一览

对应枚举：`SessionMessageRoleEnum`；对应字段：`ai_agent_session_message.role_type`。

| 枚举 | 编码 | 说明 | 是否运行 Agent | 是否产生流式事件 |
| --- | ---: | --- | --- | --- |
| `USER` | 1 | 用户输入消息 | 否 | 运行时追加消息可能产生 `user_message` 事件 |
| `AI` | 2 | Agent 回答消息，同时作为一轮后台任务 | 是 | 是 |

二段式对话中的 `messageId` 指 AI 消息主键，不使用 USER 消息 ID 作为任务标识。

## 四、AI 消息状态一览

对应枚举：`SessionMessageStatusEnum`；对应字段：`ai_agent_session_message.status`。

| 枚举 | 编码 | 中文说明 | 是否终态 | 进入条件 | 前端表现 | 当前状态 |
| --- | ---: | --- | --- | --- | --- | --- |
| `CREATED` | 1 | 创建 | 否 | AI 占位消息已创建，后台线程尚未进入运行回调 | 显示准备中，可建立订阅 | 已实现 |
| `RUNNING` | 2 | 处理中 | 否 | `SessionLifecycleMiddleware.onInitComplete` 推进状态 | 显示生成中，继续订阅事件 | 已实现 |
| `FAILED` | 3 | 失败 | 是 | 模型异常、启动失败、最大步数等失败收口 | 保留已生成内容并展示失败状态 | 已实现 |
| `CANCELLED` | 4 | 取消 | 是 | Agent 响应 cancel，触发 `onStop(CANCEL)` 正常收口 | 保留已生成内容，展示“用户已停止” | 已实现 |
| `SUCCEEDED` | 5 | 成功 | 是 | Agent 触发 `onStop(NORMAL)` | 展示完整回答 | 已实现 |
| `TIMEOUT_CANCELLED` | 6 | 超时取消 | 是 | 停止接口等待 Agent 收口超时，CAS 将 `CREATED/RUNNING` 更新为该状态 | 展示“停止超时，任务已强制收口” | 待落地 |

### 4.1 消息状态转换表

| 当前状态 | 触发动作 | 目标状态 | 更新者 | 约束 |
| --- | --- | --- | --- | --- |
| 无 | 初始化 AI 占位消息 | `CREATED` | `AgentSessionServiceImpl` | 与 USER 消息在请求准备阶段创建 |
| `CREATED` | Agent 初始化完成 | `RUNNING` | `SessionLifecycleMiddleware` | 同步写入 `started_at` |
| `CREATED/RUNNING` | 正常停止 | `SUCCEEDED` | `SessionLifecycleMiddleware.onStop` | `StopResultState.NORMAL` |
| `CREATED/RUNNING` | Agent 正常响应取消 | `CANCELLED` | `SessionLifecycleMiddleware.onStop` | `StopResultState.CANCEL` |
| `CREATED/RUNNING` | 最大步数或异常 | `FAILED` | `SessionLifecycleMiddleware` | `MAX_STEPS` 或 `onStopWithError` |
| `CREATED/RUNNING` | 停止等待超时 | `TIMEOUT_CANCELLED` | `AgentChatTaskServiceImpl` | 必须使用运行态条件 CAS，待落地 |
| 任意终态 | 迟到回调或重复停止 | 保持原终态 | Mapper 条件更新 | 不允许终态互相覆盖 |

### 4.2 消息终态判断

| 状态 | 当前 `isTerminal` | 停止接口落地后 |
| --- | --- | --- |
| `CREATED` | 否 | 否 |
| `RUNNING` | 否 | 否 |
| `FAILED` | 是 | 是 |
| `CANCELLED` | 是 | 是 |
| `SUCCEEDED` | 是 | 是 |
| `TIMEOUT_CANCELLED` | 不存在 | 是 |

`TIMEOUT_CANCELLED` 落地时需要同步修改 `SessionStatusTransfer.isTerminal` 和 `ReconnectableChatStreamService.isTerminal`。

## 五、Agent 停止状态映射

对应枚举：`StopResultState`。

| `StopResultState` | 编码 | `StopResult` 数据 | 消息状态 | 会话状态 | 末尾事件 |
| --- | ---: | --- | --- | --- | --- |
| `NORMAL` | 0 | `result=最终回答` | `SUCCEEDED(5)` | `FINISHED(2)` | `resp_end` |
| `CANCEL` | 1 | `reason=取消原因` | `CANCELLED(4)` | `FINISHED(2)` | `resp_end` |
| `MAX_STEPS` | 2 | `reason=达到最大步数` | `FAILED(3)` | `FINISHED(2)` | `resp_end` |
| 未进入 `onStop` 的异常 | — | Throwable | `FAILED(3)` | `FINISHED(2)` 或 `FAILED(3)` | `error` |
| 停止接口等待超时 | — | 超时原因 | `TIMEOUT_CANCELLED(6)` | `FINISHED(2)` | 可能没有新事件，消费端按终态尾读结束 |

框架不单独增加 `onCancel`。取消、正常完成和最大步数统一进入 `onStop`，通过 `StopResultState` 区分，避免同一轮任务出现两个终止回调。

## 六、会话与消息联动一览

| 场景 | AI 消息状态 | 会话状态 | 事件处理 |
| --- | --- | --- | --- |
| 新会话已创建 | `CREATED` | `IDLE` | 暂无或仅有 session 事件 |
| Agent 开始运行 | `RUNNING` | `RUNNING` | 开始写 think/resp/tool 等事件 |
| 正常完成 | `SUCCEEDED` | `FINISHED` | `resp_end` 后进入终态 |
| 用户取消成功 | `CANCELLED` | `FINISHED` | 保留已有事件，以 `resp_end` 收尾 |
| 停止等待超时 | `TIMEOUT_CANCELLED` | `FINISHED` | 保留已有事件，尾读为空后按终态结束 |
| Agent 执行失败 | `FAILED` | `FINISHED` | 输出 `error`，保留已有事件 |
| 会话级初始化失败 | 无消息或 `FAILED` | `FAILED` | 不启动或立即结束流 |
| 开始下一轮对话 | 新消息 `CREATED/RUNNING` | `RUNNING` | 上一轮终态不变，新 `messageId` 单独产生事件 |

## 七、流式事件一览

对应枚举：`StreamEventEnums.EventEnum` 和 `TypeEnum`。

| event | 允许的 type | 主要 content | 产生时机 | 是否写入 EventStore | 是否进入历史 events |
| --- | --- | --- | --- | --- | --- |
| `session` | `created` | 会话标识/标题 | 新会话建立 | 是 | 是 |
| `think` | `text` | 思考增量文本 | 模型输出 reasoning | 是；MySQL Sink 可合并相邻增量 | 是 |
| `resp` | `text` | 回答增量文本 | 模型输出回答 | 是；MySQL Sink 可合并相邻增量 | 是 |
| `tool_call` | `tool_card` | 工具名、参数、结果和耗时 | 工具开始、完成或失败 | 是 | 是 |
| `retry` | `retry` | 当前轮次、重试次数、等待时间 | 模型调用重试 | 是 | 是 |
| `compact` | `compacting` | 压缩开始信息 | 上下文压缩开始 | 是 | 是 |
| `compact` | `compacted` | 压缩结果 | 上下文压缩成功 | 是 | 是 |
| `compact` | `compact_failed` | 失败原因 | 上下文压缩失败 | 是 | 是 |
| `user_message` | `append_queued` | 追加消息信息 | 运行中消息进入队列 | 是 | 是 |
| `user_message` | `append_effective` | 已生效追加消息 | 追加消息进入当前 Agent Loop | 是 | 是 |
| `user_message` | `append_deferred` | 延迟到下一轮的消息 | 本轮未赶上消费点 | 是 | 是 |
| `user_message` | `append_canceled` | 被取消的追加消息 | 用户取消排队消息 | 是 | 是 |
| `resp_end` | 无 | 通常为空，`isFinish=true` | 正常、取消或最大步数停止 | 是 | 是，必须作为停止边界 |
| `error` | `text` | 错误信息 | Agent 异常停止 | 是或尽力写入 | 是 |

### 7.1 不持久化的传输事件

| 事件 | 表现形式 | 是否写 Store | 原因 |
| --- | --- | --- | --- |
| heartbeat | SSE comment `: heartbeat` | 否 | 只用于维持 HTTP 连接，不是业务事件 |
| SSE completion | `SseEmitter.complete()` | 否 | 连接生命周期动作，不参与历史重放 |
| SSE error | `completeWithError` | 否 | 连接异常不等于 Agent 业务失败 |

## 八、StreamEventResponse 字段一览

| 字段 | 类型 | 必填性 | 说明 |
| --- | --- | --- | --- |
| `event` | String | 必填 | 事件大类，对应 `EventEnum.code` |
| `type` | String | 按 event | 事件子类型，无子类型的 `resp_end` 不填写 |
| `title` | String | 可选 | 展示标题 |
| `content` | Object | 按事件 | 文本、工具卡片、重试信息或其他业务载荷 |
| `isFinish` | Boolean | 可选 | 当前事件/卡片是否完成，不替代消息状态 |
| `eventSeq` | Long | 二段式必填 | 同一 AI `messageId` 内严格递增的重放游标 |

事件唯一身份使用 `messageId + eventSeq`。工具开始和结束卡片可以共享 `toolCallId` 进行 UI 聚合，但必须拥有不同的 `eventSeq`。

## 九、前端状态与渲染一览

| 消息状态 | 是否继续订阅 | Loading | 结束提示 | 是否保留已有内容 |
| --- | --- | --- | --- | --- |
| `CREATED` | 是 | 是 | 无 | 是 |
| `RUNNING` | 是 | 是 | 无 | 是 |
| `SUCCEEDED` | 排空尾事件后否 | 否 | 正常完成 | 是 |
| `FAILED` | 排空尾事件后否 | 否 | 生成失败 | 是 |
| `CANCELLED` | 排空尾事件后否 | 否 | 用户已停止 | 是 |
| `TIMEOUT_CANCELLED` | 排空尾事件后否 | 否 | 停止超时，任务已强制收口 | 是 |

页面不能仅根据 `resp_end` 推断消息成功：`resp_end` 表示流结束边界，最终业务结果必须以消息 `status` 为准。

## 十、状态更新约束

| 约束 | 说明 |
| --- | --- |
| 消息是任务状态权威来源 | 不新增独立任务状态表，不从 SSE 是否连接推断任务状态 |
| 事件先于终态 | 最后一条 `resp_end/error` 写入 Store 后，消息才能更新终态 |
| 终态不可覆盖 | 迟到回调只能更新 `CREATED/RUNNING`，不能覆盖其他终态 |
| 取消保留内容 | `CANCELLED/TIMEOUT_CANCELLED` 不清空 content 和已产生事件 |
| 每轮独立 | 同一 session 的不同 AI message 使用不同 `messageId/eventSeq` 空间 |
| 心跳不入历史 | heartbeat 与业务事件严格分离 |
| 前端幂等 | 重放允许重复，前端使用 `messageId + eventSeq` 去重 |

## 十一、相关设计文档

| 文档 | 说明 |
| --- | --- |
| 《AgentForge流式对话支持重连二段式设计方案.md》 | submit/stream、事件 Store/Sink、重连及 Redis 扩展 |
| 《AgentForge二段式流式对话停止与超时取消设计方案.md》 | stop、轮询等待、`TIMEOUT_CANCELLED` 和多实例取消 |

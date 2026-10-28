# AgentForge Agent 链路追踪设计方案：火焰图与流程图

## 1. 文档定位

本文档面向 AgentForge 当前工程，设计一套覆盖 Agent 运行时、Service 业务层和 Service UI 的链路追踪能力，用于回答以下问题：

1. 一次 Agent 请求经过了多少轮 ReAct Loop。
2. 每一轮 Loop 中模型调用和工具执行分别耗时多久。
3. 工具调用的参数、结果、状态和异常发生在哪一层。
4. 一次用户提问最终消耗了多少 token，为什么耗时较长。
5. 调试面板中的一次 Agent 运行，如何跳转到可交互的流程图和火焰图。

本文档不是对原有聊天页面的改名，而是将链路追踪能力放回 AgentForge 的真实工程边界中：

1. Agent Runtime 只负责采集运行事实，不依赖 Spring、MySQL 或 Vue。
2. AgentForge Service 负责把运行事实与 Agent 会话、用户请求、调试态关联起来。
3. AgentForge Service UI 负责把快照转换成流程图、火焰图和调试入口。

当前工程已经具备中间件回调、同步执行、流式执行、会话持久化和 SSE 事件重放能力；链路追踪是在这些能力之上新增的一条观测旁路，不改变 Agent 的主执行语义。

---

## 2. AgentForge 当前工程基线

### 2.1 模块边界

| 层次 | 当前模块 | 现有职责 | 链路追踪新增职责 |
| --- | --- | --- | --- |
| 模型层 | agentforge-model | 统一 ChatModel、ChatRequest、ChatResponse、AiMessage、ToolExecutionRequest 等模型协议 | 提供模型响应和 TokenUsage 作为追踪输入 |
| Agent 框架层 | agentforge-framework/agentforge-agent-core | Agent、ReActAgent、ChatMemory、ToolService、中间件和流式执行 | 采集 Loop、模型、工具和终态快照 |
| Service 后端 | agentforge-service/agentforge-service-web | Agent 配置、运行时装配、会话、消息、SSE 和插件工具 | 注入追踪中间件、落库、查询和聚合 |
| Service 前端 | agentforge-service/agentforge-service-ui | Agent 配置、调试面板、会话历史、事件重放 | 提供 Trace 预览、流程图、火焰图和实时刷新 |
| 数据库 | agentforge-service/sql | Agent 配置、会话、消息和 ChatMemory 持久化 | 新增 Agent Trace 快照表 |

### 2.2 当前运行链路

AgentForge 的一次请求大致经过以下路径：

1. AgentForge Service 接收 aid、sessionId、message、isDebug 等请求参数。
2. AgentRuntimeConfigResolver 解析 Agent 配置，区分正式配置和调试配置。
3. AgentManagerFactory 获取对应的 Agent Manager。
4. ReActAgentFactory 装配模型、ChatMemory、工具和默认中间件。
5. Agent.run 或 Agent.runStream 初始化 AgentChatContext。
6. BaseAgent 将本轮 UserMessage 写入 ChatMemory。
7. Agent 进入 ReAct Loop，调用模型、执行工具并把消息写回 ChatMemory。
8. SessionLifecycleMiddleware 收口 AgentSession 和 AgentSessionMessage。
9. ServiceStreamingMiddleware 将统一事件推送到 Service UI。

链路追踪应挂在第 4 步的中间件体系中，使用 AgentForge 已有的生命周期回调，而不是在 Controller 中重复解析模型请求或 SSE 字符串。

### 2.3 现有关键代码

Agent Runtime：

1. agentforge-framework/agentforge-agent-core/src/main/java/cloud/changlu/agentforge/agent/Agent.java
2. agentforge-framework/agentforge-agent-core/src/main/java/cloud/changlu/agentforge/agent/BaseAgent.java
3. agentforge-framework/agentforge-agent-core/src/main/java/cloud/changlu/agentforge/agent/BaseReActAgent.java
4. agentforge-framework/agentforge-agent-core/src/main/java/cloud/changlu/agentforge/agent/ReActAgent.java
5. agentforge-framework/agentforge-agent-core/src/main/java/cloud/changlu/agentforge/agent/domain/AgentChatContext.java
6. agentforge-framework/agentforge-agent-core/src/main/java/cloud/changlu/agentforge/agent/memory/ChatMemory.java
7. agentforge-framework/agentforge-agent-core/src/main/java/cloud/changlu/agentforge/agent/stream/ReActTokenStream.java
8. agentforge-framework/agentforge-agent-core/src/main/java/cloud/changlu/agentforge/agent/component/middleware/IAgentMiddleware.java
9. agentforge-framework/agentforge-agent-core/src/main/java/cloud/changlu/agentforge/agent/component/middleware/IStreamingIAgentMiddleware.java
10. agentforge-framework/agentforge-agent-core/src/main/java/cloud/changlu/agentforge/agent/component/middleware/AgentMiddlewareManager.java

Service 后端：

1. agentforge-service/agentforge-service-web/src/main/java/cloud/changlu/agentforge/service/modules/agent/runtime/mode/react/ReActAgentFactory.java
2. agentforge-service/agentforge-service-web/src/main/java/cloud/changlu/agentforge/service/modules/agent/middleware/SessionLifecycleMiddleware.java
3. agentforge-service/agentforge-service-web/src/main/java/cloud/changlu/agentforge/service/modules/agent/middleware/ServiceStreamingMiddleware.java
4. agentforge-service/agentforge-service-web/src/main/java/cloud/changlu/agentforge/service/modules/session
5. agentforge-service/agentforge-service-web/src/main/java/cloud/changlu/agentforge/service/modules/agent/controller/AgentChatController.java
6. agentforge-service/agentforge-service-web/src/main/java/cloud/changlu/agentforge/service/modules/agent/runtime/base/AbstractAgentManager.java

Service UI：

1. agentforge-service/agentforge-service-ui/src/pages/agents/AgentDebugPanel.vue
2. agentforge-service/agentforge-service-ui/src/utils/sessionReplay.js
3. agentforge-service/agentforge-service-ui/src/utils/sessionApi.js
4. agentforge-service/agentforge-service-ui/src/components/AgentMessageContent.vue
5. agentforge-service/agentforge-service-ui/src/pages/published/PublishedAgentChatPage.vue

---

## 3. 设计目标与非目标

### 3.1 设计目标

1. 同时覆盖 Agent.run 的同步链路和 Agent.runStream 的流式链路。
2. 以 AgentForge 的 AgentChatContext.memoryId 作为会话追踪主键。
3. 以一次用户请求作为一个 questionRound，以一次 ReAct Loop 作为一个 step。
4. 可以分别统计模型耗时、工具耗时、总耗时和 token。
5. 同一份追踪数据可以驱动流程图、火焰图、列表摘要和节点详情。
6. 追踪采集、落库或展示失败时，不能影响 Agent 主流程。
7. 与现有 ai_agent_session、ai_agent_session_message、ai_agent_memrory 职责分离。
8. 支持调试会话和正式会话使用同一套追踪协议。

### 3.2 非目标

1. 不把 Trace 设计成新的模型协议。
2. 不替换 ChatMemory，也不把 Trace 快照当作下一轮模型上下文。
3. 不将厂商原始 SSE 片段直接暴露给前端。
4. 不在 Agent Core 中引入 Spring、MyBatis、MySQL 或 Vue 依赖。
5. 不通过前端对 events 字段进行二次猜测来还原完整耗时。

---

## 4. 核心概念

### 4.1 sessionId

AgentForge 当前以 AgentRequest.memoryId 作为 Agent 运行时会话 ID。Service 层请求中的 sessionId 应映射到该字段：

1. ai_agent_session.session_id 是对外稳定会话标识。
2. AgentRequest.memoryId 是 Agent Runtime 使用的记忆标识。
3. 两者在当前 Service 链路中保持相同字符串值。
4. ai_agent_session.id 仅用于 Service 数据库内部关联，不直接传给 Agent Runtime。

AgentTraceSnapshot 只依赖 memoryId，不依赖数据库主键，因此 Agent Core 可以脱离 AgentForge Service 单独使用。

### 4.2 questionRound

questionRound 表示同一 sessionId 下第几次用户提问，从 1 开始递增。

它与 AgentForge 当前会话消息模型的关系是：

1. 一次请求创建一条 USER 消息和一条 AI 消息占位记录。
2. 一次请求对应一个 questionRound。
3. 一次 questionRound 内可能执行多个 step。
4. 一个 questionRound 最终可以对应多个普通 step 快照和一个汇总快照。

questionRound 属于 Service 业务语义，不应由 Agent Core 自己查询数据库或通过 finished 数量推断。建议在请求初始化、创建 AI 消息占位记录时分配，并通过 AgentRequest.extensions 透传给 AgentChatContext。

这样可以避免两个请求同时进入同一个 session 时，使用 count + 1 导致轮次冲突。

### 4.3 step

step 表示一次完整的 ReAct Loop：

1. 构造本轮 ChatRequest。
2. 调用一次 ChatModel 或 StreamingChatModel。
3. 获得 AiMessage。
4. 如果包含 ToolExecutionRequest，则执行一个或多个工具。
5. 工具结果写回 ChatMemory。
6. 判断是否结束，或者进入下一个 step。

因此：

1. step=1 是本次提问的第一轮模型推理。
2. step=2 是工具返回后进入的下一轮模型推理。
3. 一个 step 可以有多个并发工具。
4. 工具本身不是新的 step，而是 step 内的子节点。

### 4.4 snapshot

snapshot 是某个时刻对 Agent 运行状态的结构化记录：

1. STEP 快照：记录当前 step 的消息、耗时、token 和状态。
2. ROUND_FINAL 快照：记录当前 questionRound 的累计耗时、累计 token 和最终状态。

STEP 快照用于定位单轮问题，ROUND_FINAL 快照用于列表摘要和最终统计。两种快照必须通过 snapshotKind 区分，不能依靠覆盖同一条数据库记录来表达，否则会丢失最后一个 step 的明细。

---

## 5. 总体架构

### 5.1 三层职责

~~~text
AgentForge Service Request
        |
        v
ReActAgentFactory
        |
        +--> SessionLifecycleMiddleware    会话与 SSE 事件收口
        +--> ServiceStreamingMiddleware   流式事件输出
        +--> AgentTraceMiddleware         链路事实采集
        |
        v
Agent / ReActAgent
        |
        +--> beforeLoop / afterLoop
        +--> beforeModelCall / afterModelCall
        +--> beforeToolExecution / afterToolExecution
        +--> onStop / onStopWithError
        |
        v
AgentTraceSnapshot
        |
        +--> AgentTraceCollector
                  |
                  +--> MysqlAgentTraceCollector
                  +--> 未来的 MQ / ES / 日志 / 实时推送实现
~~~

### 5.2 为什么选择 Agent Middleware

AgentForge 已经将同步和流式执行统一到 Agent Middleware：

1. Agent.run 负责同步 Loop。
2. ReActTokenStream 负责流式 Loop。
3. 两条链路都会触发 onInitComplete、beforeLoop、模型回调、工具回调和停止回调。
4. AgentMiddlewareManager 负责按注册顺序分发回调并隔离单个中间件异常。

因此 TraceMiddleware 可以复用同一套回调，不需要在同步 Agent、流式 TokenStream 和 Service Controller 中分别复制埋点逻辑。

### 5.3 与现有中间件的边界

| 中间件 | 主要职责 | 是否持久化 AgentTraceSnapshot |
| --- | --- | --- |
| LoggingIAgentMiddleware | 日志、耗时和 token 的运行期统计 | 否 |
| SessionLifecycleMiddleware | ai_agent_session、ai_agent_session_message 和事件收口 | 否 |
| ServiceStreamingMiddleware | 将统一事件推送给前端 | 否 |
| AgentTraceMiddleware | 采集 AgentTraceRuntime 并生成快照 | 通过 Collector 间接持久化 |

TraceMiddleware 不应替换 LoggingIAgentMiddleware。日志适合排查服务端问题，Trace 适合面向用户的可视化和离线查询，两者的数据保留周期、字段粒度和失败策略不同。

---

## 6. Trace 协议设计

### 6.1 AgentTraceSnapshot

建议将公共协议放在 agentforge-agent-core 中：

1. cloud/changlu/agentforge/agent/trace/AgentTraceCollector.java
2. cloud/changlu/agentforge/agent/trace/AgentTraceMiddleware.java
3. cloud/changlu/agentforge/agent/trace/domain/AgentTraceSnapshot.java
4. cloud/changlu/agentforge/agent/trace/domain/AgentTraceMessageSnapshot.java
5. cloud/changlu/agentforge/agent/trace/domain/AgentTraceNodeMeta.java
6. cloud/changlu/agentforge/agent/trace/domain/AgentTraceTokenUsage.java
7. cloud/changlu/agentforge/agent/trace/runtime/AgentTraceRuntime.java
8. cloud/changlu/agentforge/agent/trace/runtime/AgentTraceStepRuntime.java
9. cloud/changlu/agentforge/agent/trace/runtime/AgentTraceToolRuntime.java

AgentTraceSnapshot 建议字段如下：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| sessionId | String | 来自 AgentChatContext.memoryId |
| questionRound | Integer | 来自 AgentRequest.extensions 的业务轮次 |
| step | Integer | 当前 ReAct Loop，从 1 开始 |
| snapshotKind | STEP / ROUND_FINAL | 普通 step 或问答汇总 |
| startedAt | Long | 当前快照开始时间，Unix 毫秒 |
| finishedAt | Long | 当前快照结束时间，Unix 毫秒 |
| durationMs | Long | 当前快照总耗时 |
| modelDurationMs | Long | 模型调用耗时 |
| toolDurationMs | Long | 工具执行耗时 |
| inputTokens | Long | 当前快照输入 token |
| outputTokens | Long | 当前快照输出 token |
| totalTokens | Long | 当前快照总 token |
| cachedTokens | Long | 可选，当前统一 TokenUsage 尚未提供时为 null |
| finished | Boolean | 是否为 ROUND_FINAL 快照 |
| status | AgentTraceSnapshotStatus | CONTINUE、NORMAL、CANCEL、ERROR、MAX_STEPS |
| messages | List | 当前快照中的可视化消息 |
| attributes | Map | aid、isDebug、finishReason 等扩展信息 |

AgentForge 当前 TokenUsage 已统一提供 inputTokens、outputTokens、totalTokens。cachedTokens 应作为可选能力处理：后续可以扩展 TokenUsage，或者约定 ChatResponse.metadata 中的标准字段；在没有统一来源之前，不能把缓存 token 当成可靠统计值。

### 6.2 AgentTraceSnapshotStatus

Trace 状态是 Trace 协议自己的稳定枚举，不直接复用数据库数字或 StopResultState 的数值：

| 状态 | 含义 | AgentForge 来源 |
| --- | --- | --- |
| CONTINUE | 当前 step 完成但本次请求尚未结束 | StepState.FINISHED / RUNNING |
| NORMAL | Agent 正常得到最终回答 | StopResultState.NORMAL |
| CANCEL | 用户取消或线程中断 | StopResultState.CANCEL、CancelException |
| ERROR | 模型、工具或 Agent 运行异常 | onStopWithError |
| MAX_STEPS | 达到 AgentSettings.maxSteps | StopResultState.MAX_STEPS |

### 6.3 TraceNodeType

~~~text
ROOT        当前 questionRound
STEP        一轮 ReAct Loop
AI          当前 step 的模型输出
TOOL_CALL   模型发出的 ToolExecutionRequest
TOOL_RESULT 工具返回的 ToolExecutionResultMessage
~~~

流程图和火焰图只消费统一节点类型，不直接判断 AiMessage 的具体 Java 类。

### 6.4 消息快照

AgentForge 的 ChatMemory.messages() 是真实模型上下文，消息类型来自 agentforge-model-api：

1. SystemMessage
2. UserMessage
3. AiMessage
4. ToolExecutionResultMessage
5. CustomMessage

AgentTraceMessageSnapshot 只做展示型投影，不改写 ChatMemory 中的原始对象。建议字段：

| 字段 | 说明 |
| --- | --- |
| messageKey | 用于多条 step 快照合并去重 |
| messageType | SYSTEM、USER、AI、TOOL_EXECUTION_RESULT、CUSTOM |
| text | 展示文本或截断后的文本 |
| thinking | 可选的模型思考内容 |
| toolExecutionRequests | AI 消息携带的工具请求 |
| trace | 节点类型、节点状态、开始时间、结束时间和耗时 |

消息快照应遵循以下安全规则：

1. 工具参数和工具结果默认截断。
2. API Key、Authorization、Cookie 等敏感字段必须脱敏。
3. 追踪详情不默认保存完整模型请求头。
4. 前端展示所需的内容与 ChatMemory 持久化内容分离。

### 6.5 AgentTraceCollector

Agent Core 只定义最小 SPI：

~~~java
public interface AgentTraceCollector {

    void onSnapshot(AgentTraceSnapshot snapshot);
}
~~~

Collector 的约束：

1. 不要求实现方依赖 Spring。
2. 不要求实现方使用 MySQL。
3. 不允许 Collector 异常反向中断 Agent 主流程。
4. 实现方应自行处理异步化、重试、限流和数据保留。

AgentTraceMiddleware 在调用 Collector 时应捕获 RuntimeException 并记录日志。对于页面观测而言，偶发丢失一条快照比阻断一次 Agent 请求更可接受。

---

## 7. Agent Core 采集设计

### 7.1 AgentTraceRuntime

AgentTraceRuntime 按 sessionId 保存一次运行中的内存状态：

~~~text
AgentTraceRuntime
├── sessionId
├── questionRound
├── question
├── sessionStartedAt
├── currentStep
├── stepRuntimes
├── totalModelDurationMs
├── totalToolDurationMs
├── totalInputTokens
├── totalOutputTokens
├── totalTokens
├── finalStatus
└── finalResult
~~~

运行态只存在于 Agent 执行期间，不直接暴露给 Service UI，也不作为跨请求缓存。

### 7.2 AgentTraceStepRuntime

每个 step 维护：

1. step 开始时间和结束时间。
2. 模型调用开始时间和结束时间。
3. 工具调用开始时间和结束时间。
4. 本轮模型 token。
5. 本轮 ChatRequest、ChatResponse 和可展示消息。
6. 当前 step 的状态。

模型调用必须使用 currentStep 作为索引，工具调用必须优先使用 ToolExecutionRequest.id 作为索引。工具可以并发执行，不能使用单一的全局 toolStartTime。

### 7.3 AgentTraceToolRuntime

AgentTraceToolRuntime 维护：

1. toolCallId。
2. toolName。
3. 输入参数摘要。
4. 开始时间。
5. 结束时间。
6. durationMs。
7. SUCCESS 或 ERROR 状态。
8. 结果摘要和异常信息。

并发工具场景应使用 onConcurrentToolExecutionComplete 分别收口每一个工具，不将整个并发批次误记成一个工具节点。

### 7.4 生命周期接入点

AgentTraceMiddleware 应实现 IAgentMiddleware，并按以下回调采集：

| 回调 | 采集动作 |
| --- | --- |
| onInitComplete | 创建 AgentTraceRuntime，记录 sessionId、questionRound、question 和 sessionStart |
| beforeLoop | 创建 AgentTraceStepRuntime，记录 stepStart |
| beforeModelCall | 记录 modelStart，保存请求摘要 |
| afterModelCall | 记录 modelEnd、TokenUsage、FinishReason 和 AiMessage |
| onModelCallError | 收口模型节点为 ERROR |
| beforeToolExecution | 创建 ToolRuntime，记录 toolStart |
| afterToolExecution | 收口成功工具，记录结果摘要 |
| onToolExecutionError | 收口失败工具，记录异常摘要 |
| beforeConcurrentToolExecution | 批量创建工具运行态 |
| onConcurrentToolExecutionComplete | 分别收口每个并发工具 |
| afterConcurrentToolExecution | 校验结果数量，不重复写工具完成事件 |
| afterLoop | 生成或更新当前 STEP 快照 |
| onLoopError | 将当前 step 标记为 ERROR 或 CANCEL |
| onStop | 生成 ROUND_FINAL 快照 |
| onStopWithError | 生成 ERROR 的 ROUND_FINAL 快照 |

当前 AgentForge 已有的中间件回调名称以 IAgentMiddleware 和 IStreamingIAgentMiddleware 为准，不新增旧工程中的私有生命周期。

### 7.5 同步链路

同步链路由 Agent.run 驱动：

1. init 创建 AgentChatContext 并写入 UserMessage。
2. onInitComplete 初始化 AgentTraceRuntime。
3. while currentStep <= maxSteps：
   - beforeLoop
   - think / act
   - afterLoop
4. 正常结束、取消、达到最大步数或异常时调用对应停止回调。
5. TraceMiddleware 在停止回调中生成 ROUND_FINAL。

### 7.6 流式链路

流式链路由 ReActTokenStream 驱动：

1. TokenStream.start 触发 onInitComplete。
2. 每一次 chat(currentStep) 触发 beforeLoop。
3. 模型流式片段由 IStreamingIAgentMiddleware 的 onPartialResponse、onPartialThinking 接收。
4. 模型完整响应到达后触发 afterModelCall。
5. 工具执行完成后触发工具回调和 afterLoop。
6. 最后一轮无工具调用时触发 onStop。
7. 流式异常和取消沿 ReActTokenStream 的统一停止路径收口。

Trace 不需要逐 token 保存快照。流式片段只在内存中累加，模型响应完成或工具完成时更新当前节点，避免高频写库。

### 7.7 追踪与主流程隔离

AgentMiddlewareManager 当前会隔离单个中间件回调异常，但 TraceMiddleware 自己也必须做到：

1. 序列化失败不影响 Agent。
2. Collector 写库失败不影响 Agent。
3. 单个工具结果过长不影响其它工具。
4. AgentTraceRuntime 找不到时只记录 warning，不创建错误的跨会话状态。
5. 请求结束后清理 sessionId 对应的运行态，防止内存泄漏。

---

## 8. Service 后端接入设计

### 8.1 ReActAgentFactory 接入

当前 ReActAgentFactory 已负责装配：

1. LlmFactory 创建 ChatModel 和 StreamingChatModel。
2. ChatMemoryProvider。
3. LocalTool、HTTP Tool 和 MCP Tool。
4. LoggingIAgentMiddleware。
5. SessionLifecycleMiddleware。
6. ServiceStreamingMiddleware。

建议将 AgentTraceMiddleware 作为默认中间件加入同一处装配，并保持以下原则：

1. Agent Core 不感知 Spring Bean。
2. Service 通过构造器注入 AgentTraceCollector。
3. TraceMiddleware 只接收 Collector 和必要的运行配置。
4. 正式 Agent、调试 Agent 和未来其它入口复用同一套装配逻辑。

示意：

~~~java
builder
    .middleware(new LoggingIAgentMiddleware())
    .middleware(sessionLifecycleMiddleware)
    .middleware(streamingMiddleware)
    .middleware(traceMiddleware);
~~~

实际注册顺序应结合中间件是否会修改 ChatRequest、ChatResponse 或工具结果进行固定测试。TraceMiddleware 默认只观察，不改写数据，因此不应依赖其它中间件的副作用。

### 8.2 questionRound 的传递

Service 请求初始化时完成以下动作：

1. 校验 aid、sessionId 和用户消息。
2. 创建或读取 ai_agent_session。
3. 根据当前会话已存在的 USER/AI 请求记录分配 questionRound。
4. 创建 AI 消息占位记录，保留 session message 的收口目标。
5. 将 questionRound、aid、isDebug、sessionMessageId 等信息放入 AgentRequest.extensions。
6. ReActAgentFactory 创建 Agent，AgentChatContext 透传 extensions。

推荐扩展字段：

~~~text
agentforge.trace.questionRound
agentforge.trace.aid
agentforge.trace.debug
agentforge.trace.sessionMessageId
~~~

这样 Agent Core 只读取通用扩展，不直接依赖 Service 的 POJO。

### 8.3 与会话表的关系

AgentForge 当前已有四类相关持久化数据，职责不能混淆：

1. ai_agent_session：页面可见的会话元数据。
2. ai_agent_session_message：用户问题、最终回答和 SSE 事件重放数据。
3. ai_agent_memrory：模型上下文窗口的 ChatMemory 序列化数据。
4. ai_agent_trace_snapshot：用于火焰图、流程图和耗时统计的结构化 Trace 快照。

Trace 快照不替代 events：

1. events 适合还原前端实时消息表现。
2. AgentTraceSnapshot 适合表达模型、工具、step、时间和 token 的结构关系。
3. 两者可以通过 sessionId、questionRound 或 sessionMessageId 关联。
4. Trace 页面优先消费 AgentTraceSnapshot，必要时再跳转到会话消息重放。

### 8.4 MySQL 表设计

建议在 agentforge-service/sql/increment/1.0.x 下新增增量脚本。表名沿用 AgentForge 当前 ai_ 前缀规范：

~~~sql
CREATE TABLE IF NOT EXISTS ai_agent_trace_snapshot (
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    session_id          VARCHAR(64)  NOT NULL COMMENT '对外会话ID，同时对应 Agent memoryId',
    aid                 VARCHAR(64)  NOT NULL COMMENT 'Agent唯一标识',
    question_round      INT          NOT NULL COMMENT '当前会话第几次用户提问，从1开始',
    step                INT          NOT NULL COMMENT '当前ReAct Loop，从1开始',
    snapshot_kind       TINYINT      NOT NULL COMMENT '快照类型：0-STEP，1-ROUND_FINAL',
    started_at          DATETIME(3)  DEFAULT NULL COMMENT '快照开始时间',
    finished_at         DATETIME(3)  DEFAULT NULL COMMENT '快照结束时间',
    duration_ms         BIGINT       DEFAULT NULL COMMENT '快照耗时，单位毫秒',
    model_duration_ms   BIGINT       DEFAULT NULL COMMENT '模型调用耗时，单位毫秒',
    tool_duration_ms    BIGINT       DEFAULT NULL COMMENT '工具执行耗时，单位毫秒',
    input_tokens        BIGINT       DEFAULT NULL COMMENT '输入token数',
    output_tokens       BIGINT       DEFAULT NULL COMMENT '输出token数',
    total_tokens        BIGINT       DEFAULT NULL COMMENT '总token数',
    cached_tokens       BIGINT       DEFAULT NULL COMMENT '缓存命中token数，暂不支持时为空',
    finished            TINYINT      NOT NULL DEFAULT 0 COMMENT '是否为问答汇总快照',
    status              TINYINT      NOT NULL COMMENT 'Trace状态：0-continue 1-normal 2-cancel 3-error 4-max_steps',
    snapshot_json       LONGTEXT     NOT NULL COMMENT 'AgentTraceSnapshot序列化JSON',
    create_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    modify_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_trace_round_step_kind (session_id, question_round, step, snapshot_kind),
    KEY idx_trace_session_round (session_id, question_round),
    KEY idx_trace_session_finished (session_id, finished)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AgentForge Agent链路追踪快照表';
~~~

设计说明：

1. session_id 使用对外字符串，不直接耦合 ai_agent_session.id。
2. aid 便于跨会话运维检索和权限校验。
3. snapshot_kind 防止最终汇总快照覆盖最后一个 STEP 快照。
4. snapshot_json 保留完整协议，列字段用于快速列表查询和统计。
5. duration_ms、model_duration_ms、tool_duration_ms 使用 BIGINT，避免长时间运行溢出。
6. token 使用 BIGINT，避免多轮累计超过 INT。

### 8.5 Collector 与 QueryService

建议新增 Service 模块：

~~~text
agentforge-service-web/src/main/java/cloud/changlu/agentforge/service/modules/trace
├── controller/AgentTraceController
├── service/AgentTraceQueryService
├── service/MysqlAgentTraceCollector
├── dao/AgentTraceSnapshotMapper
├── domain/AgentTraceSnapshotPojo
└── domain/vo/AgentTraceSessionVo
~~~

MysqlAgentTraceCollector 职责：

1. 接收 AgentTraceMiddleware 产生的 AgentTraceSnapshot。
2. 校验 sessionId、aid 和 questionRound。
3. 序列化 snapshot_json。
4. 以 session_id + question_round + step + snapshot_kind 做幂等 upsert。
5. 不参与 questionRound 分配。

AgentTraceQueryService 职责：

1. 根据 sessionId 查询快照。
2. 按 questionRound 分组。
3. 每组计算 latestStep、stepCount、finished、status 和 token 汇总。
4. 优先使用 ROUND_FINAL 快照作为列表摘要。
5. 未完成时回退到最新 STEP 快照。
6. 按 createTime 或 step 排序，确保实时轮询结果稳定。

### 8.6 查询接口

推荐接口：

~~~text
GET /api/agent-trace/session/{sessionId}
~~~

响应结构建议：

~~~json
{
  "sessionId": "agent-debug-xxx",
  "aid": "demo-agent",
  "debug": true,
  "rounds": [
    {
      "questionRound": 1,
      "question": "用户问题摘要",
      "latestStep": 3,
      "stepCount": 3,
      "finished": true,
      "status": "NORMAL",
      "totalTokens": 1200,
      "cachedTokens": null,
      "snapshots": []
    }
  ]
}
~~~

调试会话不存在正式会话记录时，只要 trace_snapshot 已存在并且校验通过，也可以返回追踪数据。Trace 页面不能因为正式会话列表过滤规则而误拦截调试会话。

### 8.7 异常策略

1. sessionId 为空：直接返回 AgentForgeException 业务异常。
2. sessionId 不存在且没有 Trace 快照：返回“当前会话暂未生成可查看的链路追踪数据”。
3. snapshot_json 损坏：保留列表统计，单条快照标记为不可解析。
4. Collector 写入失败：记录日志并继续完成 Agent 请求。
5. QueryService 查询失败：返回明确业务错误，不返回原始 SQL 异常。

---

## 9. AgentForge Service UI 设计

### 9.1 页面入口

当前调试入口是：

~~~text
agentforge-service-ui/src/pages/agents/AgentDebugPanel.vue
~~~

建议新增 Trace 页面：

~~~text
agentforge-service-ui/src/pages/trace/AgentTracePreview.vue
agentforge-service-ui/src/pages/trace/AgentTraceSidebar.vue
agentforge-service-ui/src/pages/trace/AgentTraceWorkspace.vue
agentforge-service-ui/src/pages/trace/AgentTraceFlowView.vue
agentforge-service-ui/src/pages/trace/AgentTraceFlameView.vue
agentforge-service-ui/src/pages/trace/AgentTraceTimelineAxis.vue
agentforge-service-ui/src/pages/trace/api.js
agentforge-service-ui/src/pages/trace/token.js
~~~

页面定位不是替代 AgentDebugPanel，而是提供一次 Agent 运行的结构化观测视图。

### 9.2 页面布局

~~~text
AgentTracePreview
├── AgentTraceSidebar
│   ├── 会话信息
│   ├── questionRound 列表
│   └── token / 耗时 / 状态摘要
└── AgentTraceWorkspace
    ├── 标题、sessionId、当前轮次和操作区
    ├── 流程图模式
    └── 火焰图模式
~~~

左侧按照 questionRound 展示用户每次提问；右侧只展示当前选中的一次提问，避免整个 session 的多个问题混在一张图中。

### 9.3 流程图

流程图层级：

1. Root：当前 questionRound。
2. Step：每一轮 ReAct Loop。
3. AI：当前 step 的模型调用。
4. Tool：当前 step 中的工具调用。
5. Tool Result：工具执行返回。

节点详情至少包含：

1. 节点类型和状态。
2. 开始时间、结束时间和耗时。
3. 模型名称或模型配置标识。
4. token 使用量。
5. 工具名称、参数摘要和结果摘要。
6. 异常信息。

默认只展开 Root、Step、AI 和 Tool 四类有价值层级，工具参数与结果通过点击详情展示，不在画布中无限展开。

### 9.4 火焰图

火焰图的横轴表示当前 questionRound 的时间范围，纵向层级表示调用关系：

~~~text
Root：questionRound 总耗时
└── Step：单轮 Loop 耗时
    ├── AI：模型调用耗时
    └── Tool：工具执行耗时
~~~

并发工具应使用同一个时间轴并列绘制，而不是把并发耗时简单相加。汇总卡片可以同时展示：

1. wall clock 总耗时。
2. 模型耗时累计。
3. 工具耗时累计。
4. 并发工具累计耗时。

其中工具累计耗时可能大于 wall clock 总耗时，这不是数据错误，而是并发执行的自然结果。

火焰图交互：

1. 时间轴缩放。
2. 横向滚动。
3. 固定左侧节点标签。
4. 节点 hover 显示简要耗时。
5. 点击节点显示完整详情。
6. 适配视图和全屏。

### 9.5 前端数据组装

Trace 页面不直接把数据库快照数组交给图组件。建议在 AgentTracePreview 中完成：

1. 根据路由 sessionId 请求 TraceSession。
2. 计算 questionRound 列表。
3. 根据 selectedRound 找到当前轮次。
4. 遍历该轮 snapshots。
5. 以 messageKey、toolCallId 和 step 做去重合并。
6. 构造流程图节点和火焰图时间段。
7. 将统一 payload 交给 AgentTraceWorkspace。

这样图组件不需要理解多个快照如何拼接，也不需要读取 ai_agent_session_message.events。

### 9.6 实时监测

实时监测只刷新当前 TraceSession，不重置图组件状态：

1. 普通从会话历史进入时默认关闭。
2. 从 AgentDebugPanel 进入时默认开启。
3. 默认每 1 秒请求一次，可按页面负载调整为 1 至 2 秒。
4. 请求失败时保留上一次数据，并显示轻量提示。
5. Agent 完成、取消或异常后可以自动停止轮询。

页面应维护：

~~~text
lastRenderedRound
lastRenderedSignature
currentRoundPayload
~~~

签名至少包含：

1. questionRound。
2. latestStep。
3. stepCount。
4. finished。
5. status。
6. totalTokens。
7. snapshots 的 id、step、snapshotKind、status、modifyTime 和 totalTokens。

签名不变时，不重建流程图实例，避免用户拖拽、缩放或选中节点后被轮询刷新重置。

### 9.7 与 AgentDebugPanel 联动

AgentDebugPanel 当前已经维护：

1. aid。
2. sessionId。
3. isDebug。
4. SSE 事件实时展示。
5. sessionReplay 历史重放结构。

建议在调试面板增加“查看本次 Trace”入口，并携带：

~~~text
/trace-preview?sessionId=xxx&traceSource=debug&realtime=1
~~~

Trace 页面和 Debug 面板职责如下：

| 页面 | 主要关注 |
| --- | --- |
| AgentDebugPanel | 实时回答、思考片段、工具卡片、SSE 事件 |
| AgentTracePreview | Loop 结构、模型/工具耗时、token、流程和火焰图 |

两者使用同一个 sessionId，但不共享 Vue 内部状态，也不互相解析组件实例。

---

## 10. 数据一致性与性能策略

### 10.1 快照写入频率

不按 token 或 SSE 片段写数据库。推荐写入时机：

1. afterLoop：写入或更新当前 STEP 快照。
2. onStop：写入 ROUND_FINAL 快照。
3. onStopWithError：写入 ERROR 的 ROUND_FINAL 快照。

一个 questionRound 通常产生 stepCount + 1 条记录，远小于逐事件持久化。

### 10.2 幂等与重复回调

AgentForge 的同步、流式和异常路径可能在停止阶段触发不同回调组合，因此必须：

1. AgentTraceRuntime 使用 finished 原子标记保证最终快照只收口一次。
2. Collector 使用 session_id + question_round + step + snapshot_kind 幂等。
3. ToolRuntime 使用 toolCallId 防止工具完成事件重复计算。
4. 查询侧按 modify_time 选择最新版本，不依赖数据库返回顺序。

### 10.3 内存清理

AgentTraceRuntime 以 sessionId 为 key 时必须在以下路径清理：

1. 正常结束。
2. 用户取消。
3. 达到最大步数。
4. 模型异常。
5. 工具异常导致请求终止。
6. 流式客户端断开后的 Service 兜底。

建议增加定时清理兜底，清理超过固定 TTL 的运行态，防止极端情况下异常线程导致内存泄漏。

### 10.4 数据保留

Trace 快照可能包含用户问题、模型输出和工具结果，应增加：

1. 内容截断长度配置。
2. 敏感字段脱敏器。
3. 按 session 或 aid 的权限校验。
4. 调试 Trace 的单独保留周期。
5. 生产环境的清理任务或归档策略。

---

## 11. 方案取舍

### 11.1 为什么不复用 ai_agent_session_message.events 直接画图

events 是面向 Service UI 的 SSE 展示事件：

1. 事件顺序适合消息重放。
2. 事件中没有稳定的 step 层级协议。
3. 模型耗时、工具耗时和并发关系需要前端自行推断。
4. 不同事件到达顺序可能导致前端计算不稳定。

AgentTraceSnapshot 是面向观测的结构化协议，两者应并存。

### 11.2 为什么不把 Trace 放进 ChatMemory

ChatMemory 的职责是向下一次模型调用提供上下文：

1. Trace 元数据会增加模型上下文长度。
2. Trace 节点状态不是模型需要理解的业务消息。
3. ChatMemory 可能被窗口裁剪，而 Trace 需要完整保留。
4. 清空模型记忆不应删除用户仍然需要查看的追踪记录。

### 11.3 为什么一轮 Loop 一个 STEP 快照

AgentForge 的核心执行单位就是 ReAct Loop。按 Loop 记录可以同时满足：

1. 快速定位某一次模型调用。
2. 查看工具调用发生在哪一步。
3. 展示模型和工具的时间关系。
4. 支持实时页面逐步出现新 step。
5. 控制数据库记录数量。

### 11.4 为什么保留 ROUND_FINAL 快照

页面最常见的摘要问题是：

1. 这次提问最终是否成功。
2. 总耗时是多少。
3. 总 token 是多少。
4. 最终结果处于正常、取消、异常还是最大步数状态。

如果没有 ROUND_FINAL，查询层和前端每次都要累加所有 step，并自行处理异常和未完成状态。保留汇总快照可以降低查询复杂度，也使实时页面有稳定的终态。

---

## 12. 推荐落地顺序

### P0：Agent Core 协议与采集

1. 新增 AgentTraceCollector。
2. 新增 AgentTraceSnapshot、AgentTraceMessageSnapshot 和运行态对象。
3. 新增 AgentTraceMiddleware。
4. 覆盖同步 Agent.run 和流式 ReActTokenStream。
5. 增加并发工具、取消、异常和最大步数测试。

### P1：Service 持久化与查询

1. 在 agentforge-service/sql 增加 ai_agent_trace_snapshot。
2. 在请求初始化阶段分配 questionRound。
3. 在 ReActAgentFactory 注册 TraceMiddleware。
4. 新增 MysqlAgentTraceCollector。
5. 新增 Mapper、QueryService、VO 和 Controller。
6. 增加调试会话、正式会话和空会话查询测试。

### P2：Service UI

1. 新增 Trace API 封装。
2. 新增 TracePreview 页面和路由。
3. 新增 questionRound Sidebar。
4. 新增流程图。
5. 新增火焰图与时间轴。
6. 在 AgentDebugPanel 增加 Trace 跳转入口。
7. 增加轮询去重渲染和画布状态保持。

### P3：增强能力

1. TokenUsage 增加 cachedTokens 的统一抽象。
2. 支持 trace 标签、模型版本和工具来源。
3. 支持按 aid、时间范围和状态查询。
4. 支持异步 MQ、ES 或实时 WebSocket Collector。
5. 增加权限、脱敏和 Trace 生命周期清理。

---

## 13. 验收标准

### 13.1 Agent Core

1. 一次无工具请求产生 1 个 STEP 快照和 1 个 ROUND_FINAL 快照。
2. 一次包含两个工具 Loop 的请求产生对应的多个 STEP 快照。
3. 并发工具的节点时间互不覆盖。
4. 模型异常、工具异常、用户取消和 maxSteps 都有正确终态。
5. Trace Collector 异常不会导致 Agent 请求失败。
6. 同步和流式链路生成的 Trace 结构保持一致。

### 13.2 Service

1. Trace 快照能够关联 aid、sessionId 和 questionRound。
2. 同一快照重复回调不会产生重复数据。
3. ai_agent_session_message.events 仍能正常用于历史重放。
4. Debug session 即使不出现在正式会话列表，也能查询 Trace。
5. 最终汇总快照可直接返回总耗时和 token。

### 13.3 Service UI

1. 可按 questionRound 选择一次用户提问。
2. 流程图能展示 Root、Step、AI、Tool 和 Tool Result。
3. 火焰图能展示模型、工具和并发工具耗时。
4. 轮询数据不变时，画布位置和缩放不重置。
5. AgentDebugPanel 可跳转到当前 session 的 Trace 页面。
6. 快照缺失或部分损坏时，页面仍能展示可用摘要并给出提示。

---

## 14. 最终结论

AgentForge 的链路追踪应理解为 Agent Runtime 的一条观测旁路：

1. agentforge-agent-core 通过已有 Middleware 生命周期采集 Agent 运行事实。
2. agentforge-service-web 负责 questionRound、会话关联、MySQL 快照和查询聚合。
3. agentforge-service-ui 负责按问答轮次展示流程图、火焰图和实时监测。
4. ai_agent_session、ai_agent_session_message、ai_agent_memrory 与 ai_agent_trace_snapshot 各自承担不同职责。
5. AgentTraceSnapshot 以一次 ReAct Loop 为 step，以一次用户请求为 questionRound，以 ROUND_FINAL 作为稳定汇总。

最终对 AgentForge 业务层而言，Trace 的接入要求应保持简单：

1. Service 在请求初始化时提供 sessionId、aid、questionRound 和调试态扩展信息。
2. ReActAgentFactory 自动注册 AgentTraceMiddleware。
3. 业务只需决定 AgentTraceSnapshot 的存储、查询和展示方式。

这套设计能够完整贴合 AgentForge 当前的 Agent、Middleware、ChatMemory、Session、SSE 和 Vue 页面结构，同时为后续多模型、多工具、异步 Collector 和更复杂的观测能力保留扩展空间。

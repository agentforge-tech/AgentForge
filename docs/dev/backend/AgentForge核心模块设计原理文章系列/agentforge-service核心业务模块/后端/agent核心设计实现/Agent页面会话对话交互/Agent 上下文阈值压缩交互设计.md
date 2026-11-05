# Agent 上下文阈值压缩交互设计（agentforge-service）

> 文档定位：从 `agentforge-service` 业务层视角，说明如何把 Core 的上下文压缩能力转换为可观测、可回放的会话事件，以及前端如何区分调试态与发布态进行交互展示。
>
> 核心代码：
>
> - 业务适配：`agentforge-service/agentforge-service-web/src/main/java/cloud/changlu/agentforge/service/modules/agent/middleware/compact/`
> - 事件协议：`.../service/protocol/CompactResult.java`、`.../service/protocol/processor/compact/AiCompactProcessor.java`
> - 前端展示：`agentforge-service-ui/src/components/CompactNotice.vue`、`src/utils/compactEventReducer.js`
>
> Core 压缩算法本身见：[内置middlware扩展：上下文压缩middlware（阈值比例压缩机制）](../../../../02、通用ReActagent层/middleware/内置middlware扩展：上下文压缩middlware（阈值比例压缩机制）.md)

---

## 一、背景与设计目标

Core 层的 `CompactSummarizationMiddleware` 只负责“何时压缩、压缩什么、保留什么”，并通过 `CompactLifecycleHook` 暴露压缩前后两个钩子。它不关心 SSE、数据库或前端展示。

Service 层需要解决的是另一组问题：

1. **过程可见**：用户在长会话中需要知道“上下文正在压缩 / 已压缩 / 压缩失败”，而不是无反馈地等待。
2. **调试可观测**：开发调试时需要看到触发来源、前后 Token、使用率、节省比例、耗时等指标。
3. **发布态克制**：面向终端用户时，不应暴露上下文规模等内部细节，只保留状态和耗时。
4. **历史可回放**：刷新页面或重新打开历史会话时，压缩提示应与实时展示保持一致。
5. **不侵入 Core**：业务差异全部收敛在 Service 适配层，Core 算法保持稳定。

因此，Service 层的职责是：**订阅 Core 压缩钩子 → 构造统一事件 → 同时写入实时 SSE 与会话事件存储 → 前端按模式渲染**。

---

## 二、分层架构

```text
┌───────────────────────────────────────────────────────────────┐
│ Core: CompactSummarizationMiddleware                          │
│  ├─ beforeCompact(...)  ── CompactLifecycleHook               │
│  └─ afterCompact(event) ── CompactLifecycleHook               │
└───────────────────────────────┬───────────────────────────────┘
                                │ 继承 + 重写钩子
                                ▼
┌───────────────────────────────────────────────────────────────┐
│ Service: ServiceCompactSummarizationMiddleware                │
│  ├─ 构造 CompactResult                                        │
│  └─ publish(...)                                              │
│       ├─ ServiceStreamingMiddleware.publish  → 实时 SSE       │
│       └─ SessionLifecycleMiddleware.collectEvent → 事件持久化 │
└───────────────────────────────┬───────────────────────────────┘
                                │ StreamEventResponse
                                ▼
┌───────────────────────────────────────────────────────────────┐
│ 前端                                                          │
│  ├─ reduceCompactEvent(answer, event, detailed)               │
│  └─ CompactNotice.vue                                         │
│       ├─ 调试模式：压缩前 / 压缩后两条 + 完整指标             │
│       └─ 发布模式：合并为一条 + 状态与耗时                    │
└───────────────────────────────────────────────────────────────┘
```

---

## 三、核心代码与扩展点

### 3.1 ServiceCompactSummarizationMiddleware

该类继承 Core 的 `CompactSummarizationMiddleware`，只重写两个生命周期钩子，不修改压缩算法。

```java
public class ServiceCompactSummarizationMiddleware
        extends CompactSummarizationMiddleware {

    private final ServiceStreamingMiddleware streamingMiddleware;
    private final SessionLifecycleMiddleware sessionLifecycleMiddleware;
    private final boolean detailed;

    @Override
    public void beforeCompact(
            int currentStep,
            CompactTrigger trigger,
            int beforeMessages,
            int beforeTokens,
            int maxContextTokens,
            AgentChatContext chatContext) {
        // 构造“正在压缩”事件
    }

    @Override
    public void afterCompact(CompactEvent event, AgentChatContext chatContext) {
        // 构造“压缩完成 / 压缩失败”事件
    }
}
```

职责边界：

| 职责 | 说明 |
| --- | --- |
| 事件构造 | 把 Core 的 `CompactTrigger`、`CompactEvent` 转为 Service 的 `CompactResult` |
| 详略控制 | `detailed` 决定是否填充调试指标 |
| 双通道发布 | 同时写实时 SSE 与会话事件存储 |
| 空值保护 | `chatContext == null` 时只跳过发布，不影响压缩主流程 |

关键设计点：

- **构造参数中的 `detailed` 来自会话调试态**，在 `ReActAgentFactory` 中直接传入 `isDebug`。
- **不重写 `beforeModelCall` / `afterToolExecution` / `afterConcurrentToolExecution`**，避免业务代码与压缩算法耦合。
- Core 对钩子异常做了保护，Service 即使发布失败也不会阻断 Agent 执行。

### 3.2 实时推送：ServiceStreamingMiddleware.publish

`publish(memoryId, event)` 根据 `memoryId` 找到本次请求绑定的 SSE 输出上下文，并串行写出：

```text
publish(memoryId, event)
  └─ sessions.get(memoryId)
       └─ SessionContext.send(event)
            └─ emitter.send(SseEmitter.event().data(event))
```

特点：

- 相同 `memoryId` 的多个运行实例会覆盖旧 `SessionContext`，旧连接被关闭。
- `send` 方法加锁，避免压缩事件与其他流式事件并发写出时乱序。
- 客户端断开或 `SseEmitter` 已关闭时，异常被吞掉，不影响主流程。

### 3.3 事件收集与历史回放：SessionLifecycleMiddleware.collectEvent

压缩事件不仅推给实时流，还要进入会话事件列表，供历史消息回放：

```java
public void collectEvent(Object memoryId, StreamEventResponse event) {
    RunState state = memoryId == null ? null : states.get(memoryId);
    if (state != null && event != null) {
        state.collect(event, true);
    }
}
```

- `flush = true`：压缩事件属于低频关键事件，立即把事件 JSON 快照写入 AI 消息的 `events` 字段。
- 运行结束时，`onStop` / `onStopWithError` 会一次性落库完整 `content`、`events` 与终态。
- 前端通过 `sessionReplay` 读取同一份 `events`，因此实时与历史展示语义一致。

### 3.4 事件构造：AiCompactProcessor

所有压缩事件统一使用：

```text
event = "compact"
```

并通过 `type` 区分阶段：

| type | title | 含义 |
| --- | --- | --- |
| `compacting` | 正在压缩上下文 | 压缩开始前 |
| `compacted` | 上下文已压缩 | 压缩成功后 |
| `compact_failed` | 上下文压缩失败 | 压缩失败后 |

`StreamEventResponse` 自身使用 `@JsonInclude(NON_NULL)`，不会输出空的顶层字段。

### 3.5 装配：ReActAgentFactory

压缩 Middleware 在构建 Agent 时注册，`maxContextTokens` 来自模型配置：

```java
CompactSummarizationMiddleware compact =
        new ServiceCompactSummarizationMiddleware(
                CompactSummarizationMiddleware.CompactConfig.builder()
                        .maxContextTokens(
                                toActualTokens(
                                        chatModelConfig.getMaxInputKTokens(),
                                        modelAdvancedConfig.getMaxInputTokens()))
                        .build(),
                streamingMiddleware,
                sessionLifecycleMiddleware,
                isDebug);

List<IAgentMiddleware> middlewares = Arrays.asList(
        userMessageAppendMiddleware,
        compact,
        new LoggingIAgentMiddleware(),
        sessionLifecycleMiddleware,
        streamingMiddleware,
        agentTraceMiddleware);
```

注册顺序原则：

1. 先执行会改变本轮输入的消息追加 Middleware。
2. 再执行上下文压缩，使其看到最终候选消息。
3. 最后执行日志、流式与 Trace。

---

## 四、事件协议与参数

### 4.1 事件外层结构

```json
{
  "event": "compact",
  "type": "compacting | compacted | compact_failed",
  "title": "正在压缩上下文 | 上下文已压缩 | 上下文压缩失败",
  "content": { "……CompactResult……" },
  "isFinish": false
}
```

### 4.2 CompactResult 字段说明

| 字段 | 类型 | 调试模式 | 发布模式 | 说明 |
| --- | --- | --- | --- | --- |
| `success` | Boolean | 有值 | 有值 | 仅结束后事件有值；开始时为 null |
| `status` | String | 有值 | 有值 | `compacting` / `compacted` / `compact_failed` |
| `currentStep` | Integer | 有值 | null | 当前 ReAct 步骤；工具触发时为 `-1` |
| `trigger` | String | 有值 | 有值 | `model_call` / `tool_result` / `concurrent_tool_result` |
| `duration` | Long | 有值 | 有值 | 压缩耗时，毫秒 |
| `beforeMessages` | Integer | 有值 | null | 压缩前消息数 |
| `afterMessages` | Integer | 有值 | null | 压缩后消息数 |
| `beforeTokens` | Integer | 有值 | null | 压缩前估算 Token |
| `afterTokens` | Integer | 有值 | null | 压缩后估算 Token |
| `maxContextTokens` | Integer | 有值 | null | 模型最大上下文 Token |
| `beforeUsagePercent` | Double | 有值 | null | 压缩前使用率 |
| `afterUsagePercent` | Double | 有值 | null | 压缩后使用率 |
| `savedTokens` | Integer | 有值 | null | 节省 Token |
| `savedPercent` | Double | 有值 | null | 节省比例 |
| `triggerDescription` | String | 有值 | null | 触发来源中文描述 |
| `errorMessage` | String | 失败时有值 | 失败时有值 | 失败原因 |

**注意**：发布模式下未赋值的字段为 `null`，由前端按 `detailed` 决定是否展示，而不是依赖后端删除字段。

### 4.3 触发来源对照

| `trigger` | 中文描述 | 触发位置 |
| --- | --- | --- |
| `model_call` | 模型调用前压缩 | `beforeModelCall` 历史摘要 |
| `tool_result` | 单工具结果截断 | `afterToolExecution` |
| `concurrent_tool_result` | 并发工具结果截断 | `afterConcurrentToolExecution` |

### 4.4 调试模式参数示例

单工具 / 并发工具截断场景：

```text
data:{"event":"compact","type":"compacting","title":"正在压缩上下文","content":{"success":null,"status":"compacting","currentStep":-1,"trigger":"concurrent_tool_result","duration":null,"beforeMessages":9,"afterMessages":null,"beforeTokens":9180,"afterTokens":null,"maxContextTokens":10000,"beforeUsagePercent":91.8,"afterUsagePercent":null,"savedTokens":null,"savedPercent":null,"triggerDescription":"并发工具结果截断","errorMessage":null},"isFinish":false}

data:{"event":"compact","type":"compacted","title":"上下文已压缩","content":{"success":true,"status":"compacted","currentStep":-1,"trigger":"concurrent_tool_result","duration":19,"beforeMessages":9,"afterMessages":9,"beforeTokens":9180,"afterTokens":7263,"maxContextTokens":10000,"beforeUsagePercent":91.8,"afterUsagePercent":72.63,"savedTokens":1917,"savedPercent":20.88,"triggerDescription":"并发工具结果截断","errorMessage":null},"isFinish":false}
```

模型调用前压缩场景：

```text
data:{"event":"compact","type":"compacting","title":"正在压缩上下文","content":{"success":null,"status":"compacting","currentStep":3,"trigger":"model_call","duration":null,"beforeMessages":42,"afterMessages":null,"beforeTokens":8500,"afterTokens":null,"maxContextTokens":10000,"beforeUsagePercent":85.0,"triggerDescription":"模型调用前压缩","errorMessage":null},"isFinish":false}

data:{"event":"compact","type":"compacted","title":"上下文已压缩","content":{"success":true,"status":"compacted","currentStep":3,"trigger":"model_call","duration":2680,"beforeMessages":42,"afterMessages":12,"beforeTokens":8500,"afterTokens":4100,"maxContextTokens":10000,"beforeUsagePercent":85.0,"afterUsagePercent":41.0,"savedTokens":4400,"savedPercent":51.76,"triggerDescription":"模型调用前压缩","errorMessage":null},"isFinish":false}
```

失败场景：

```text
data:{"event":"compact","type":"compact_failed","title":"上下文压缩失败","content":{"success":false,"status":"compact_failed","currentStep":3,"trigger":"model_call","duration":1201,"beforeMessages":42,"afterMessages":42,"beforeTokens":8500,"afterTokens":8500,"maxContextTokens":10000,"beforeUsagePercent":85.0,"afterUsagePercent":85.0,"savedTokens":0,"savedPercent":0.0,"triggerDescription":"模型调用前压缩","errorMessage":"ChatModel is null, cannot generate summary"},"isFinish":false}
```

**注意**：工具截断只改变工具结果内容，不改变消息条数，因此 `beforeMessages` 与 `afterMessages` 相等。

### 4.5 发布模式参数示例

发布模式只填充状态、触发来源、耗时与失败原因，其余字段为 `null`：

```json
{
  "event": "compact",
  "type": "compacted",
  "title": "上下文已压缩",
  "content": {
    "success": true,
    "status": "compacted",
    "currentStep": null,
    "trigger": "concurrent_tool_result",
    "duration": 19,
    "beforeMessages": null,
    "afterMessages": null,
    "beforeTokens": null,
    "afterTokens": null,
    "maxContextTokens": null,
    "beforeUsagePercent": null,
    "afterUsagePercent": null,
    "savedTokens": null,
    "savedPercent": null,
    "triggerDescription": null,
    "errorMessage": null
  },
  "isFinish": false
}
```

### 4.6 事件时序

```text
模型调用前压缩：
  compacting(currentStep=N, trigger=model_call)
        │  摘要生成 / Memory 替换
        ▼
  compacted(currentStep=N, beforeMessages>afterMessages)

工具结果截断：
  compacting(currentStep=-1, trigger=tool_result|concurrent_tool_result)
        │  截断单个 / 并发工具结果
        ▼
  compacted(currentStep=-1, beforeMessages=afterMessages)

失败：
  compacting(...)
        │  摘要或替换异常
        ▼
  compact_failed(errorMessage=...)
```

---

## 五、前端交互设计

### 5.1 事件归并：reduceCompactEvent

前端不直接渲染原始事件，而是先把事件归并为回答中的一个 `compact_notice` part。

```js
reduceCompactEvent(answer, event, detailed)
```

归并规则：

| 模式 | 行为 |
| --- | --- |
| 发布模式（`detailed = false`） | 查找同一 `trigger` 且 `status = compacting` 的 part，把结束事件合并进去，最终只保留一条 |
| 调试模式（`detailed = true`） | 压缩前、压缩后各保留一条独立 part；结束事件到达时，把前一条标记为 `finished = true` |

调试模式下会先把旧的压缩中 part 标记完成：

```text
compacting 到达 → 新建 part(status=compacting)
compacted  到达 → 旧 part.finished = true
                → 新建 part(status=compacted)
```

因此调试模式能看到“压缩前 → 压缩后”的完整前后对照。

### 5.2 CompactNotice 展示规则

`CompactNotice.vue` 接收两个属性：

```text
part     : compact_notice 数据
detailed : 是否调试模式
```

展示差异：

| 状态 | 调试模式标题 | 调试模式详情 | 发布模式标题 | 发布模式详情 |
| --- | --- | --- | --- | --- |
| `compacting`（进行中） | 当前上下文大小 9.2K tokens，已触发压缩阈值，正在压缩... | 当前 9.2K / 10K tokens · 占比 91.8% · 并发工具结果触发 | 正在压缩上下文... | 空 |
| `compacting`（已完成） | 压缩前上下文 | 当前 9.2K / 10K tokens · 占比 91.8% · 并发工具结果触发 | — | — |
| `compacted` | 上下文阈值压缩完成 | 上下文 9.2K → 7.3K tokens · 节省 1.9K tokens (20.9%) · 占比 91.8% → 72.6% · 消息 9 → 9 条 · 耗时 19 ms | 上下文阈值压缩完成 | 耗时 19 ms |
| `compact_failed` | 上下文阈值压缩失败 | 当前 8.5K tokens · 上下文上限 10K tokens · 错误信息 | 上下文压缩失败 | 错误信息 |

### 5.3 Token 单位约定

前端统一使用 K/M 单位展示，保留一位小数并去掉 `.0`：

```text
9180   → 9.2K
7263   → 7.3K
10000  → 10K
1000000 → 1M
小于 1000：按整数展示
```

同时展示：

- 压缩前后上下文 Token；
- 节省 Token 与节省比例；
- 压缩前后使用率；
- 消息条数变化；
- 耗时。

### 5.4 调试模式交互效果

```text
执行过程
├─ 思考片段...
├─ 工具调用卡片...
├─ 压缩前上下文     当前 9.2K / 10K tokens · 占比 91.8% · 并发工具结果触发
├─ 上下文阈值压缩完成  上下文 9.2K → 7.3K tokens · 节省 1.9K tokens (20.9%)
│                    · 占比 91.8% → 72.6% · 消息 9 → 9 条 · 耗时 19 ms
└─ 最终回答...
```

压缩提示与 think、tool_card 一起挂在“执行过程时间线”内：

- `DebugMessage.vue` 的 `processParts` 包含 `compact_notice`，并传入 `detailed`。
- `ElegantMessage.vue` 的 `thinkParts` 也包含 `compact_notice`，但不传 `detailed`，使用精简展示。

### 5.5 发布模式交互效果

```text
执行过程
├─ 工具调用卡片...
├─ 上下文阈值压缩完成  耗时 19 ms
└─ 最终回答...
```

发布模式只保留一条合并后的记录，不展示 Token、消息数、使用率等内部指标。

### 5.6 历史回放

历史消息的事件来自 AI 消息的 `events` 字段。回放时按当前页面模式传入 `detailed`：

```js
// 调试面板
const replay = replaySession(records, this.mode === 'debug')

// 发布页
const replay = replaySession(records)
```

因此：

- 同一份历史数据，在调试模式下还原出“压缩前 / 压缩后”两条；
- 在发布模式下还原为一条状态记录；
- 不依赖后端为不同模式存储两套事件。

---

## 六、业务层扩展指南

### 6.1 新增展示字段

1. 在 Core `CompactEvent` 中补充原始指标。
2. 在 Service `CompactResult` 中增加字段。
3. 在 `ServiceCompactSummarizationMiddleware.afterCompact` 的 `detailed` 分支中赋值。
4. 前端 `CompactNotice.vue` 读取新字段并展示。

**原则**：原始数值字段与展示文案分离，后端只提供数值和描述，格式化交给前端。

### 6.2 新增触发来源

1. 在 Core `CompactTrigger` 中增加枚举值与 `code`、`description`。
2. 在对应压缩入口调用 `invokeBeforeCompact` / `completeCompact` 时传入新枚举。
3. 前端 `triggerLabel` 增加对应中文映射。

### 6.3 新增事件消费方

除 SSE 和会话事件存储外，还可以扩展：

- 审计日志：在 `publish` 中追加审计写入。
- 监控指标：统计压缩次数、失败率、平均节省 Token。
- 消息队列：异步投递压缩事件做离线分析。

**注意**：新增消费方应放在 `publish` 的旁路逻辑中，并自行捕获异常，不能影响 SSE 主链路。

### 6.4 扩展注意事项

- 不要为了展示而修改 Core 压缩算法。
- 不要在发布模式中输出 Token、消息数等内部细节。
- 不要在日志中打印完整用户内容、工具结果或摘要正文。
- 钩子异常已由 Core 保护，但仍应避免在钩子中执行阻塞操作。
- 新增字段保持向后兼容：前端对缺失字段应降级展示，而不是报错。

---

## 七、异常与降级

| 场景 | 行为 |
| --- | --- |
| 摘要生成失败 | Core 返回原请求，Service 收到 `CompactEvent.failure`，发布 `compact_failed` |
| Memory 替换失败 | Core 回滚到原始消息，发布 `compact_failed` |
| 钩子内部异常 | Core 捕获并记录 WARNING，不影响压缩主流程 |
| SSE 已关闭 | `SessionContext.send` 忽略异常，事件仍会进入会话事件存储 |
| `chatContext == null` | 跳过发布，不抛异常 |
| `streamingMiddleware == null` | 构造时抛出 `IllegalArgumentException` |

---

## 八、测试建议

### 8.1 后端

- 调试模式：`beforeCompact` 输出完整指标，`afterCompact` 输出前后 Token、使用率、节省比例。
- 发布模式：仅输出状态、触发来源、耗时；不填充调试字段。
- 工具截断：`beforeMessages == afterMessages`，`trigger` 正确。
- 模型调用压缩：`afterMessages < beforeMessages`。
- 失败：`compact_failed` 携带 `errorMessage`。
- 事件同时进入 SSE 与会话事件列表。

### 8.2 前端

- `reduceCompactEvent` 发布模式合并为一条。
- `reduceCompactEvent` 调试模式保留两条，并标记前一条已完成。
- `CompactNotice` 在各状态下标题与详情正确。
- K/M 单位格式化在小数、整数、百万级下结果正确。
- 历史回放按 `detailed` 还原为一条或两条。

---

## 九、代码索引

| 能力 | 代码位置 |
| --- | --- |
| Core 压缩算法与钩子 | `agentforge-agent-core/.../extend/middlewares/compact/` |
| Service 事件适配 | `agentforge-service-web/.../agent/middleware/compact/ServiceCompactSummarizationMiddleware.java` |
| 实时 SSE | `agentforge-service-web/.../agent/middleware/ServiceStreamingMiddleware.java` |
| 事件持久化与回放 | `agentforge-service-web/.../agent/middleware/SessionLifecycleMiddleware.java` |
| 事件协议 | `agentforge-service-web/.../service/protocol/CompactResult.java` |
| 事件构造 | `agentforge-service-web/.../service/protocol/processor/compact/AiCompactProcessor.java` |
| 事件枚举 | `agentforge-service-web/.../service/protocol/enums/StreamEventEnums.java` |
| 装配 | `agentforge-service-web/.../agent/runtime/mode/react/ReActAgentFactory.java` |
| 前端归并 | `agentforge-service-ui/src/utils/compactEventReducer.js` |
| 前端展示 | `agentforge-service-ui/src/components/CompactNotice.vue` |
| 历史回放 | `agentforge-service-ui/src/utils/sessionReplay.js` |

---

整理者：长路 创建时间：2026.11.6 更新时间：2026.11.6

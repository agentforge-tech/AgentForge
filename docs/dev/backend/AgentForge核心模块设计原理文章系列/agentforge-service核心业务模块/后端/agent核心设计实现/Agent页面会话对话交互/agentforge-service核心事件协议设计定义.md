# AgentForge Service 核心事件协议设计定义

## 一、背景

AgentForge 的发布态对话是一条 SSE 长连接：Agent 在 ReAct 循环里不断产生思考文本、正文增量、工具调用卡片，最终以正常结束或异常结束收口。前端需要把这些过程区分开来渲染，历史会话又需要把同一轮过程重新播放出来。

如果这段过程没有统一协议，通常会出现几个问题：

- 前端靠字符串前缀或字段猜测事件语义，渲染逻辑散落在页面里；
- 实时推送与历史重放各写一套结构，两边逐渐漂移；
- 新增一种事件（例如工作流步骤）时，前端要改解析、后端要改拼接，没有统一约束点；
- 异常、结束、会话创建这些边界语义靠“约定”，没有代码层面的校验。

AgentForge Service 的解法是把事件抽象成一组显式枚举与统一载体，集中放在：

```text
agentforge-service/agentforge-service-web/src/main/java/cloud/changlu/agentforge/service/protocol
├── StreamEventResponse.java          # 统一事件载体
├── ToolCallResult.java               # 工具卡片载荷
├── enums
│   └── StreamEventEnums.java         # event × type 枚举与组合约束
└── processor
    ├── IStreamSceneProcessor.java    # 场景处理器接口
    ├── AiSessionProcessor.java       # session/created
    ├── AiThinkProcessor.java         # think/text（think 子包）
    ├── AiResponseProcessor.java      # resp/text、resp_end
    ├── AiToolCallProcessor.java      # tool_call/tool_card（tool 子包）
    └── AiErrorProcessor.java         # error/text
```

本文按“枚举契约 → 统一载体 → 场景处理器 → 真实运行链路 → 设计取舍与边界”的顺序展开，尽量对齐仓库里的实际实现，而不是只讲理想模型。

## 二、当前阶段全量事件类型清单

在展开枚举约束与处理器实现之前，先把当前阶段所有事件完整列出来。后续章节讨论的“为什么这样设计”，都可以回到这张清单上对照。

### 2.1 全量事件清单（event → type）

下表中 **event 列只在每个事件的首行出现**，`〃` 表示沿用上一行的 event。这样一个 event 右侧可以挂多个 type 形态，同时不会出现重复的 event。

| event | type | 示范 | 含义 |
| --- | --- | --- | --- |
| `session` | `created` | `{"event":"session","type":"created","content":"<sessionId>","isFinish":false}` | 会话创建：下发 sessionId，`isFinish=false`，前端新会话时缓存本地 ID |
| `think` | `text` | `{"event":"think","type":"text","content":"我先确认","isFinish":false}` | AI 思考增量：追加到思考块，当前链路中 `isFinish` 恒为 `false` |
| `resp` | `text` | `{"event":"resp","type":"text","content":"根据检索结果","isFinish":false}` | AI 回答增量：按到达顺序拼接正文，同时也是最终 `content` 的来源 |
| `tool_call` | `tool_card`（执行中） | `{"event":"tool_call","type":"tool_card","content":{"toolCallId":"call_1","toolName":"web_search","description":"工具执行中","inputParams":"{...}","outputResult":null,"duration":null},"isFinish":false}` | 工具开始执行：前端按 `toolCallId` 插入卡片骨架 |
| 〃 | `tool_card`（完成） | `{"event":"tool_call","type":"tool_card","content":{"toolCallId":"call_1","toolName":"web_search","description":"工具执行完成","inputParams":"{...}","outputResult":"...","duration":1832},"isFinish":true}` | 工具执行完成：携带结果与耗时，前端按 `toolCallId` 原位更新 |
| 〃 | `tool_card`（失败） | `{"event":"tool_call","type":"tool_card","content":{"toolCallId":"call_1","toolName":"web_search","description":"工具执行失败","inputParams":"{...}","outputResult":"<异常信息>","duration":1832},"isFinish":true}` | 工具执行失败：结构同完成帧，`outputResult` 放异常信息，前端渲染失败态 |
| `resp_end` | 无（不传 type） | `{"event":"resp_end","isFinish":true}` | 本轮正常结束：无载荷、无 type，前端停止 loading 并刷新会话列表 |
| `error` | `text` | `{"event":"error","type":"text","content":"<错误信息>","isFinish":true}` | 本轮异常结束：结构与 `resp/text` 一致，前端切换为错误气泡 |

补充说明：

- `tool_call` 的 `type` 字符串始终是 `tool_card`，括号里的“执行中 / 完成 / 失败”是**同一 type 下的生命周期阶段**，不是三个不同的 type。前端必须按 `toolCallId` 合并，不能当成三种事件分别渲染。
- 上表覆盖了当前 `StreamEventEnums` 中全部 6 个 event 的所有合法 type 组合，没有其他未列出的组合。
- 写入 `events` 的只有 `think`、`resp`、`tool_call` 三类。`session` 只在实时阶段有用；`resp_end` 可由消息 `status=5` 推导；`error` 只影响消息 `status=3` 与兜底 `content`，不进入事件数组。
- `StreamEventResponse.title` 是预留字段，当前阶段没有任何处理器写入，因此线上报文里不会出现。

### 2.2 type 维度汇总

| type | code | 载荷类型 | 当前绑定的 event | 当前用途 | 可能的扩展方向 |
| --- | --- | --- | --- | --- | --- |
| `CREATED` | `created` | `String` | `session` | 下发会话 ID | 会话元信息对象（标题、模型） |
| `TEXT` | `text` | `String` | `think`、`resp`、`error` | 纯文本增量/错误信息 | Markdown 片段、结构化错误码 |
| `TOOL_CARD` | `tool_card` | `ToolCallResult` | `tool_call` | 工具卡片快照 | 差异对比、多结果卡片 |

type 的数量被刻意控制在三个。新增 type 意味着前端要多一种渲染分支，因此只有载荷形态真的不同才值得新增；如果只是内容含义不同，优先复用现有 type、新增 event。

### 2.3 event × type 组合矩阵

| event \ type | `created` | `text` | `tool_card` | 无 type |
| --- | --- | --- | --- | --- |
| `session` | ✓ | — | — | — |
| `think` | — | ✓ | — | — |
| `resp` | — | ✓ | — | — |
| `tool_call` | — | — | ✓ | — |
| `resp_end` | — | — | — | ✓ |
| `error` | — | ✓ | — | — |

这张矩阵就是 `StreamEventEnums` 里每个 `EventEnum` 构造参数展开后的结果。打 ✓ 的组合可以构造，打 — 的组合会在 `StreamEventResponse.of(...)` 阶段直接抛 `IllegalArgumentException`。

### 2.4 报文速查

| event / type | 最小报文 |
| --- | --- |
| `session/created` | `{"event":"session","type":"created","content":"<sessionId>","isFinish":false}` |
| `think/text` | `{"event":"think","type":"text","content":"<增量>","isFinish":false}` |
| `resp/text` | `{"event":"resp","type":"text","content":"<增量>","isFinish":false}` |
| `tool_call/tool_card`（开始） | `{"event":"tool_call","type":"tool_card","content":{"toolCallId":"...","toolName":"...","description":"工具执行中","inputParams":"...","outputResult":null,"duration":null},"isFinish":false}` |
| `tool_call/tool_card`（完成） | `{"event":"tool_call","type":"tool_card","content":{"toolCallId":"...","toolName":"...","description":"工具执行完成","inputParams":"...","outputResult":"...","duration":1832},"isFinish":true}` |
| `resp_end` | `{"event":"resp_end","isFinish":true}` |
| `error/text` | `{"event":"error","type":"text","content":"<错误信息>","isFinish":true}` |

### 2.5 协议全景与分层

协议在系统中的位置可以概括为一句话：**中间件只负责“什么时候发”，协议层负责“发什么、长什么样”，传输层只负责“怎么送出去”。**

```text
Agent 生命周期回调（onInitComplete / onPartialThinking / onPartialResponse /
                    beforeToolExecution / afterToolExecution / onStop ...）
        │
        ▼
场景处理器 Processor（AiSessionProcessor / AiThinkProcessor / ...）
        │  构造统一的 StreamEventResponse
        ▼
StreamEventEnums（event + type + isFinish 语义约束）
        │
        ├── ServiceStreamingMiddleware ──► SseEmitter ──► 前端实时渲染
        └── SessionLifecycleMiddleware ──► ai_agent_session_message.events ──► 历史重放
```

几个关键事实：

1. `protocol` 包**不依赖 Spring、不依赖 `SseEmitter`**。协议对象是纯 POJO，谁都可以构造、序列化、持久化。
2. `ServiceStreamingMiddleware` 是唯一把事件写进 `SseEmitter` 的地方；`SessionLifecycleMiddleware` 是唯一把事件写进数据库 `events` 的地方。两者构造的是同一种对象。
3. 事件组合的合法性由 `StreamEventEnums` 在**构造时**校验，而不是靠文档约定。

## 三、事件枚举：event × type 的二维模型

### 3.1 为什么拆成 event 和 type

一个流式事件至少携带两类信息：

- **阶段语义**：这是会话、思考、正文、工具调用、结束还是异常；
- **呈现形态**：同一阶段可能以不同形态出现。例如“正文”可以是普通文本，未来也可能是 Markdown 片段、表格或引用块；“工具调用”当前是卡片，未来可能是差异对比或其他形态。

如果只用一个扁平枚举，`think_text`、`resp_text`、`error_text` 这类组合会迅速膨胀；如果只用 type，又会丢失“谁产生的”这一层阶段信息。AgentForge 选择二维模型：`event` 表达阶段，`type` 表达形态。

### 3.2 EventEnum 定义

```java
public enum EventEnum {
    SESSION("session", "会话事件", TypeEnum.CREATED),
    THINK("think", "AI思考", TypeEnum.TEXT),
    RESP("resp", "AI回答事件", TypeEnum.TEXT),
    TOOL_CALL("tool_call", "工具调用事件", TypeEnum.TOOL_CARD),
    RESP_END("resp_end", "AI回答结束"),
    ERROR("error", "异常事件", TypeEnum.TEXT);
    ...
}
```

| 枚举 | code | 含义 | 允许的 type | 是否带 type |
| --- | --- | --- | --- | --- |
| `SESSION` | `session` | 会话事件 | `created` | 是 |
| `THINK` | `think` | AI 思考增量 | `text` | 是 |
| `RESP` | `resp` | AI 回答增量 | `text` | 是 |
| `TOOL_CALL` | `tool_call` | 工具调用 | `tool_card` | 是 |
| `RESP_END` | `resp_end` | 本轮回答结束 | 无 | 否 |
| `ERROR` | `error` | 异常 | `text` | 是 |

注意最后两行的差异：`resp_end` 是一个**纯控制事件**，它不携带内容，也不需要形态区分，因此构造时只接受 `EventEnum` 本身，不接受 type。这不是遗漏，而是枚举构造器变长参数的自然表达——不传 type 就是空集合。

### 3.3 TypeEnum 定义

```java
public enum TypeEnum {
    CREATED("created", "会话创建"),
    TEXT("text", "文本内容"),
    TOOL_CARD("tool_card", "工具调用卡片");
}
```

type 只描述“内容怎么解释”，不描述“内容从哪来”。`created` 与 `text` 的载荷是字符串，`tool_card` 的载荷是 `ToolCallResult` 对象。载荷类型的差异由处理器封装，不进入枚举。

### 3.4 组合约束：让枚举自己声明白名单

真正的设计点在这里：`EventEnum` 在构造时就把“我允许哪些 type”固化下来，而不是在外部用 `switch` 或 `Map` 维护。

```java
private final Set<TypeEnum> types;

EventEnum(String code, String msg, TypeEnum... types) {
    this.code = code;
    this.msg = msg;
    this.types = types.length == 0
            ? Collections.emptySet()
            : Collections.unmodifiableSet(EnumSet.copyOf(Arrays.asList(types)));
}

public boolean supports(TypeEnum type) {
    return types.contains(type);
}
```

细节值得逐条看：

- **`EnumSet.copyOf`**：`EnumSet` 对枚举集合做了位向量优化，`contains` 是常数级；`Arrays.asList(...)` 避免枚举构造器里出现可变参数的空指针问题。
- **`unmodifiableSet`**：`getTypes()` 是公开方法，不包一层可读视图，调用方可以通过返回值破坏契约。
- **`types.length == 0` 单独处理**：`EnumSet.copyOf(空集合)` 会抛 `IllegalArgumentException`，所以无 type 的事件必须走空集合分支。这是 JDK API 的一个真实坑点。
- **code 与枚举名分离**：线上传输的是 `session`、`resp_end` 这种小写下划线串，枚举名只是 Java 侧标识。即使将来枚举重命名，wire format 也能保持稳定。

### 3.5 这套约束解决了什么

假设某天有人写出：

```java
StreamEventResponse.of(StreamEventEnums.EventEnum.RESP_END,
                       StreamEventEnums.TypeEnum.TEXT);
```

代码能编译，但会在运行时立刻抛异常：

```text
Event resp_end does not support type text
```

同理，给 `resp_end` 传空 type 是唯一合法路径。这样协议错误的暴露时点从“前端渲染异常”提前到了“后端构造现场”，定位成本完全不同。

不过也要如实说明它的边界：**这是运行期校验，不是编译期校验**。枚举能表达“允许的集合”，但 Java 枚举无法在类型系统层面把 `EventEnum` 与 `TypeEnum` 的合法组合约束住。要做到编译期约束，需要为每个 event 定义独立的响应子类型，代价是处理器和序列化复杂度显著上升，对当前阶段的事件规模不划算。

## 四、统一载体：StreamEventResponse

### 4.1 字段设计

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamEventResponse {
    private String event;
    private String type;
    private String title;
    private Object content;
    private Boolean isFinish;
}
```

| 字段 | 类型 | 作用 | 当前使用情况 |
| --- | --- | --- | --- |
| `event` | `String` | 阶段语义，来自 `EventEnum.getCode()` | 全部事件 |
| `type` | `String` | 形态语义，来自 `TypeEnum.getCode()` | 除 `resp_end` 外 |
| `title` | `String` | 展示标题预留位 | 当前无处理器写入 |
| `content` | `Object` | 事件载荷 | 全部事件 |
| `isFinish` | `Boolean` | 是否为该场景的结束帧 | 全部处理器显式设置 |

### 4.2 为什么 content 是 Object

如果 `content` 声明为 `String`，工具卡片就不得不先序列化成 JSON 字符串塞进去，前端拿到后再二次解析，历史重放时还要再解析一次。声明为 `Object` 后：

- 文本事件放 `String`；
- 工具事件放 `ToolCallResult` 对象；
- 未来步骤事件可以放结构化对象。

序列化交给 Jackson，前端拿到的是天然的结构化 JSON。代价是编译期丢失载荷类型信息，因此每个处理器都必须在自己的方法签名里明确参数类型（例如 `toolCallCard(ToolCallResult, Boolean)`），把类型安全下沉到工厂方法。

### 4.3 @JsonInclude(NON_NULL) 的真实效果

`resp_end` 事件序列化后是：

```json
{"event":"resp_end","isFinish":true}
```

`type`、`title`、`content` 为 `null`，被整体省略。这样前端不需要处理 `"content": null` 与 `"content" 不存在` 两种等价情况，SSE 帧也更短——流式场景下每帧省下的字节会被放大很多倍。

需要区分的是：**`NON_NULL` 只作用于 `StreamEventResponse` 自身字段**。`content` 内部的 `ToolCallResult` 没有该注解，所以工具卡片里未赋值的字段仍会输出 `null`。这是当前实现的真实行为，前端解析时不能假设工具卡片字段一定非空。

### 4.4 两个静态工厂与构造校验

```java
public static StreamEventResponse of(EventEnum event) {
    Objects.requireNonNull(event, "event must not be null");
    if (!event.getTypes().isEmpty()) {
        throw new IllegalArgumentException("Event " + event.getCode() + " requires a type");
    }
    return new StreamEventResponse(event.getCode());
}

public static StreamEventResponse of(EventEnum event, TypeEnum type) {
    Objects.requireNonNull(event, "event must not be null");
    Objects.requireNonNull(type, "type must not be null");
    if (!event.supports(type)) {
        throw new IllegalArgumentException(
                "Event " + event.getCode() + " does not support type " + type.getCode());
    }
    return new StreamEventResponse(event.getCode()).type(type.getCode());
}
```

两个入口形成互补的对称校验：

| 调用方式 | 校验规则 | 适用事件 |
| --- | --- | --- |
| `of(event)` | 该 event 必须**没有**任何 type | `resp_end` |
| `of(event, type)` | 该 event 必须**支持**该 type | 其余五类 |

也就是说，既不允许“该带 type 却不带”，也不允许“不该带 type 却带了”。构造器本身是 private，外部无法绕过校验直接 `new`。

### 4.5 链式 API

```java
StreamEventResponse.of(EventEnum.RESP, TypeEnum.TEXT)
        .content(content)
        .finish(isFinish);
```

链式设置让处理器的每个方法都能保持“一行一个事件”的紧凑形态。`type(String)` 虽然公开，但由于静态工厂已经完成校验，正常调用路径不会用到它；保留它是为了反序列化与未来扩展（例如从数据库读回事件后重新组装）。

### 4.6 为什么没有 eventId / deliveryId

类注释写得很直接：

```java
/**
 * Service unified SSE event. eventId and deliveryId are intentionally omitted for the first phase.
 */
```

这是一次显式的范围裁剪，不是一个“忘了加”的字段。第一阶段的可靠性依赖三条现成事实：

1. SSE 是单连接内的有序流，TCP 保证到达顺序；
2. 每个 `memoryId` 在中间件里有独立的 `SessionContext`，不会串流；
3. `events` 在收口时一次性写入消息记录，顺序就是内存 `List` 的追加顺序。

真正需要 `eventId` / `deliveryId` 的场景是**断线续传与去重**：客户端重连后要告诉服务端“我已经收到第 N 条”，服务端要能重放。当前 `/api/chat/stop` + 前端重新打开会话的历史重放模式绕开了这个问题——断了就重新拉消息明细，而不是续传。注释保留这两个命名，是为后续演进留下明确锚点。

## 五、核心事件类型：设计与实现明细

本章按事件逐个展开，用表格对齐“设计意图—实现落点—线上表现”。所有行都对应仓库中的真实代码路径，而不是规划稿。

### 5.1 总览对照表

| 事件 | code | type | 载荷类型 | isFinish | 触发回调 | 构造方法 | 是否入库 | 前端行为 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 会话创建 | `session` | `created` | `String`（sessionId） | `false` | `onInitComplete` | `AiSessionProcessor.sessionCreated` | 否（该中间件不收集） | 新会话时缓存 sessionId |
| AI 思考 | `think` | `text` | `String`（增量） | `false` | `onPartialThinking` | `AiThinkProcessor.respThink` | 是 | 追加到思考区 |
| AI 回答 | `resp` | `text` | `String`（增量） | `false` | `onPartialResponse` | `AiResponseProcessor.respText` | 是（同时累积到 content） | 顺序拼接正文 |
| 工具调用 | `tool_call` | `tool_card` | `ToolCallResult` | 开始 `false` / 结束 `true` | `beforeToolExecution`、`afterToolExecution`、`onToolExecutionError` | `AiToolCallProcessor.toolCallCard` | 是 | 按 `toolCallId` 原位更新卡片 |
| 回答结束 | `resp_end` | 无 | 无 | `true` | `onStop` | `AiResponseProcessor.respEnd` | 否 | 结束 loading、刷新会话列表 |
| 异常 | `error` | `text` | `String`（错误信息） | `true` | `onStopWithError`、`onError` | `AiErrorProcessor.respError` | 否（只更新消息状态与兜底 content） | 结束本轮并展示错误 |

这张表有三个值得注意的设计事实：

1. **六个事件里只有 `session`、`resp_end`、`error` 不入库**。`session` 不参与历史重放，前端只需在实时阶段拿到 sessionId；`resp_end` 可以从消息 `status` 推导；`error` 只用于实时提示并驱动消息状态收口。
2. **只有 `tool_call` 会出现同一个事件 code 的两帧**，靠 `isFinish` 区分开始与结束。
3. **载荷形态只有三种**：字符串、`ToolCallResult`、空。协议层没有为任何事件设计复杂嵌套结构。

### 5.2 `session/created`：会话创建

| 设计维度 | 定义 | 实现落点 |
| --- | --- | --- |
| 触发时机 | Agent 初始化完成，会话与占位消息已建立 | `ServiceStreamingMiddleware#onInitComplete` |
| 构造方法 | `sessionCreated(String sessionId)` | `AiSessionProcessor` |
| event / type | `session` / `created` | `StreamEventEnums.EventEnum.SESSION` |
| 载荷 | `sessionId` 字符串本身 | `content(chatContext.getMemoryId())` |
| isFinish | `false` | 处理器内固定 |
| 空值防护 | 中间件先取 `state(chatContext)`，为 `null` 则不发送 | `ServiceStreamingMiddleware` |
| 是否入库 | 否 | `SessionLifecycleMiddleware` 不收集该事件 |
| 前端消费 | 新会话时缓存本地 ID，后续请求携带 | `PublishedAgentChatPage.vue` |

实现代码如下：

```java
@Override
public void onInitComplete(AgentChatContext chatContext) {
    SessionContext context = state(chatContext);
    if (context != null) {
        context.send(AiSessionProcessor.sessionCreated(
                String.valueOf(chatContext.getMemoryId())));
    }
}
```

线上形态：

```json
{"event":"session","type":"created","content":"9f1c2b7e-...","isFinish":false}
```

设计取舍：`content` 直接放字符串而不是 `{"sessionId": "..."}`，因为唯一消费者只做 ID 缓存。代价是未来若要在会话事件里补充标题、模型名，需要把 `content` 改成对象，属于破坏性变更；当前选择用最小载荷换取最简解析。

### 5.3 `think/text`：AI 思考增量

| 设计维度 | 定义 | 实现落点 |
| --- | --- | --- |
| 触发时机 | 模型产生思考片段 | `ServiceStreamingMiddleware#onPartialThinking` |
| 构造方法 | `respThink(String content, Boolean isFinish)` | `AiThinkProcessor` |
| event / type | `think` / `text` | `StreamEventEnums.EventEnum.THINK` |
| 载荷 | 思考文本增量 | `partialThinking.text()` |
| isFinish | 链路中恒为 `false` | 中间件传 `false` |
| 空值防护 | `partialThinking` 或 `text()` 为 `null` 直接返回 | `ServiceStreamingMiddleware` |
| 是否入库 | 是，追加进 `RunState.events` | `SessionLifecycleMiddleware#onPartialThinking` |
| 前端消费 | 合并到思考块，按事件类型切换分段 | `ElegantMessage.vue` |

```java
@Override
public void onPartialThinking(
        int currentStep, PartialThinking partialThinking, AgentChatContext chatContext) {
    SessionContext context = state(chatContext);
    if (context == null || partialThinking == null || partialThinking.text() == null) {
        return;
    }
    context.send(AiThinkProcessor.respThink(partialThinking.text(), false));
}
```

线上形态（连续多帧）：

```json
{"event":"think","type":"text","content":"我先确认","isFinish":false}
{"event":"think","type":"text","content":"一下版本信息","isFinish":false}
```

设计边界：当前没有 `think/end`，前端依靠后续出现 `resp` 或 `tool_call` 来隐式结束思考块。单步 ReAct 下够用，多步思考时前端需要额外规则判断“新思考块还是续写”。

### 5.4 `resp/text`：AI 回答增量

| 设计维度 | 定义 | 实现落点 |
| --- | --- | --- |
| 触发时机 | 模型产生正文片段 | `ServiceStreamingMiddleware#onPartialResponse` |
| 构造方法 | `respText(String content, Boolean isFinish)` | `AiResponseProcessor` |
| event / type | `resp` / `text` | `StreamEventEnums.EventEnum.RESP` |
| 载荷 | 正文文本增量 | `partialResponse` |
| isFinish | 链路中恒为 `false` | 中间件传 `false` |
| 是否入库 | 是；同时 `content.append(...)` 累积全文 | `SessionLifecycleMiddleware#onPartialResponse` |
| 前端消费 | 按到达顺序拼接为正文 | `ElegantMessage.vue` / `DebugMessage.vue` |

```java
@Override
public void onPartialResponse(
        int currentStep, String partialResponse, AgentChatContext chatContext) {
    SessionContext context = state(chatContext);
    if (context == null || partialResponse == null) {
        return;
    }
    context.send(AiResponseProcessor.respText(partialResponse, false));
}
```

`resp` 与 `think` 在结构上完全同构，区别只在 `event` 字符串和前端渲染位置。这正是二维枚举的价值：新增一种“文本类阶段”不需要新增载荷类型或解析逻辑。

### 5.5 `tool_call/tool_card`：工具调用卡片

这是协议里唯一带结构化载荷、也是唯一会出现两帧的事件。

| 设计维度 | 定义 | 实现落点 |
| --- | --- | --- |
| 触发时机（开始） | 工具即将执行 | `beforeToolExecution` / `beforeConcurrentToolExecution` |
| 触发时机（结束） | 工具执行完成或失败 | `afterToolExecution` / `onConcurrentToolExecutionComplete` / `onToolExecutionError` |
| 构造方法 | `toolCallCard(ToolCallResult, Boolean)` | `AiToolCallProcessor` |
| event / type | `tool_call` / `tool_card` | `StreamEventEnums.EventEnum.TOOL_CALL` |
| 载荷 | `ToolCallResult` | 中间件逐字段填充 |
| isFinish（开始） | `false` | 表示卡片已创建、等待结果 |
| isFinish（结束） | `true` | 表示该工具卡片终态 |
| 关联键 | `toolCallId` | `ToolExecutionRequest.id()` |
| 耗时 | `duration` | 中间件用 `toolStartTimes` 计算 |
| 是否入库 | 是，开始与结束各一条事件 | `SessionLifecycleMiddleware#collectTool` |
| 前端消费 | 按 `toolCallId` 原位更新卡片 | `ElegantToolItem.vue` / `ToolCard.vue` |

`ToolCallResult` 的字段填充时机：

| 字段 | 开始帧 | 结束帧 | 失败帧 |
| --- | --- | --- | --- |
| `toolCallId` | `request.id()` | 同左 | 同左 |
| `toolName` | `request.name()` | 同左 | 同左 |
| `description` | `工具执行中` | `工具执行完成` | `工具执行失败` |
| `inputParams` | `request.arguments()` | 同左 | 同左 |
| `outputResult` | `null` | 工具返回值 | 异常信息 |
| `duration` | `null` | 毫秒数 | 毫秒数 |

线上两帧：

```json
{"event":"tool_call","type":"tool_card","content":{"toolCallId":"call_1","toolName":"web_search","description":"工具执行中","inputParams":"{\"query\":\"AgentForge\"}","outputResult":null,"duration":null},"isFinish":false}

{"event":"tool_call","type":"tool_card","content":{"toolCallId":"call_1","toolName":"web_search","description":"工具执行完成","inputParams":"{\"query\":\"AgentForge\"}","outputResult":"...","duration":1832},"isFinish":true}
```

并发批次下，开始帧按批次顺序发出，结束帧按各自完成顺序发出，因此**前端不能按下标匹配，必须按 `toolCallId` 归位**。

### 5.6 `resp_end`：回答结束

| 设计维度 | 定义 | 实现落点 |
| --- | --- | --- |
| 触发时机 | 本轮正常结束 | `ServiceStreamingMiddleware#onStop` |
| 构造方法 | `respEnd(Boolean isFinish)` | `AiResponseProcessor` |
| event / type | `resp_end` / 无 | `StreamEventEnums.EventEnum.RESP_END` |
| 载荷 | 无 | 不调用 `content(...)` |
| isFinish | `true` | 处理器内固定 |
| 是否入库 | 否 | 消息 `status=5` 已能表达正常结束 |
| 前端消费 | 停止 loading、刷新会话列表 | `PublishedAgentChatPage.vue` |

```java
public static StreamEventResponse respEnd(Boolean isFinish) {
    return StreamEventResponse.of(StreamEventEnums.EventEnum.RESP_END).finish(isFinish);
}
```

线上形态只有两个字段：

```json
{"event":"resp_end","isFinish":true}
```

它是协议里唯一走 `of(event)` 单参数工厂的事件。`of(event)` 会校验该 event 不允许任何 type，因此任何人都没法构造出 `resp_end/text` 这种非法组合。

### 5.7 `error/text`：异常

| 设计维度 | 定义 | 实现落点 |
| --- | --- | --- |
| 触发时机 | Agent 执行异常、流式启动异常 | `onStopWithError` / `onError` |
| 构造方法 | `respError(String content)` | `AiErrorProcessor` |
| event / type | `error` / `text` | `StreamEventEnums.EventEnum.ERROR` |
| 载荷 | 错误信息 | `safeMessage(error)` |
| isFinish | `true` | 处理器内固定 |
| 错误信息规则 | 优先 `error.getMessage()`，否则类名，空则 `未知错误` | 两个中间件的 `safeMessage` |
| 是否入库 | 否；异常只把消息收口为 `status=3`，不改写已收集事件 | `SessionLifecycleMiddleware#finishState` |
| 前端消费 | 结束本轮并展示错误气泡 | `ElegantMessage.vue` |

```java
public static StreamEventResponse respError(String content) {
    return StreamEventResponse.of(StreamEventEnums.EventEnum.ERROR,
                    StreamEventEnums.TypeEnum.TEXT)
            .content(content)
            .finish(true);
}
```

线上形态：

```json
{"event":"error","type":"text","content":"Connection reset","isFinish":true}
```

设计边界：错误只有文本，没有错误码。前端无法区分“模型超时”“限流”“鉴权失败”。由于 `content` 是 `Object`，后续可以平滑扩展为 `{"code":"RATE_LIMIT","message":"..."}`，不需要改载体结构。

### 5.8 六类事件的横向对比

| 对比项 | session | think | resp | tool_call | resp_end | error |
| --- | --- | --- | --- | --- | --- | --- |
| 是否有 type | 是 | 是 | 是 | 是 | 否 | 是 |
| 载荷类型 | String | String | String | Object | 无 | String |
| 是否增量 | 否（一次性） | 是 | 是 | 否（两帧快照） | 否 | 否 |
| 是否可多次出现 | 每轮 1 次 | 多次 | 多次 | 每工具 2 次 | 每轮 1 次 | 最多 1 次 |
| isFinish 恒值 | false | false | false | false→true | true | true |
| 是否入库 | 否 | 是 | 是 | 是 | 否 | 否 |
| 终态语义 | 无 | 无 | 无 | 单工具终态 | 整轮正常终态 | 整轮异常终态 |
| 前端关键动作 | 缓存 ID | 追加思考 | 拼接正文 | 原位更新卡片 | 收尾 | 报错收尾 |

这张表基本就是前端渲染器的实现说明书：按 `event` 分发，按 `type` 决定解析方式，按 `isFinish` 决定该分片是否收口，按 `toolCallId` 做卡片归位。

## 六、场景处理器：把协议知识收敛到一处

### 6.1 接口设计

```java
public interface IStreamSceneProcessor {
    String getSceneCode();
}
```

每个处理器实现它并返回自己负责的 event code：

```java
public class AiResponseProcessor implements IStreamSceneProcessor {
    @Override
    public String getSceneCode() {
        return StreamEventEnums.EventEnum.RESP.getCode();
    }
    ...
}
```

这里有一个必须讲清楚的真实情况：**当前 Service 侧没有任何代码通过 `getSceneCode()` 做路由**，中间件是直接静态调用 `AiResponseProcessor.respText(...)` 的。接口此刻的价值不是“已投入使用”，而是两件事：

1. **自描述**：每个处理器都能回答“我对应哪个 event”，无需反查枚举；
2. **预留扩展缝**：如果未来要按场景做插件化（例如工作流事件由独立模块提供处理器），注册表可以直接以 `getSceneCode()` 为 key 装配。

不把它说成已经实现的“策略路由”，更符合仓库现状。

### 6.2 五个处理器

| 处理器 | 场景 code | 工厂方法 | 构造的事件 | isFinish |
| --- | --- | --- | --- | --- |
| `AiSessionProcessor` | `session` | `sessionCreated(String sessionId)` | `session/created` | `false` |
| `AiThinkProcessor` | `think` | `respThink(String, Boolean)` | `think/text` | 调用方决定，链路中恒为 `false` |
| `AiResponseProcessor` | `resp` | `respText(String, Boolean)` | `resp/text` | 调用方决定，链路中恒为 `false` |
| `AiResponseProcessor` | `resp` | `respEnd(Boolean)` | `resp_end`（无 type） | `true` |
| `AiToolCallProcessor` | `tool_call` | `toolCallCard(ToolCallResult, Boolean)` | `tool_call/tool_card` | 开始 `false`，完成 `true` |
| `AiErrorProcessor` | `error` | `respError(String)` | `error/text` | `true` |

逐个看关键细节。

**会话创建**：`content` 直接是 `sessionId` 字符串，不是对象。

```java
return StreamEventResponse.of(EventEnum.SESSION, TypeEnum.CREATED)
        .content(sessionId)
        .finish(false);
```

选择字符串而不是 `{"sessionId": ...}` 的原因很实际：这个字段的唯一消费者是前端“新会话时缓存本地 ID”，包一层对象只会增加解析层级。代价是将来若要在会话事件里补充标题、模型名，就得把 `content` 从字符串改成对象，属于一次破坏性变更。当前用 `isFinish=false` 明确表示“会话刚建立，本轮还没结束”。

**回答结束**：没有 type，没有 content，只有 `isFinish=true`。

```java
return StreamEventResponse.of(EventEnum.RESP_END).finish(true);
```

它是整条流里语义最纯粹的一个事件：不表达内容，只表达“本轮正常收口”。前端收到它就知道可以停止 loading、刷新会话列表。

**异常**：结构与正文事件完全一致（`error/text`），只是 `event` 不同且 `isFinish=true`。

```java
return StreamEventResponse.of(EventEnum.ERROR, TypeEnum.TEXT)
        .content(content)
        .finish(true);
```

这样前端可以复用同一套文本渲染逻辑，只是把气泡样式换成错误态。

**思考**：与正文同构，`think/text`，链路中始终 `isFinish=false`。当前实现没有“思考结束”的显式标记，前端依靠后续出现 `resp`/`tool_call` 事件来隐式结束思考块。这是一个有意简化的语义，代价是前端必须按“事件类型切换”而不是“结束标记”来分段。

### 6.3 isFinish 的语义边界

`isFinish` 容易被误解成“整条流是否结束”。实际上它表达的是**该场景帧是否为终态帧**：

| 事件 | isFinish | 语义 |
| --- | --- | --- |
| `session/created` | `false` | 会话建立，不是终态 |
| `think/text` | `false` | 思考增量 |
| `resp/text` | `false` | 正文增量 |
| `tool_call/tool_card`（执行中） | `false` | 卡片已创建，等待结果 |
| `tool_call/tool_card`（完成/失败） | `true` | 该工具卡片终态 |
| `resp_end` | `true` | 本轮正常结束 |
| `error/text` | `true` | 本轮异常结束 |

因此“整轮结束”只能由 `resp_end` 或 `error` 表达；工具卡片的 `isFinish=true` 只表示**这一个工具**收口，不表示整轮结束。前端如果把任意 `isFinish=true` 当作全局结束，就会在第一个工具执行完成时错误地关闭流。这个坑在协议设计上是明确的，但在消费端需要被反复强调。

## 七、ToolCallResult：工具卡片载荷

### 7.1 字段设计

```java
public class ToolCallResult {
    private String toolCallId;
    private String toolName;
    private String description;
    private String inputParams;
    private String outputResult;
    private Long duration;
}
```

| 字段 | 含义 | 来源 |
| --- | --- | --- |
| `toolCallId` | 工具调用唯一标识 | `ToolExecutionRequest.id()` |
| `toolName` | 工具名 | `ToolExecutionRequest.name()` |
| `description` | 卡片状态描述 | 中间件按执行阶段赋值 |
| `inputParams` | 工具入参原文 | `ToolExecutionRequest.arguments()` |
| `outputResult` | 工具结果或异常信息 | 执行返回值 / 异常 message |
| `duration` | 耗时（毫秒） | 中间件按 `toolCallId` 计算 |

### 7.2 一次调用，两张卡片

工具卡片不是“执行完才发一次”，而是**开始和结束各发一次，用 `toolCallId` 关联**：

```text
beforeToolExecution   → tool_call/tool_card  isFinish=false  description=“工具执行中”
afterToolExecution    → tool_call/tool_card  isFinish=true   description=“工具执行完成” + outputResult + duration
onToolExecutionError  → tool_call/tool_card  isFinish=true   description=“工具执行失败” + outputResult=异常信息
```

前端按 `toolCallId` 做“原位更新”：第一次插入卡片骨架，第二次填充结果与耗时。这样用户在模型还在决定下一步时，就能看到工具已经跑起来了，而不是整个 ReAct 循环结束后才一次性出现所有卡片。

并发工具批次同样遵循这个模型：`beforeConcurrentToolExecution` 为每个请求各发一张执行中卡片，`onConcurrentToolExecutionComplete` 在**每个工具各自完成时**单独发终态卡片，因此不同卡片可以乱序完成，靠 `toolCallId` 归位。

### 7.3 duration 为什么在中间件算

`AiToolCallProcessor` 只接收现成的 `ToolCallResult`，不知道时间。耗时统计放在 `SessionContext` / `RunState` 的 `toolStartTimes`（`Map<toolCallId, Long>`）里，理由有两点：

1. 处理器保持纯函数式——同样的入参产出同样的事件，便于测试与复用；
2. 实时推送与落库的两个中间件需要**各自**维护耗时映射，因为它们运行在同一进程但持有独立状态；把计时放在处理器里会导致两处共享可变状态。

### 7.4 inputParams / outputResult 为什么是 String

`inputParams` 保存的是模型生成的工具参数 JSON 原文，`outputResult` 保存工具返回值原文。两者都可能是任意结构（对象、数组、纯文本），用 `String` 承载可以：

- 保证协议层不感知具体工具 Schema；
- 历史重放时原样交给前端，由工具卡片组件自行决定如何展示；
- 避免大对象在协议层被二次建模。

代价是前端要自己做 `JSON.parse` 容错——`ElegantToolItem` 正是这样处理的：能解析就解析，解析失败就按纯文本展示。

### 7.5 一个真实的不一致

两个中间件各自构造 `ToolCallResult` 时，`description` 的文案并不一致：

| 中间件 | 执行中 | 完成 | 失败 |
| --- | --- | --- | --- |
| `ServiceStreamingMiddleware` | `工具执行中` | `工具执行完成` | `工具执行失败` |
| `SessionLifecycleMiddleware` | `Tool execution in progress` | `Tool execution completed` | `Tool execution failed` |

这意味着**实时推送看到的是中文描述，历史重放看到的是英文描述**。协议结构是一致的，但展示文案出现了分叉。这个问题不在 `protocol` 包内，根因是“同一个卡片被两个中间件分别构造”。合理的收敛方向是把卡片构造与文案也下沉到协议层（例如 `ToolCallResult.running(request)` / `.completed(request, result, duration)` 静态工厂），让两个中间件只负责“何时发”和“发到哪里”。这是当前实现值得改进的第一优先级问题。

## 八、真实运行链路

### 8.1 从 Controller 到 SSE

`ChatController#stream` 只做三件事与协议相关：

```java
final SseEmitter emitter = new SseEmitter(0L);
streamingMiddleware.bind(sessionId, emitter);
...
agentManager.stream(agent, chatReq).ignoreErrors().start();
```

`bind` 把 `memoryId -> SseEmitter` 写进 `ServiceStreamingMiddleware` 的 `sessions` 映射，之后 Agent 的每个生命周期回调都会通过 `state(chatContext)` 拿到同一个 `SessionContext`。

```java
@Override
public void onInitComplete(AgentChatContext chatContext) {
    SessionContext context = state(chatContext);
    if (context != null) {
        context.send(AiSessionProcessor.sessionCreated(
                String.valueOf(chatContext.getMemoryId())));
    }
}

@Override
public void onPartialThinking(int step, PartialThinking partialThinking, AgentChatContext ctx) {
    SessionContext context = state(ctx);
    if (context == null || partialThinking == null || partialThinking.text() == null) {
        return;
    }
    context.send(AiThinkProcessor.respThink(partialThinking.text(), false));
}
```

可以看到中间件的写法非常薄：**判空 → 调处理器 → send**。所有“事件长什么样”的知识都在处理器里，中间件不拼 `event`、不拼 `type`。

### 8.2 SSE 帧长什么样

`SessionContext.send` 的写法是：

```java
emitter.send(SseEmitter.event().data(event));
```

`SseEmitter.event().data(...)` 在未指定 `name` 时直接序列化对象为 `data:` 行。用 `curl` 看到的原始流大致是：

```text
data:{"event":"session","type":"created","content":"9f1c...","isFinish":false}

data:{"event":"think","type":"text","content":"我先确认","isFinish":false}

data:{"event":"think","type":"text","content":"一下版本信息","isFinish":false}

data:{"event":"tool_call","type":"tool_card","content":{"toolCallId":"call_1","toolName":"web_search","description":"工具执行中","inputParams":"{\"query\":\"AgentForge\",\"__thought\":\"需要核对最新版本\"}","outputResult":null,"duration":null},"isFinish":false}

data:{"event":"tool_call","type":"tool_card","content":{"toolCallId":"call_1","toolName":"web_search","description":"工具执行完成","inputParams":"{\"query\":\"AgentForge\",\"__thought\":\"需要核对最新版本\"}","outputResult":"...","duration":1832},"isFinish":true}

data:{"event":"resp","type":"text","content":"根据检索结果","isFinish":false}

data:{"event":"resp_end","isFinish":true}
```

这里能直接观察到前文提到的几个设计后果：

- SSE 帧没有 `event:` 行，只有 `data:`，因为 event 名在 JSON 的 `event` 字段里，不占用 SSE 协议层；
- `resp_end` 帧只有两个字段；
- 工具卡片里未赋值的 `outputResult` / `duration` 在第一帧是显式 `null`（`ToolCallResult` 没有 `NON_NULL`）；
- `__thought` 作为工具入参的一部分自然出现在 `inputParams` 里，协议层不需要为“工具调用意图”单独加字段。

前端 `utils/sse.js` 的解析也印证了这一点——它只按 `data:` 行聚合，然后 `JSON.parse`：

```js
const data = frame.split(/\r?\n/)
  .filter((line) => line.startsWith('data:'))
  .map((line) => line.slice(5).trimStart()).join('\n')
if (data) onEvent(JSON.parse(data))
```

**协议层不依赖 SSE 的 `event:` 字段**，这让同一批事件可以被非 SSE 通道（例如 WebSocket、消息队列）复用。

### 8.3 落库与重放同源

`SessionLifecycleMiddleware` 与流式中间件并排注册，监听同样的回调，但收集的 `StreamEventResponse` 只进内存 `List`：

```java
@Override
public void onPartialResponse(int step, String partialResponse, AgentChatContext ctx) {
    RunState state = state(ctx);
    if (state == null || partialResponse == null) {
        return;
    }
    state.content.append(partialResponse);          // 正文全文
    state.collect(AiResponseProcessor.respText(partialResponse, false)); // 事件流
}
```

收口时一次性序列化：

```java
String eventsJson = toJson(state.events);
agentSessionMessageMapper.finish(state.aiMessageId, status.getCode(), content,
        eventsJson, elapsed, finishedAt, finishedAt);
```

因此数据库里 `ai_agent_session_message.events` 存的就是一个 `StreamEventResponse` 数组：

```json
[
  {"event":"think","type":"text","content":"我先确认","isFinish":false},
  {"event":"resp","type":"text","content":"根据检索结果","isFinish":false}
]
```

前端 `utils/sessionReplay.js` 直接按同一套 event/type 语义把数组还原成渲染结构。于是**实时渲染和历史重放消费的是同一个协议**，不存在两套字段映射。这是这套设计最大的收益点：协议不只是“传输格式”，也是“持久化格式”。

需要注意，`events` 由 `SessionLifecycleMiddleware` **自己构造**，不是复用流式中间件发出的对象。结构相同，但 `description` 文案不同（见 7.5），这是“同源”承诺上的一个真实裂缝。

### 8.4 结束、异常、停止路径

三条收口路径都由协议事件表达：

| 路径 | 触发点 | 推送事件 | 落库状态 |
| --- | --- | --- | --- |
| 正常结束 | `onStop` | `resp_end` + `isFinish=true` | AI 消息 `5`，会话 `2` |
| 异常结束 | `onStopWithError` / `onError` | `error/text` + `isFinish=true` | AI 消息 `3` |
| 用户停止 | `cancel` → `onStop(CANCEL)` | `resp_end` + `isFinish=true` | AI 消息 `4` |

`stop` 接口本身只发取消信号并 `unbind`，不碰任何表，也不构造事件；终态事件统一由中间件回调产生。这保证了“同一终态只有一个写入方”。

另有 `ChatController#notice`，用于 Agent 未发布这类无需建立会话的场景。它刻意复用了正常协议：

```java
emitter.send(SseEmitter.event().data(AiResponseProcessor.respText(message, false)));
emitter.send(SseEmitter.event().data(AiResponseProcessor.respEnd(true)));
emitter.complete();
```

前端无需为“提示信息”写特殊分支，它看起来就是一条普通 AI 回答。这是“协议复用优于新增事件类型”的一个正面例子——只有结构真的不同时才应该新增 event。

## 九、设计原则复盘

把上面的实现抽象一下，可以归纳出六条原则。

### 9.1 枚举即契约

event 与 type 的合法组合写在枚举构造器里，而不是散落在中间件的 `switch` 或前端判断中。新增事件时，必须同时回答两个问题：“它的 code 是什么”“它允许哪些 type”。协议变更点被收敛到单个文件。

### 9.2 载体与传输解耦

`StreamEventResponse` 不 import 任何 Web 类型。`SseEmitter` 只出现在 `ServiceStreamingMiddleware`。这让协议对象可以被自由地序列化进数据库、写入日志、在测试中直接断言。

### 9.3 处理器只负责“长什么样”

中间件负责“何时发、发给谁”，处理器负责“构造什么事件”。处理器方法是静态、无状态、纯函数式的，因此可以被两个中间件同时调用而互不影响（除了它们各自传入的载荷）。

### 9.4 增量事件 + 显式终态

思考、正文都是增量帧，`isFinish=false`；结束用独立的 `resp_end`，而不是“最后一帧正文的 `isFinish=true`”。这样前端不需要猜测“这是不是最后一段”，也避免了“空回答无法收口”的边界问题——即使模型没输出任何正文，`resp_end` 仍会到达。

### 9.5 可持久化即协议的一部分

选择 `Object content` + 稳定的字符串 code，使得事件数组可以原样入库并原样重放。如果协议为传输做了特殊优化（例如把所有内容扁平化成字符串），持久化侧就得再建一套结构。

### 9.6 演进留白

`title` 字段、`eventId` / `deliveryId` 注释、`IStreamSceneProcessor` 接口、`of(event)` 的无 type 分支，都是显式留白。它们当前没有完整闭环，但为工作流步骤事件、断线续传、插件化处理器保留了明确的接入点。

## 十、已知边界与后续演进

从真实代码视角看，这套协议还有几处需要正视的地方。

### 10.1 文案与结构分离不彻底

如 7.5 所述，`description` 的中英文分叉说明“卡片构造”仍分散在两个中间件。建议把 `ToolCallResult` 的构造收敛为静态工厂：

```java
ToolCallResult.running(request);
ToolCallResult.completed(request, result, duration);
ToolCallResult.failed(request, errorMessage, duration);
```

中间件只保留“何时调用”的职责，文案与字段填充只存在一份。

### 10.2 title 字段尚未启用

`title(String)` 是链式 API 的一部分，但仓库中没有任何处理器调用它。它目前的实际作用是序列化占位。要么在近期把它用于工具卡片的展示标题（例如“网页搜索”而不是 `web_search`），要么在文档里明确标注“保留字段，当前不消费”，避免前端误以为它一定有值。

### 10.3 缺少显式序号

事件顺序目前完全依赖单条 SSE 连接的到达顺序和内存 `List` 的追加顺序。一旦引入多路复用（例如同一连接订阅多个子任务）或异步写出，就需要 `seq` 字段。`eventId` / `deliveryId` 的注释已经预留了这个方向。

### 10.4 error 事件只有文本

`error/text` 的 `content` 是 `safeMessage(error)` 的结果：优先 `error.getMessage()`，否则类名。对于需要前端做差异化处理的错误（限流、鉴权、模型超时），仅凭文本无法区分。后续可以在 `content` 中扩展错误码，或新增 `error/code` 类型。由于 `content` 是 `Object`，这种扩展不需要改载体结构。

### 10.5 思考块没有结束标记

`think` 事件始终 `isFinish=false`，前端靠后续事件类型切换来分段。如果模型在一轮内多次进入思考（多步 ReAct），前端需要额外逻辑判断“这是新的思考块还是继续上一块”。可以考虑增加 `think/end` 或在 `think` 帧上携带 `step` 信息。

### 10.6 工具并发时的卡片顺序

并发批次下，工具完成顺序与开始顺序不一致，前端必须按 `toolCallId` 归位而不能按数组下标。协议已经提供了 `toolCallId`，但这一点需要在接入文档中显式说明，否则很容易写出“把第 N 个完成事件套到第 N 张卡片上”的错误实现。

### 10.7 step 事件仍未定义

会话/消息表设计文档中提到工作流步骤类事件（`step`）属于后续扩展。当前 `EventEnum` 没有对应枚举，说明协议在有意控制事件数量——只有真正需要独立渲染语义的阶段才值得新增 event，其余情况优先复用 `resp` 或 `tool_call`。

## 十一、总结

AgentForge Service 的事件协议规模很小：两个枚举、一个载体、一个载荷对象、五个处理器。但它在几个关键点上做了明确取舍：

- 用 **event × type 二维枚举** 替代扁平事件名，把组合合法性写进枚举构造器；
- 用 **`Object content` + 稳定 code** 让同一份事件既能推 SSE、又能落库重放；
- 用 **场景处理器** 把“事件长什么样”从中间件里抽离，中间件只保留“何时发”；
- 用 **增量帧 + `resp_end` 终态** 表达一轮对话的生命周期，异常与停止复用同一收口语义；
- 用 **显式留白**（`eventId`、`title`、`step`）为断线续传和工作流事件预留演进空间。

它也有真实存在的粗糙处：两个中间件各自构造工具卡片导致文案分叉，`title` 尚未被消费，事件缺少显式序号，思考块缺少结束标记。把这些边界写清楚，比假装协议已经完备更有价值——因为协议的价值不在于字段多少，而在于**每一个消费方都能对同一份定义达成一致，并且知道哪里还没有一致**。

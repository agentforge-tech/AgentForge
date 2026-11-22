# AgentForge ReAct 层、内置 Middleware 扩展：任务计划 TodoPlanMiddleware 实现与事件交互

> 适用版本：release_1.x
> 适用模块：`agentforge-framework/agentforge-agent-core`、`agentforge-service`
> 源码位置：`agent/extend/middlewares/todoplan/`、`service/modules/agent/middleware/todoplan/`
> 包前缀：`cloud.changlu.agentforge.agent.extend.middlewares.todoplan`
> 维护者：长路

系列定位：本文属于「AgentForge 核心模块设计原理文章系列 / 02、通用 ReAct Agent 层 / middleware」，聚焦任务计划能力如何用「工具 + 观察型中间件 + 复用事件协议」落地：

> **`TodoPlanTool` 负责定义计划与校验；`TodoPlanMiddleware` 只负责在工具成功后识别 `todoplan`，把全量计划交给 `TodoPlanListener`；前端不新增事件协议，直接复用 `tool_call` 事件把工具卡片替换为 `TodoBoard`。**

<br/>

## 文档导航

| 文档 | 说明 |
|---|---|
| [核心ReAct 框架设计，支持非流式、流式 一套调用.md](../%E6%A0%B8%E5%BF%83ReAct%20%E6%A1%86%E6%9E%B6%E8%AE%BE%E8%AE%A1%EF%BC%8C%E6%94%AF%E6%8C%81%E9%9D%9E%E6%B5%81%E5%BC%8F%E3%80%81%E6%B5%81%E5%BC%8F%20%E4%B8%80%E5%A5%97%E8%B0%83%E7%94%A8.md) | 第 01 篇：ReAct 分层、非流式主循环、流式递归驱动、状态机 |
| [标准Agent的middleware接口扩展实现.md](%E6%A0%87%E5%87%86Agent%E7%9A%84middleware%E6%8E%A5%E5%8F%A3%E6%89%A9%E5%B1%95%E5%AE%9E%E7%8E%B0.md) | 第 02 篇：中间件契约、管理器分发、流式/非流式接入 |
| [内置middleware扩展：日志Loggingmiddlware实现.md](%E5%86%85%E7%BD%AEmiddleware%E6%89%A9%E5%B1%95%EF%BC%9A%E6%97%A5%E5%BF%97Loggingmiddlware%E5%AE%9E%E7%8E%B0.md) | 第 03 篇：日志中间件、会话级统计、耗时与 token 汇总 |
| [内置middlware扩展：Agent Loop TraceMiddlware实现.md](%E5%86%85%E7%BD%AEmiddlware%E6%89%A9%E5%B1%95%EF%BC%9AAgent%20Loop%20TraceMiddlware%E5%AE%9E%E7%8E%B0.md) | 第 04 篇：Trace 中间件与链路可视化 |
| 当前文章（任务计划 TodoPlanMiddleware 实现） | 工具与校验、观察型中间件、`tool_call` 事件对接、TodoBoard 交互效果 |
| [Agent支持任务计划todo列表展示.md](../../agentforge-service核心业务模块/后端/agent核心设计实现/Agent页面会话对话交互/Agent支持任务计划todo列表展示.md) | 业务落地篇：前端页面接入与任务列表展示方案 |

<br/>

## 目录

- [一、背景与定位：任务计划为什么落在中间件层](#一背景与定位任务计划为什么落在中间件层)
- [二、能力全景与执行链路](#二能力全景与执行链路)
- [三、核心对象：工具、模型与校验](#三核心对象工具模型与校验)
- [四、TodoPlanMiddleware 源码拆解](#四todoplanmiddleware-源码拆解)
- [五、Service 侧装配与能力开关](#五service-侧装配与能力开关)
- [六、核心事件协议对接](#六核心事件协议对接)
- [七、前端交互效果实现](#七前端交互效果实现)
- [八、设计取舍与已知边界](#八设计取舍与已知边界)
- [九、验证清单与扩展方向](#九验证清单与扩展方向)
- [十、总结](#十总结)

---

## 一、背景与定位：任务计划为什么落在中间件层

### 1.1、任务计划的体验问题

Agent 执行多步骤任务时，用户通常只能看到最终回答，无法中途感知：

- 任务一共拆成了几步；
- 每一步是待处理、进行中还是已完成；
- 当前正在执行哪一步；
- 计划是否被调整过。

最直接的解法是让模型在每一步调用一个工具，把结构化计划写出来；但只加工具还不够：

1. **工具结果会进入模型上下文**，但业务侧不一定能及时拿到「最新计划是什么」；
2. **前端需要稳定数据源**，不能依赖模型自由文本；
3. **历史回放需要可重建**，不能只存在内存里；
4. **监控、审计、统计需要一个无侵入的挂点**。

因此任务计划被拆成三件事：

| 角色 | 职责 |
|---|---|
| `TodoPlanTool` | 定义工具契约、接收全量计划、校验并返回状态统计 |
| `TodoPlanMiddleware` | 工具成功执行后识别 `todoplan`，把计划交给监听扩展点 |
| 前端 | 复用 `tool_call` 事件，解析入参并渲染任务列表 |

### 1.2、设计定位：观察型中间件，只旁路，不接管

`TodoPlanMiddleware` 只实现 `IAgentMiddleware`，并且只覆盖工具执行后的两个回调：

```java
public class TodoPlanMiddleware implements IAgentMiddleware {

    @Override
    public String afterToolExecution(
            ToolExecutionRequest request, String result, AgentChatContext context) {
        if (isTodoPlan(request)) {
            notifyListener(request, context);
        }
        return result;
    }

    @Override
    public List<String> afterConcurrentToolExecution(
            List<ToolExecutionRequest> requests, List<String> results, AgentChatContext context) {
        // 从后往前找到最新的 todoplan，通知一次
        return results;
    }
}
```

它有三个明确边界：

1. **不改写结果**：两个回调都原样返回 `result(s)`；
2. **不发送事件**：SSE 的 `tool_call` 由 `ServiceStreamingMiddleware` 独立发送；
3. **不持有计划状态**：每次只把当前入参解析成快照交给 `TodoPlanListener`。

这样它天然满足「可随时摘掉」：移除中间件后，工具照常执行、事件照常推送，只是少了业务侧的计划监听。

### 1.3、为什么不是专用 Todo 事件

`todoplan` 的入参本身就是一份完整计划，而现有 `tool_call` 事件已经携带：

| 字段 | 作用 |
|---|---|
| `toolName` | 识别是否为 `todoplan` |
| `inputParams` | 完整工具入参，包含 `todos` |
| `isFinish` | 区分「执行中」和「执行完成」 |
| `events` 持久化 | 历史会话可回放 |

所以不需要新增 `todo` 事件、`TodoResult` 或 `AiTodoProcessor`。多一套事件协议意味着多一套前后端契约、持久化格式和回放分支；在当前能力下，复用 `tool_call` 是成本最低、闭环最完整的选择。

<br/>

## 二、能力全景与执行链路

### 2.1、代码结构

```text
agentforge-framework/agentforge-agent-core/src/main/java/
└── cloud/changlu/agentforge/agent/extend/middlewares/todoplan/
    ├── model/
    │   ├── TodoItem.java              // 单条任务
    │   ├── TodoPriority.java          // 优先级枚举
    │   └── TodoStatus.java            // 状态枚举
    ├── validation/
    │   └── TodoValidator.java         // 全量计划校验与排序
    ├── tool/
    │   └── TodoPlanTool.java          // todoplan 工具 + 系统提示词 + 入参解析
    ├── listener/
    │   └── TodoPlanListener.java      // 监控扩展点
    └── middleware/
        └── TodoPlanMiddleware.java    // 观察型中间件

agentforge-service/agentforge-service-backend/agentforge-service-web/src/main/java/
└── cloud/changlu/agentforge/service/modules/agent/
    ├── config/TodoPlanConfiguration.java
    └── middleware/todoplan/
        ├── ServiceTodoPlanMiddleware.java
        └── ServiceTodoPlanListener.java
```

### 2.2、端到端链路

```text
模型返回 ToolExecutionRequest(name=todoplan, arguments={"todos":[...]})
        │
        ▼
LocalToolExecutor
        │
        ▼
TodoPlanTool.plan(List<TodoItem>)
        ├─ TodoValidator.normalize(...)
        └─ 返回 "Plan updated: x completed, y in progress, z pending."
        │
        ├──────────────────────────────┬──────────────────────────────┐
        ▼                              ▼                              ▼
ServiceStreamingMiddleware       TodoPlanMiddleware            SessionLifecycleMiddleware
before/afterToolExecution        afterToolExecution            收集同一份 tool_call
发送 tool_call(isFinish=false/true)  → TodoPlanTool.parsePlan    写入消息 events
        │                        → TodoPlanListener
        │                              │
        ▼                              ▼
SSE tool_call 事件             ServiceTodoPlanListener
        │                       打印会话级计划日志
        ▼
前端 applyTool
        ├─ 调试模式 detailed=true  → 普通 ToolCard
        └─ 发布模式 detailed=false → buildTodoCardPart → TodoBoard
```

这张图有一个关键点：

> **事件推送与中间件监听是同一份工具执行的三个并行消费者，彼此不相互依赖。**

`ServiceStreamingMiddleware` 负责实时 SSE，`SessionLifecycleMiddleware` 负责事件持久化，`TodoPlanMiddleware` 负责业务监控。任何一个缺席，另外两个仍然工作。

### 2.3、层级职责

| 层 | 组件 | 职责 | 是否感知前端 |
|---|---|---|---|
| 框架层 | `TodoPlanTool` | 工具定义、参数校验、提示词 | 否 |
| 框架层 | `TodoPlanMiddleware` | 识别工具、解析计划、回调监听器 | 否 |
| 框架层 | `TodoPlanListener` | 监控扩展点，默认空实现 | 否 |
| Service 层 | `ServiceTodoPlanMiddleware` | 默认绑定 Service 监听器 | 否 |
| Service 层 | `ServiceTodoPlanListener` | 打印计划日志，可替换为审计/指标 | 否 |
| Service 层 | `ServiceStreamingMiddleware` | 发送 `tool_call` SSE | 是 |
| 前端 | `buildTodoCardPart` | 从 `tool_call.inputParams` 推导任务卡片 | 是 |
| 前端 | `TodoBoard.vue` | 任务列表、进度、状态可视化 | 是 |

<br/>

## 三、核心对象：工具、模型与校验

### 3.1、`TodoItem`：字段即 Schema

`TodoItem` 直接使用数字表示状态和优先级，字段上的 `@P` 会被 `ToolSpecifications` 写入嵌套 Schema：

```java
public class TodoItem {

    @P("任务序号，从 1 开始，在同一份计划内唯一")
    private Integer seq;

    @P("任务内容，简短、可执行，最长 300 字符")
    private String content;

    @P("状态：1=待处理，2=进行中，3=已完成，4=已取消")
    private Integer status;

    @P("优先级：1=高，2=中，3=低")
    private Integer priority;
}
```

模型看到的 `todoplan` 入参结构：

```json
{
  "todos": [
    {
      "seq": 1,
      "content": "梳理需求与边界",
      "status": 3,
      "priority": 1
    },
    {
      "seq": 2,
      "content": "实现 TodoPlanTool",
      "status": 2,
      "priority": 1
    },
    {
      "seq": 3,
      "content": "接入前端任务计划",
      "status": 1,
      "priority": 2
    }
  ]
}
```

使用数字而不是字符串的原因：

- 模型输出更短、更稳定，少一层枚举文案歧义；
- 后端枚举集中维护数字与文案映射，前端也只需要一份映射表；
- 校验失败时能明确提示「必须是 1/2/3/4」或「必须是 1/2/3」。

### 3.2、状态与优先级映射

```java
public enum TodoStatus {
    PENDING(1, "pending"),
    IN_PROGRESS(2, "in_progress"),
    COMPLETED(3, "completed"),
    CANCELLED(4, "cancelled");
}

public enum TodoPriority {
    HIGH(1, "high"),
    MEDIUM(2, "medium"),
    LOW(3, "low");
}
```

两个枚举都提供 `fromCode` / `fromValue`，前端 `todoEventReducer.js` 维护同样的数字映射：

```js
const STATUS_KEYS = { 1: 'pending', 2: 'in_progress', 3: 'completed', 4: 'cancelled' }
const PRIORITY_KEYS = { 1: 'high', 2: 'medium', 3: 'low' }
```

这保证了「模型输出数字、后端校验数字、前端渲染文案」三层使用同一套语义。

### 3.3、`TodoValidator`：把计划约束前移到工具边界

`TodoValidator.normalize` 是计划质量的唯一闸门：

| 约束 | 值 | 目的 |
|---|---:|---|
| 最大任务数 | 30 | 防止单次计划撑爆上下文与前端列表 |
| `seq` | 正整数且唯一 | 保证稳定展示顺序 |
| `content` | 非空，去首尾空格后不超过 300 字 | 防止超长文本破坏卡片布局 |
| `status` | 1/2/3/4 | 保证前端映射可穷举 |
| `priority` | 1/2/3 | 保证前端标签可穷举 |
| `in_progress` | 最多一项 | 明确「当前正在做什么」 |
| 输出顺序 | 按 `seq` 升序 | 服务端与前端顺序一致 |
| 返回值 | 不可变列表 | 防止监听器意外修改计划 |

核心逻辑：

```java
public static List<TodoItem> normalize(List<TodoItem> todos) {
    if (todos == null) {
        throw new IllegalArgumentException("todos must not be null");
    }
    if (todos.size() > MAX_TODOS) {
        throw new IllegalArgumentException("todos cannot contain more than 30 items");
    }

    int inProgress = 0;
    Set<Integer> seqSet = new HashSet<Integer>();
    List<TodoItem> normalized = new ArrayList<TodoItem>(todos.size());
    for (TodoItem item : todos) {
        // seq / content / status / priority 校验
        if (status == TodoStatus.IN_PROGRESS && ++inProgress > 1) {
            throw new IllegalArgumentException("todos can contain at most one in_progress item");
        }
        normalized.add(new TodoItem(item.getSeq(), content, status.getCode(), priority.getCode()));
    }
    Collections.sort(normalized, bySeq);
    return Collections.unmodifiableList(normalized);
}
```

### 3.4、`TodoPlanTool`：全量替换语义

工具名固定为 `todoplan`，方法只接收一个参数：

```java
public final class TodoPlanTool {

    public static final String TOOL_NAME = "todoplan";

    @Tool(name = TOOL_NAME, value = DESCRIPTION)
    public String plan(
            @P(value = "Complete replacement list of todo objects") List<TodoItem> todos) {
        List<TodoItem> normalized = TodoValidator.normalize(todos);
        return "Plan updated: "
                + count(normalized, TodoStatus.COMPLETED.getCode()) + " completed, "
                + count(normalized, TodoStatus.IN_PROGRESS.getCode()) + " in progress, "
                + count(normalized, TodoStatus.PENDING.getCode()) + " pending.";
    }
}
```

`DESCRIPTION` 明确告诉模型这是**全量替换**：

```text
Replace the current session's complete todo list and return status counts.
Pass a todos array containing at most 30 items...
Every call is a full replacement: include all items that should remain in the list,
because omitted items are removed. Pass an empty array to clear the list.
```

全量替换的好处：

1. 不需要 `add/update/delete` 多套工具，模型只需维护一个数组；
2. 每次调用都是一份完整快照，前端和监听器不需要做增量合并；
3. 历史回放时每个 `todoplan` 事件都能独立还原出当时的计划；
4. 状态推进、取消、失败都能通过「保留并改状态」表达。

### 3.5、系统提示词：约束模型的使用时机

`SYSTEM_PROMPT` 只做行为引导，不参与校验：

```text
当任务需要多个步骤、需要调用多个工具，或预计无法在一次回复内完成时，先调用 todoplan 制定计划。
维护计划时遵守：
- 每项 seq 使用从 1 开始的递增序号，保证展示顺序；
- 每项 content 保持简短、可执行；
- 同一时间最多一项 in_progress；
- 每次推进任务后立即调用 todoplan 全量更新状态；
- 已完成、已取消或失败的任务也要保留在列表中并更新状态；
- 简单问答、单步查询不要调用 todoplan。
```

`appendSystemPrompt` 负责拼接，空提示词时直接返回任务计划说明：

```java
public static String appendSystemPrompt(String basePrompt) {
    if (basePrompt == null || basePrompt.trim().isEmpty()) {
        return SYSTEM_PROMPT;
    }
    return basePrompt + "\n\n" + SYSTEM_PROMPT;
}
```

### 3.6、`parsePlan`：给中间件复用的解析入口

中间件不应该重新实现一遍 JSON 解析，因此 `TodoPlanTool` 暴露：

```java
public static List<TodoItem> parsePlan(String arguments) {
    Map<String, Object> root = Json.parseObject(arguments);
    List<Object> rawTodos = Json.array(root.get("todos"));
    // 逐项读取 seq/content/status/priority，转成 TodoItem
    return TodoValidator.normalize(todos);
}
```

它有两个细节：

- `code()` 同时接受 `Number` 和 `String`，模型偶尔把数字写成字符串也能兼容；
- 解析后仍然走 `TodoValidator.normalize`，保证监听器拿到的计划与工具执行时校验过的口径一致。

<br/>

## 四、TodoPlanMiddleware 源码拆解

### 4.1、构造函数：空监听器兜底

```java
private final TodoPlanListener listener;

public TodoPlanMiddleware(TodoPlanListener listener) {
    this.listener = listener == null ? TodoPlanListener.noop() : listener;
}
```

`TodoPlanListener` 是函数式接口：

```java
@FunctionalInterface
public interface TodoPlanListener {
    void onTodoPlan(List<TodoItem> todos, AgentChatContext context);

    static TodoPlanListener noop() {
        return (todos, context) -> {};
    }
}
```

好处是：框架层不依赖任何 Service 实现；调用方不传监听器时也不会 NPE。

### 4.2、串行工具：`afterToolExecution`

```java
@Override
public String afterToolExecution(
        ToolExecutionRequest request, String result, AgentChatContext context) {
    if (isTodoPlan(request)) {
        notifyListener(request, context);
    }
    return result;
}
```

执行顺序：

```text
beforeToolExecution
        ↓
TodoPlanTool 执行（参数转换 + 校验 + 返回统计）
        ↓
afterToolExecution
        ├─ streaming middleware 发送 tool_call(isFinish=true)
        ├─ session middleware 收集事件
        └─ todo middleware 识别 todoplan → 监听器
        ↓
结果写回 ChatMemory
```

这里有一个重要前提：**只有工具成功执行才会进入 `afterToolExecution`**。如果 `todoplan` 因校验失败抛异常，流程会走 `onToolExecutionError`，`TodoPlanMiddleware` 不会收到通知。这是符合预期的：

- 失败的计划不应该展示给用户；
- 失败原因已经通过工具错误结果返回给模型和前端；
- 中间件不做「失败计划补救」，避免把半成品当成正式计划。

### 4.3、并发工具：`afterConcurrentToolExecution`

一轮模型可能同时返回多个工具调用，执行器会走并发批次。此时串行回调不会触发，必须实现批次回调：

```java
@Override
public List<String> afterConcurrentToolExecution(
        List<ToolExecutionRequest> requests, List<String> results, AgentChatContext context) {
    if (requests == null || requests.isEmpty()) {
        return results;
    }
    for (int i = requests.size() - 1; i >= 0; i--) {
        if (isTodoPlan(requests.get(i))) {
            notifyListener(requests.get(i), context);
            break;
        }
    }
    return results;
}
```

两个设计点：

1. **从后往前找**：同一批里如果出现多个 `todoplan`，最后一个调用代表最新计划，只通知它；
2. **只通知一次**：并发批次只产生一次监控回调，避免同一批计划重复上报。

注意这里与前端存在一个有意的差异：

> 服务端监控只取「最新一份计划」，前端仍然会为每个 `todoplan` 工具调用渲染一个 `TodoBoard`。

原因是两者目标不同：监控关心当前有效状态，展示关心执行过程。当前实现优先保证监控口径简洁。

### 4.4、识别与通知：异常隔离

```java
private boolean isTodoPlan(ToolExecutionRequest request) {
    return request != null && TodoPlanTool.TOOL_NAME.equals(request.name());
}

private void notifyListener(ToolExecutionRequest request, AgentChatContext context) {
    try {
        List<TodoItem> todos = TodoPlanTool.parsePlan(request.arguments());
        listener.onTodoPlan(todos, context);
    } catch (Exception e) {
        log.log(Level.WARNING,
                "处理 Todo 计划失败，忽略旁路异常: sessionId="
                        + (context == null ? null : context.getMemoryId()),
                e);
    }
}
```

异常隔离有两层：

| 层 | 行为 |
|---|---|
| `TodoPlanMiddleware` | 解析失败、监听器抛异常都只记 WARN，不影响工具结果 |
| `AgentMiddlewareManager` | 即使中间件本身抛出未捕获异常，也会记 SEVERE 并继续执行其他中间件 |

因此任务计划监控是**纯旁路能力**：监听器写日志、写审计、发 MQ 失败，都不会让 Agent 主流程失败。

### 4.5、无状态带来的并发安全

`TodoPlanMiddleware` 没有成员状态，只有 `listener` 引用，因此：

- 可以被多个会话共享；
- 串行回调在 Agent 主线程执行；
- 并发回调可能在工具线程池执行；
- 监听器本身需要线程安全，或只做轻量日志。

当前 `ServiceTodoPlanListener` 只使用 SLF4J 打印日志，天然线程安全。

<br/>

## 五、Service 侧装配与能力开关

### 5.1、Bean 装配

`TodoPlanConfiguration` 注册三个 Bean：

```java
@Configuration
public class TodoPlanConfiguration {

    @Bean
    public TodoPlanTool todoPlanTool() {
        return new TodoPlanTool();
    }

    @Bean
    @ConditionalOnMissingBean(TodoPlanListener.class)
    public TodoPlanListener todoPlanListener() {
        return new ServiceTodoPlanListener();
    }

    @Bean
    public ServiceTodoPlanMiddleware serviceTodoPlanMiddleware(TodoPlanListener todoPlanListener) {
        return new ServiceTodoPlanMiddleware(todoPlanListener);
    }
}
```

`@ConditionalOnMissingBean` 让业务方可以覆盖默认监听器，例如接入审计、指标或消息队列：

```java
@Bean
public TodoPlanListener auditTodoPlanListener() {
    return (todos, context) -> auditService.record(todos, context.getMemoryId());
}
```

### 5.2、Service 中间件：只负责绑定默认监听器

```java
public class ServiceTodoPlanMiddleware extends TodoPlanMiddleware {

    public ServiceTodoPlanMiddleware() {
        this(new ServiceTodoPlanListener());
    }

    public ServiceTodoPlanMiddleware(TodoPlanListener listener) {
        super(listener);
    }
}
```

它没有任何额外逻辑，作用是把「Service 默认监听器」与「框架中间件」解耦：

- 框架层可以独立使用 `TodoPlanMiddleware`；
- Service 层默认获得一个可用的日志监听器；
- 需要替换行为时只需替换 `TodoPlanListener` Bean。

### 5.3、默认监听器：会话级计划日志

```java
@Override
public void onTodoPlan(List<TodoItem> todos, AgentChatContext context) {
    Object sessionId = context == null ? null : context.getMemoryId();
    int total = todos == null ? 0 : todos.size();
    log.info("Todo plan updated: sessionId={}, total={}", sessionId, total);
    for (TodoItem item : todos) {
        TodoStatus status = TodoStatus.fromCode(item.getStatus());
        TodoPriority priority = TodoPriority.fromCode(item.getPriority());
        log.info("  #{} [{}] {} ({})",
                item.getSeq(),
                status == null ? "unknown" : status.getValue(),
                item.getContent(),
                priority == null ? "unknown" : priority.getValue());
    }
}
```

输出示例：

```text
Todo plan updated: sessionId=chat-xxx, total=3
  #1 [completed] 梳理需求与边界 (high)
  #2 [in_progress] 实现 TodoPlanTool (high)
  #3 [pending] 接入前端任务计划 (medium)
```

这条日志可以直接回答：

- 哪个会话更新了计划；
- 计划一共几项；
- 每项的顺序、状态、内容和优先级。

### 5.4、能力开关：`planEnabled`

`AgentCapabilityConfig` 提供开关，默认开启：

```java
public class AgentCapabilityConfig {
    private Boolean planEnabled = true;
}
```

`StandardAgentConfig.isPlanEnabled()` 做空值兜底：

```java
public boolean isPlanEnabled() {
    return capabilityConfig == null
            || capabilityConfig.getPlanEnabled() == null
            || Boolean.TRUE.equals(capabilityConfig.getPlanEnabled());
}
```

配置示例：

```json
{
  "capabilityConfig": {
    "planEnabled": true
  }
}
```

前端 Agent 配置页提供「任务计划」开关，提示文案为：

```text
开启后 Agent 可在多步骤任务中调用 todoplan 维护任务列表
```

### 5.5、ReActAgentFactory 中的三处装配

开关打开时，任务计划能力同时做三件事：

```java
boolean todoEnabled = standardAgentConfig.isPlanEnabled();

if (todoEnabled) {
    middlewares.add(todoPlanMiddleware);
}

String systemPrompt = standardAgentConfig.getSystemPrompt();
if (todoEnabled) {
    systemPrompt = TodoPlanTool.appendSystemPrompt(systemPrompt);
}

// buildToolService(...) 内部
if (todoEnabled) {
    toolService.tools(
            LocalToolFactory.buildLocalToolsWithThought(
                    Collections.<Object>singletonList(todoPlanTool), true));
}
```

| 装配点 | 关闭时 | 开启时 |
|---|---|---|
| 中间件 | 不注册 | 注册 `ServiceTodoPlanMiddleware` |
| 系统提示词 | 保持原样 | 追加 `todoplan` 使用说明 |
| 工具 | 不注册 | 注册 `todoplan` 工具 |

三者必须同步，否则会出现不一致：

- 只加工具不加提示词：模型不知道何时使用；
- 只加提示词不加工具：模型会尝试调用不存在的工具；
- 加工具不加中间件：功能可用，但业务侧拿不到计划监控。

当前实现用同一个 `todoEnabled` 控制三处，避免配置漂移。

### 5.6、中间件顺序

`ReActAgentFactory` 中的注册顺序：

```java
middlewares.add(userMessageAppendMiddleware);
middlewares.add(readArtifactMiddleware);
middlewares.add(compactSummarizationMiddleware);
middlewares.add(new LoggingIAgentMiddleware());
middlewares.add(streamingMiddleware);
middlewares.add(sessionLifecycleMiddleware);
if (todoEnabled) {
    middlewares.add(todoPlanMiddleware);
}
middlewares.add(agentTraceMiddleware);
```

`TodoPlanMiddleware` 位于流式/会话中间件之后、Trace 之前。因为它是观察型中间件，不参与结果改写，顺序主要影响观测语义：

- 放在流式中间件之后：SSE 先发送完成事件，再做计划监听；
- 放在 Trace 之前：Trace 可以间接观测到计划更新发生在工具完成后。

如果未来需要「先写审计再发 SSE」，只需调整注册顺序，不需要改中间件代码。

<br/>

## 六、核心事件协议对接

### 6.1、复用的事件：`tool_call`

任务计划没有独立事件，完整依赖已有的 `tool_call`。协议枚举定义：

```java
TOOL_CALL("tool_call", "工具调用事件", TypeEnum.TOOL_CARD)
```

事件对象 `StreamEventResponse` 的字段：

| 字段 | 说明 |
|---|---|
| `event` | 固定 `tool_call` |
| `type` | 固定 `tool_card` |
| `content` | `ToolCallResult` |
| `isFinish` | 工具是否执行完成 |
| `eventSeq` | 单条 AI 消息内稳定序号 |

`ToolCallResult` 字段：

| 字段 | 说明 | 任务计划中的值 |
|---|---|---|
| `toolCallId` | 工具调用 ID | 用于前端原位替换卡片 |
| `toolName` | 工具名 | `todoplan` |
| `description` | 展示描述 | `工具执行中` / `工具执行完成` |
| `inputParams` | 工具入参原文 | `{"todos":[...]}` 字符串 |
| `outputResult` | 工具结果 | `Plan updated: ...` |
| `duration` | 耗时毫秒 | 用于调试卡片 |

### 6.2、事件何时产生

实时链路由 `ServiceStreamingMiddleware` 负责：

```java
@Override
public void beforeToolExecution(
        ToolExecutionRequest toolRequest, AgentChatContext chatContext) {
    // 记录开始时间，发送 isFinish=false 的 tool_call
    context.startTool(toolRequest);
}

@Override
public String afterToolExecution(
        ToolExecutionRequest toolRequest, String toolResult, AgentChatContext chatContext) {
    // 计算耗时，发送 isFinish=true 的 tool_call
    context.sendTool(toolRequest, toolResult, false);
    return toolResult;
}
```

发送时的组装：

```java
ToolCallResult card = new ToolCallResult();
card.setToolCallId(request.id());
card.setToolName(request.name());
card.setInputParams(request.arguments());
card.setDescription("工具执行中");
send(AiToolCallProcessor.toolCallCard(card, false));
```

完成时补上结果与耗时：

```java
card.setOutputResult(result);
card.setDuration(duration);
card.setDescription("工具执行完成");
send(AiToolCallProcessor.toolCallCard(card, true));
```

并发批次下，每个工具在自己的完成时刻单独发送完成事件：

```java
@Override
public void onConcurrentToolExecutionComplete(
        ToolExecutionRequest toolRequest, String toolResult, Throwable error,
        AgentChatContext chatContext) {
    if (error != null) {
        return;
    }
    context.sendTool(toolRequest, toolResult, false);
}
```

### 6.3、`tool_call` 报文示例

执行中的事件：

```json
{
  "event": "tool_call",
  "type": "tool_card",
  "content": {
    "toolCallId": "call_9f2c",
    "toolName": "todoplan",
    "description": "工具执行中",
    "inputParams": "{\"todos\":[{\"seq\":1,\"content\":\"梳理需求与边界\",\"status\":3,\"priority\":1},{\"seq\":2,\"content\":\"实现 TodoPlanTool\",\"status\":2,\"priority\":1}]}"
  },
  "isFinish": false,
  "eventSeq": 6
}
```

执行完成的事件：

```json
{
  "event": "tool_call",
  "type": "tool_card",
  "content": {
    "toolCallId": "call_9f2c",
    "toolName": "todoplan",
    "description": "工具执行完成",
    "inputParams": "{\"todos\":[{\"seq\":1,\"content\":\"梳理需求与边界\",\"status\":3,\"priority\":1},{\"seq\":2,\"content\":\"实现 TodoPlanTool\",\"status\":2,\"priority\":1}]}",
    "outputResult": "Plan updated: 1 completed, 1 in progress, 0 pending.",
    "duration": 18
  },
  "isFinish": true,
  "eventSeq": 7
}
```

注意：`inputParams` 是**字符串**，内容是工具入参 JSON。前端 `parseInputParams` 同时兼容字符串和对象，先 `JSON.parse` 再读取 `todos`。

### 6.4、事件持久化与回放

`SessionLifecycleMiddleware` 收集同一份事件，写入 AI 消息的 `events` 字段：

```java
@Override
public void beforeToolExecution(
        ToolExecutionRequest toolRequest, AgentChatContext chatContext) {
    state.toolStarted(toolRequest);
    ToolCallResult card = new ToolCallResult();
    card.setToolCallId(toolRequest.id());
    card.setToolName(toolRequest.name());
    card.setInputParams(toolRequest.arguments());
    card.setDescription("Tool execution in progress");
    state.collect(AiToolCallProcessor.toolCallCard(card, false));
}
```

这样实时 SSE 与历史回放使用**同一套事件结构**：

```text
实时：SSE tool_call → applyTool → buildTodoCardPart → TodoBoard
历史：events 数组 → sessionReplay.applyTool → buildTodoCardPart → TodoBoard
```

### 6.5、中间件与事件的边界

| 能力 | 中间件 | 事件 |
|---|---|---|
| 识别 `todoplan` | 是 | 前端自行判断 `toolName` |
| 解析计划 | `TodoPlanTool.parsePlan` | 前端 `JSON.parse(inputParams)` |
| 校验计划 | `TodoValidator` | 依赖后端已校验 |
| 数据用途 | 监控、审计、统计 | 页面渲染 |
| 失败处理 | WARN 日志，忽略 | 解析失败保持普通工具卡片 |

一句话：

> **中间件负责「服务端知道计划变了」，事件负责「客户端看到计划变了」。两者共享同一份工具入参，但互不依赖。**

<br/>

## 七、前端交互效果实现

### 7.1、从工具卡片到任务卡片

前端不新增事件类型，只在 `tool_call` 完成时做一次转换。核心函数位于 `todoEventReducer.js`：

```js
export const TODO_PLAN_TOOL_NAME = 'todoplan'

export function buildTodoCardPart(toolPart) {
  if (!toolPart || toolPart.toolName !== TODO_PLAN_TOOL_NAME) return null
  const params = parseInputParams(toolPart.inputParams)
  if (!params || !Array.isArray(params.todos)) return null

  const todos = params.todos
    .filter((item) => item && item.content)
    .map((item) => ({
      ...item,
      statusKey: STATUS_KEYS[Number(item.status)] || 'pending',
      priorityKey: PRIORITY_KEYS[Number(item.priority)] || 'low'
    }))
    .sort((left, right) => Number(left.seq || 0) - Number(right.seq || 0))

  return {
    type: 'todo_card',
    visible: todos.length > 0,
    toolCallId: toolPart.toolCallId,
    todos,
    summary: {
      total: todos.length,
      pending: count(todos, 'pending'),
      inProgress: count(todos, 'in_progress'),
      completed: count(todos, 'completed'),
      cancelled: count(todos, 'cancelled')
    }
  }
}
```

转换规则：

1. 只处理 `toolName === 'todoplan'`；
2. `inputParams` 解析失败或没有 `todos` 数组时返回 `null`，保持普通工具卡片；
3. 过滤没有 `content` 的脏项；
4. 数字状态/优先级映射为前端 key；
5. 按 `seq` 升序排序；
6. 生成 `summary`，供进度计算使用；
7. 空计划返回 `visible: false`。

### 7.2、实时替换：只在完成后替换一次

发布页 `PublishedAgentChatPage.vue` 的 `applyTool`：

```js
part.isFinish = !!event.isFinish
if (part.isFinish) {
  part.finishedAt = Date.now()
  if (!(part.duration > 0)) {
    part.duration = Math.max(100, part.finishedAt - (part.startedAt || part.finishedAt))
  }
  const todoPart = buildTodoCardPart(part)
  if (todoPart) {
    const index = answer.parts.indexOf(part)
    if (index >= 0) answer.parts.splice(index, 1, todoPart)
  }
}
```

关键行为：

- **执行中不转换**：`isFinish=false` 时仍展示普通工具卡片，避免半成品计划闪动；
- **完成后原位替换**：用 `splice` 替换同一个位置，保持消息时间线顺序；
- **每次调用一个卡片**：模型每次调用 `todoplan` 都会产生一个新的 `TodoBoard`，用户能看到计划的演进过程；
- **非 `todoplan` 工具不受影响**。

### 7.3、调试模式与发布模式

| 维度 | 调试模式 `detailed=true` | 发布模式 `detailed=false` |
|---|---|---|
| 工具展示 | 保留原始 `ToolCard` | 替换为 `TodoBoard` |
| 计划列表 | 不额外渲染 | 每个 `todoplan` 渲染一次 |
| 数据来源 | `tool_call.inputParams` | `tool_call.inputParams` |
| 历史回放 | 还原普通工具卡片 | 还原 `TodoBoard` |

调试模式保留原始工具卡片，便于排查：

- 模型传了什么参数；
- 工具返回了什么；
- 耗时多少；
- 是否有校验错误。

发布模式则把技术细节收起，只呈现用户关心的任务、状态和进度。

### 7.4、历史回放：与实时展示同一语义

`sessionReplay.js` 复用同一个转换函数：

```js
function applyTool(message, event, detailed = false) {
  // 构建 tool_card ...
  part.isFinish = !!event.isFinish
  if (!detailed && part.isFinish) {
    replaceWithTodoCard(message, part)
  }
}

function replaceWithTodoCard(message, part) {
  const todoPart = buildTodoCardPart(part)
  if (!todoPart) return
  const index = message.parts.indexOf(part)
  if (index >= 0) message.parts.splice(index, 1, todoPart)
}
```

因此刷新页面、重新打开历史会话时：

- 同一份 `events` 能还原出与实时一致的卡片；
- 调试模式仍展示普通工具卡片；
- 发布模式展示任务计划列表；
- 每次 `todoplan` 调用恢复一个 `TodoBoard`。

### 7.5、TodoBoard 的展示效果

`TodoBoard.vue` 只负责展示，不做业务判断。整体效果：

```text
┌──────────────────────────────────────────────┐
│ 任务计划   2/3 完成   ███████░░░ 67%    ⌄   │
├──────────────────────────────────────────────┤
│ ✓ 1  梳理需求与边界                  [高]    │
│ ✓ 2  实现 TodoPlanTool               [高]    │
│ ◐ 3  接入前端任务计划                [中]    │
└──────────────────────────────────────────────┘
```

关键计算：

```js
effectiveTotal() {
  return Math.max(0, this.total - this.cancelledCount)
},
percent() {
  if (!this.effectiveTotal) return this.total ? 100 : 0
  return Math.min(100, Math.round((this.completedCount / this.effectiveTotal) * 100))
},
title() {
  if (this.total > 0 && this.completedCount >= this.effectiveTotal) return '任务已完成'
  return this.running ? '任务执行中' : '任务计划'
}
```

设计要点：

| 能力 | 实现 |
|---|---|
| 进度计算 | 排除取消项：`total - cancelled` |
| 当前任务 | 找到 `statusKey === 'in_progress'` 的项并展示「正在执行」 |
| 状态图标 | `completed` 对勾、`cancelled` 移除、`in_progress` 加载、`pending` 时钟 |
| 优先级标签 | `high` 高、`medium` 中、`low` 低 |
| 折叠 | 点击头部展开/收起 |
| 长内容 | 单行省略，`title` 展示完整内容 |
| 更新时间 | 详细模式展示 `updatedAt` |

### 7.6、页面接入位置

```text
ElegantMessage.vue
  └─ part.type === 'todo_card'
        └─ <TodoBoard :state="part" />
```

发布页与调试页只需在 `applyTool` 完成后调用 `buildTodoCardPart`，渲染层统一交给 `ElegantMessage` 的 `todo_card` 分支。这样：

- 消息渲染逻辑不需要知道 `todoplan` 细节；
- 新增其他卡片类型时，可以沿用同一套 `part.type` 分发；
- 调试/发布差异只体现在「是否转换」，不体现在「如何渲染」。

<br/>

## 八、设计取舍与已知边界

### 8.1、为什么中间件不直接推送 SSE

| 方案 | 优点 | 缺点 |
|---|---|---|
| 中间件直接推送 Todo 事件 | 业务语义清晰 | 需要新增事件协议、持久化、回放分支；中间件依赖 SSE 上下文 |
| 复用 `tool_call`（当前） | 零新增协议；实时与回放天然一致 | 前端需要解析工具入参；技术细节暴露在事件中 |

当前选择后者的核心理由：

> **工具入参本身就是单一事实来源。** 再定义一个 `todo` 事件，只是把同一份数据复制一遍，并引入两份数据可能不一致的风险。

### 8.2、全量替换而不是增量更新

| 方案 | 优点 | 缺点 |
|---|---|---|
| 增量 `add/update/delete` | 请求体小 | 工具多、状态合并复杂、回放难 |
| 全量替换（当前） | 工具单一、快照完整、回放简单 | 每次都要传完整列表 |

任务计划通常只有几项到几十项，全量替换的成本可以接受，换来的是显著更低的实现与调试复杂度。

### 8.3、并发批次只通知最新计划

当前 `afterConcurrentToolExecution` 从后往前找到第一个 `todoplan` 后 `break`：

- 监控侧只关心当前有效计划，避免重复上报；
- 前端仍会为每个 `todoplan` 调用渲染卡片，展示完整演进过程。

这是一处有意的口径差异。如果业务需要审计每一次计划变更，可以改为遍历并逐个通知。

### 8.4、已知边界

| 边界 | 说明 |
|---|---|
| 不做跨轮状态存储 | 当前监听器不持久化计划，只打印日志；跨轮状态由工具调用历史自然表达 |
| 不发送专用事件 | 前端完全依赖 `tool_call`，不做 `todo` SSE |
| 失败计划不通知 | `todoplan` 校验失败走 `onToolExecutionError`，中间件不回调 |
| 并发批次只通知一次 | 取批次中最后一个 `todoplan` |
| 监听器需自行保证线程安全 | 并发批次可能在工具线程池执行 |
| 不限制模型是否调用 | 是否制定计划由系统提示词引导，不强制 |
| 空计划不展示 | 前端 `visible=false`，但工具仍会更新为空列表 |

<br/>

## 九、验证清单与扩展方向

### 9.1、后端验证清单

| 场景 | 预期 |
|---|---|
| 合法计划，3 项 | `TodoValidator` 按 `seq` 排序返回，工具返回状态统计 |
| 31 项 | 校验失败，工具返回错误 |
| `content` 301 字 | 校验失败 |
| `seq` 重复 / 非正数 | 校验失败 |
| 两个 `in_progress` | 校验失败 |
| `status=5` / `priority=4` | 校验失败 |
| 空数组 | 校验通过，返回 0 统计 |
| `parsePlan` 缺少 `todos` | 抛 `IllegalArgumentException`，中间件记 WARN |
| 串行 `todoplan` 成功 | `TodoPlanListener` 被调用一次 |
| 非 `todoplan` 工具 | 监听器不调用 |
| 并发批次含多个 `todoplan` | 只通知最后一个一次 |
| 监听器抛异常 | 工具结果不变，Agent 继续执行 |

对应测试：

- `TodoValidatorTest`：数量、长度、`seq`、`in_progress`、去空格、排序；
- `TodoPlanToolTest`：数字解析、空参数、缺少 `todos`、工具执行、嵌套 Schema、提示词拼接。

### 9.2、前端验证清单

| 场景 | 预期 |
|---|---|
| 发布模式 `todoplan` 完成 | 工具卡片原位替换为 `TodoBoard` |
| 调试模式 | 保持普通 `ToolCard` |
| 每次 `todoplan` 调用 | 渲染一个 `TodoBoard` |
| `todos=[]` | 不展示 `TodoBoard` |
| `inputParams` 非法 JSON | 保持普通工具卡片 |
| 刷新历史会话 | 与实时展示一致 |
| 取消项 | 不计入进度分母 |
| 全部完成 | 标题变为「任务已完成」 |
| 多个 `in_progress` | 后端拒绝，前端不会收到成功事件 |

### 9.3、可扩展方向

| 方向 | 做法 |
|---|---|
| 审计落库 | 自定义 `TodoPlanListener`，把每次计划写入审计表 |
| 指标统计 | 监听器上报计划项数、完成率、平均推进步数 |
| 消息通知 | 监听器在计划完成时发 MQ / Webhook |
| 跨轮计划恢复 | 新增 `TodoPlanStore`，由监听器写入、会话启动时恢复 |
| 每次计划都上报 | 修改并发批次逻辑，遍历并逐个通知 |
| 独立 Todo 事件 | 新增 `todo` SSE + 回放分支，当前阶段不推荐 |
| 前端增量合并 | 当前不建议；全量快照更简单可靠 |

<br/>

## 十、总结

`TodoPlanMiddleware` 是「工具 + 观察型中间件 + 事件复用」组合的一个完整样本：

1. **工具是事实来源**：`TodoPlanTool` 用一次全量替换表达完整计划，`TodoValidator` 把约束前移到工具边界；
2. **中间件只做旁路**：`afterToolExecution` / `afterConcurrentToolExecution` 识别 `todoplan`，把计划交给 `TodoPlanListener`，不改写结果、不发送事件、不持有状态；
3. **异常完全隔离**：解析失败、监听器异常只记 WARN，Agent 主流程不受影响；
4. **装配三件套同步**：中间件、工具、系统提示词统一由 `planEnabled` 控制，避免配置漂移；
5. **事件协议零新增**：`tool_call` 已携带 `toolName`、`inputParams`、`isFinish` 和持久化能力，前端直接解析即可渲染；
6. **实时与回放同语义**：`applyTool` 与 `sessionReplay.applyTool` 共用 `buildTodoCardPart`，刷新后效果一致；
7. **交互效果清晰**：调试模式看原始工具卡片，发布模式看 `TodoBoard`，进度排除取消项，当前任务高亮展示。

它验证了中间件设计的另一个核心命题：

> **业务能力不一定需要新的协议或新的内核钩子；把「结构化工具」和「观察型中间件」组合起来，再复用现有事件，就能形成完整闭环。**

当需要更强的计划能力时，扩展路径也很清楚：监听器接审计/指标/消息队列，或新增 Store 做跨轮恢复；这些变化都停留在业务侧，不需要修改 ReAct 主循环、事件协议或前端渲染骨架。

<br/>

## 参考资料

- 工具与模型：`agentforge-agent-core/src/main/java/cloud/changlu/agentforge/agent/extend/middlewares/todoplan/`
- 中间件契约：`component/middleware/IAgentMiddleware.java`
- 中间件分发：`component/middleware/AgentMiddlewareManager.java`
- Service 装配：`service/modules/agent/config/TodoPlanConfiguration.java`
- Service 中间件：`service/modules/agent/middleware/todoplan/`
- 事件发送：`service/modules/agent/middleware/ServiceStreamingMiddleware.java`
- 事件持久化：`service/modules/agent/middleware/SessionLifecycleMiddleware.java`
- 前端转换：`agentforge-service-ui/src/utils/todoEventReducer.js`
- 前端回放：`agentforge-service-ui/src/utils/sessionReplay.js`
- 前端组件：`agentforge-service-ui/src/components/TodoBoard.vue`
- 测试：`TodoValidatorTest`、`TodoPlanToolTest`
- 业务落地篇：[Agent支持任务计划todo列表展示.md](../../agentforge-service核心业务模块/后端/agent核心设计实现/Agent页面会话对话交互/Agent支持任务计划todo列表展示.md)
- 同系列：
  - [核心ReAct 框架设计，支持非流式、流式 一套调用.md](../%E6%A0%B8%E5%BF%83ReAct%20%E6%A1%86%E6%9E%B6%E8%AE%BE%E8%AE%A1%EF%BC%8C%E6%94%AF%E6%8C%81%E9%9D%9E%E6%B5%81%E5%BC%8F%E3%80%81%E6%B5%81%E5%BC%8F%20%E4%B8%80%E5%A5%97%E8%B0%83%E7%94%A8.md)
  - [标准Agent的middleware接口扩展实现.md](%E6%A0%87%E5%87%86Agent%E7%9A%84middleware%E6%8E%A5%E5%8F%A3%E6%89%A9%E5%B1%95%E5%AE%9E%E7%8E%B0.md)
  - [内置middleware扩展：日志Loggingmiddlware实现.md](%E5%86%85%E7%BD%AEmiddleware%E6%89%A9%E5%B1%95%EF%BC%9A%E6%97%A5%E5%BF%97Loggingmiddlware%E5%AE%9E%E7%8E%B0.md)
  - [内置middlware扩展：Agent Loop TraceMiddlware实现.md](%E5%86%85%E7%BD%AEmiddlware%E6%89%A9%E5%B1%95%EF%BC%9AAgent%20Loop%20TraceMiddlware%E5%AE%9E%E7%8E%B0.md)

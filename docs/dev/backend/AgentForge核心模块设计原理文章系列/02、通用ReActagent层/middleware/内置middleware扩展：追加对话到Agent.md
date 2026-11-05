# AgentForge ReAct 层05、内置 Middleware 扩展：追加对话到 Agent Loop

> 适用版本：release_1.x
> 适用模块：`agentforge-framework/agentforge-agent-core`
> Core 包路径：`cloud.changlu.agentforge.agent.extend.middlewares.append`
> 维护者：长路

系列定位：本文属于「AgentForge 核心模块设计原理文章系列 / 02、通用 ReAct Agent 层 / middleware」。前文介绍了 Middleware 的生命周期和回调链，本篇基于当前仓库已经落地的 `append` 实现，回答两个问题：

1. 运行中的 Agent 如何接收一条新的用户消息，并让下一次模型调用看到它；
2. Core 为什么要把能力拆成 Service、Store、Middleware 和事件监听器，以及后续应该从哪些扩展点继续演进。

> **核心结论：运行期追加消息不是简单地 `ChatMemory.add(...)`。追加消息先进入按 `memoryId` 隔离的待处理队列，再由 `UserMessageAppendMiddleware` 在 `beforeModelCall` 中一次性取出、写入 Memory，并重建当前 `ChatRequest`；这样当前这一次模型调用就能看到追加内容。**

<br/>

## 文档导航

| 文档 | 说明 |
|---|---|
| [核心ReAct 框架设计，支持非流式、流式 一套调用.md](../%E6%A0%B8%E5%BF%83ReAct%20%E6%A1%86%E6%9E%B6%E8%AE%BE%E8%AE%A1%EF%BC%8C%E6%94%AF%E6%8C%81%E9%9D%9E%E6%B5%81%E5%BC%8F%E3%80%81%E6%B5%81%E5%BC%8F%20%E4%B8%80%E5%A5%97%E8%B0%83%E7%94%A8.md) | 第 01 篇：ReAct 主循环、流式递归和状态机 |
| [标准Agent的middleware接口扩展实现.md](标准Agent的middleware接口扩展实现.md) | Middleware 契约、管理器分发和生命周期接入点 |
| [内置middleware扩展：日志Loggingmiddlware实现.md](内置middleware扩展：日志Loggingmiddlware实现.md) | 日志 Middleware 的观察型实现 |
| 当前文章 | 运行期追加消息的 Core 封装、注入边界与扩展方式 |

<br/>

## 目录

- [一、问题定义与 Core 边界](#一问题定义与-core-边界)
- [二、为什么选择 `beforeModelCall`](#二为什么选择-beforemodelcall)
- [三、Core 的标准封装分层](#三core-的标准封装分层)
- [四、领域对象与追加窗口](#四领域对象与追加窗口)
- [五、Store 如何保证 FIFO 与关闭一致性](#五store-如何保证-fifo-与关闭一致性)
- [六、Middleware 如何把消息送进当前模型请求](#六middleware-如何把消息送进当前模型请求)
- [七、事件状态与 Service 层适配](#七事件状态与-service-层适配)
- [八、如何装配到 ReAct Agent](#八如何装配到-react-agent)
- [九、并发与边界语义](#九并发与边界语义)
- [十、后续扩展实现指南](#十后续扩展实现指南)
- [十一、测试设计与总结](#十一测试设计与总结)

---

## 一、问题定义与 Core 边界

### 1.1、运行期追加消息解决什么问题

一次 ReAct 运行可能经历多次模型调用：

```text
用户问题
  -> 第 1 次模型调用
  -> 工具调用
  -> 第 2 次模型调用
  -> 工具调用
  -> 第 3 次模型调用
  -> 最终回答
```

在工具执行或模型思考期间，用户可能补充新的约束：

- “不要修改数据库结构”；
- “实现完成后补充单元测试”；
- “后续优先使用 Redis，不要再读本地缓存”。

这类消息的语义不是重新启动一个 Agent Run，而是进入当前 Run 的待处理队列，在后续某一次模型调用前生效。

### 1.2、Core 负责什么

当前 `agentforge-agent-core` 中，追加能力由以下几部分组成：

```text
append/
├── UserMessageAppendMiddleware.java
├── domain/
│   ├── AppendedUserMessage.java
│   └── UserMessageAppendResult.java
├── event/
│   └── UserMessageAppendEventListener.java
├── service/
│   └── IUserMessageAppendService.java
└── store/
    ├── UserMessageAppendStore.java
    ├── AbstractUserMessageAppendStore.java
    └── InMemoryUserMessageAppendStore.java
```

Core 的职责是：

1. 定义一条追加消息的最小领域对象；
2. 通过 `IUserMessageAppendService` 暴露追加、取消、消费和关闭契约；
3. 通过 `UserMessageAppendStore` 抽象具体存储；
4. 在 Agent Run 开始时打开追加窗口，在模型调用边界消费消息；
5. 在 Run 结束时关闭窗口，并把未消费消息通知为延后；
6. 通过事件监听器把状态变化交给上层。

Core 不负责：

- 当前登录用户是否有权限操作某个会话；
- HTTP 接口、状态码和请求参数格式；
- SSE、WebSocket 或消息总线的具体协议；
- MySQL、Redis 等基础设施的连接和事务配置；
- 跨节点查找某个 Agent Run。

因此，Core 中没有新增 `ReActAgent.appendUserMessage(...)` 之类的业务入口。上层可以把同一个 `IUserMessageAppendService` 注入 HTTP 入口和 Middleware，让两者共享队列，但不需要侵入 ReAct 主循环。

### 1.3、当前实现的基本假设

当前队列以 `memoryId` 作为隔离键，隐含以下运行约束：

- 同一个 `memoryId` 在同一时刻只对应一个活跃 Run；
- 追加消息按 FIFO 顺序处理；
- 每次 `beforeModelCall` 消费当时队列中的全部消息；
- 已经发出的模型请求不会被追加消息中途修改；
- 追加消息未被消费时，Run 结束会进入延后语义。

如果未来同一会话允许并发 Run，需要把队列键升级为 `memoryId + runId`，不能继续只靠 `memoryId` 区分消息归属。

<br/>

## 二、为什么选择 `beforeModelCall`

### 2.1、同步和流式都会经过同一个模型调用前回调

非流式 `ReActAgent.think()` 的关键顺序是：

```text
ChatMemory.messages()
  -> 构建 ChatRequest
  -> AgentMiddlewareManager.triggerBeforeModelCall(...)
  -> ChatModel.chat(processedRequest)
```

流式 `ReActTokenStream.chat()` 也采用同样的顺序：

```text
ChatMemory.messages()
  -> 构建 ChatRequest
  -> AgentMiddlewareManager.triggerBeforeModelCall(...)
  -> StreamingChatModel.chat(processedRequest, handler)
```

所以 `UserMessageAppendMiddleware` 只实现 `IAgentMiddleware`，就能同时覆盖：

- `run()` 的非流式模型调用；
- `runStream()` 的流式模型调用；
- 第 1 轮以及工具调用后的后续每一轮模型调用。

不需要修改 `ReActAgent.think()` 或 `ReActTokenStream` 的主循环，只要把 Middleware 注册进去即可。

### 2.2、只修改 Memory 为什么不够

在 `beforeModelCall` 被触发时，当前 `ChatRequest` 已经由 Memory 创建了消息快照。下面的实现并不完整：

```java
@Override
public ChatRequest beforeModelCall(
        int currentStep, ChatRequest request, AgentChatContext context) {
    context.getChatMemory().add(UserMessage.from("请补充单测"));
    return request;
}
```

这条消息虽然进入了 `ChatMemory`，但 `request.messages()` 仍是旧列表。当前轮模型调用看不到它；只有下一轮重新从 Memory 构建请求时才可能看到。如果当前轮直接返回最终回答，这条消息就不会被模型读取。

正确做法是写入 Memory 后重新构建请求，并保留原请求参数：

```java
context.getChatMemory().add(UserMessage.from(content));

return ChatRequest.builder()
        .messages(context.getChatMemory().messages())
        .parameters(request.parameters())
        .build();
```

这里保留 `request.parameters()` 很重要。当前参数中可能包含工具声明、模型配置或其他调用方设置；只替换 `messages` 而丢掉参数，会造成追加消息生效但工具能力消失等隐蔽问题。

### 2.3、模型调用边界是一次提交点

当前实现把追加消息的生效边界收敛为：

```text
drain 队列
  -> 合并为一条 UserMessage
  -> 写入 ChatMemory
  -> 重建 ChatRequest
  -> 发布 effective 事件
  -> 返回新请求并调用模型
```

“下一次模型调用前”比“收到消息立即打断模型”更容易保持 ReAct Loop 的一致性：在途请求不被修改，工具执行不被强行中断，下一次模型调用自然获得最新上下文。

<br/>

## 三、Core 的标准封装分层

这部分实现的关键不是单独写一个 Middleware，而是把“排队”和“注入”拆开：

```text
HTTP / WebSocket / 内部业务调用
          |
          v
IUserMessageAppendService
          |
          v
UserMessageAppendStore
          |
          +--> InMemoryUserMessageAppendStore
          +--> Service 层 MysqlUserMessageAppendStore

ReAct Loop
    |
    v
UserMessageAppendMiddleware
    |-- appendService.open(memoryId)
    |-- appendService.drain(memoryId)
    |-- ChatMemory.add(...)
    |-- rebuild ChatRequest
    +-- UserMessageAppendEventListener
```

四层各自只解决一个问题：

| 层 | 当前类型 | 主要职责 |
|---|---|---|
| 领域对象 | `AppendedUserMessage`、`UserMessageAppendResult` | 表达消息、入队结果和队列位置 |
| Service | `IUserMessageAppendService` | 对外提供稳定的追加、取消、消费、关闭门面 |
| Store | `UserMessageAppendStore` 及其抽象实现 | 管理窗口、FIFO、容量和并发一致性 |
| Middleware | `UserMessageAppendMiddleware` | 把队列消息注入当前 Agent Loop，并在停止时收口 |
| 事件适配 | `UserMessageAppendEventListener` | 把 Core 状态变化交给 SSE、审计或持久化层 |

这里的“标准”体现在依赖方向：Middleware 依赖 Service 接口，Service 依赖 Store 接口，Core 不依赖 Spring 和数据库实现。后续替换存储时，Agent Loop 不需要改动。

<br/>

## 四、领域对象与追加窗口

### 4.1、`AppendedUserMessage`：一条待生效消息

当前实现是一个不可变对象：

```java
public final class AppendedUserMessage {
    private final Long appendId;
    private final String content;
    private final long queuedAt;

    public AppendedUserMessage(Long appendId, String content, long queuedAt) {
        this.appendId = appendId;
        this.content = content;
        this.queuedAt = queuedAt;
    }
}
```

字段含义：

| 字段 | 语义 |
|---|---|
| `appendId` | 追加消息 ID，用于前端关联状态和精确取消；内存 Store 可以本地生成，数据库 Store 可以回填自增主键 |
| `content` | 已提交的用户追加内容；当前 Service 会先 `trim()`，拒绝空白消息 |
| `queuedAt` | 进入队列的 Unix 毫秒时间 |

Core 不在领域对象里保存 `status`。当前实现通过 Store 操作和事件监听器表达生命周期，Service 层可以把事件映射成自己的枚举或协议字段。

### 4.2、`UserMessageAppendResult`：显式表达入队结果

追加不应该只返回 `boolean`。当前结果对象支持：

```java
public enum Code {
    QUEUED,
    INVALID_ARGUMENT,
    RUN_NOT_OPEN,
    RUN_ALREADY_CLOSED,
    QUEUE_FULL
}
```

成功时还携带：

- `message`：已经被 Store 接收的消息，包含最终 `appendId`；
- `queuePosition`：入队后的队列位置，从 1 开始。

不同拒绝原因需要被区分：

| Code | 含义 |
|---|---|
| `QUEUED` | 消息已进入待处理队列 |
| `INVALID_ARGUMENT` | `memoryId` 为空或消息为空白；当前由 Service 校验 |
| `RUN_NOT_OPEN` | 当前 `memoryId` 尚未打开追加窗口 |
| `RUN_ALREADY_CLOSED` | Run 已关闭，不能再追加 |
| `QUEUE_FULL` | 达到 Store 配置的待处理消息上限 |

显式结果让上层可以稳定映射 HTTP 响应、SSE 事件和测试断言，也避免用异常表示正常的业务拒绝。

### 4.3、`IUserMessageAppendService`：Core 对外门面

当前 Core 提供的标准服务接口是：

```java
public interface IUserMessageAppendService {
    boolean open(Object memoryId);

    UserMessageAppendResult append(Object memoryId, String content);

    List<AppendedUserMessage> drain(Object memoryId);

    Optional<AppendedUserMessage> cancel(Object memoryId, Long appendId);

    List<AppendedUserMessage> closeAndDrain(Object memoryId);

    void clear(Object memoryId);

    int size(Object memoryId);
}
```

这个接口有两个使用方：

1. 外部入口调用 `append/cancel`；
2. `UserMessageAppendMiddleware` 调用 `open/drain/closeAndDrain`。

两者共享同一个 Service 实例，才能操作同一份队列。Core 不要求 Service 必须由 Spring 管理，普通 Java 程序也可以提供自己的实现。

### 4.4、Store 的生命周期协议

`UserMessageAppendStore` 把一次运行期间的窗口抽象为：

```text
open
  -> enqueue / remove
  -> drain      （窗口仍然 OPEN）
  -> closeAndDrain
```

其中：

- `open(memoryId)`：打开窗口；重复打开仍处于 OPEN 的窗口返回 `false`；
- `enqueue(...)`：检查窗口状态和队列容量后追加到队尾；
- `remove(...)`：只删除尚未被 `drain` 消费的指定消息；
- `drain(...)`：按 FIFO 原子取出当前全部待处理消息，窗口继续开放；
- `closeAndDrain(...)`：原子关闭窗口，并取出全部残留消息；
- `clear(...)`：强制清理指定会话的窗口和消息；
- `size(...)`：读取当前待处理数量。

`drain` 之后窗口不能立即关闭，因为工具调用完成后可能还会进入下一轮模型调用；只有 Agent 进入统一停止回调时，Middleware 才执行 `closeAndDrain`。

<br/>

## 五、Store 如何保证 FIFO 与关闭一致性

### 5.1、抽象 Store 把并发协议集中起来

`AbstractUserMessageAppendStore` 不保存具体消息字段，而是集中实现通用控制逻辑：

```java
private final ConcurrentHashMap<Object, RunState> states =
        new ConcurrentHashMap<Object, RunState>();

private static final class RunState {
    private final ReentrantLock lock = new ReentrantLock();
    private boolean open;
}
```

子类只需要实现六个 CRUD 形态的方法：

```java
protected abstract AppendedUserMessage insertMessage(
        Object memoryId, AppendedUserMessage message);

protected abstract List<AppendedUserMessage> listMessages(Object memoryId);

protected abstract Optional<AppendedUserMessage> removeMessage(
        Object memoryId, Long appendId);

protected abstract void deleteMessages(
        Object memoryId, List<AppendedUserMessage> messages);

protected abstract void clearMessages(Object memoryId);

protected abstract int countMessages(Object memoryId);
```

这样可以把“数据库如何查询”与“什么时候允许查询”分离。所有对同一 `memoryId` 的窗口操作，都在对应的 `RunState.lock` 中完成。

### 5.2、容量检查和入队必须在同一个锁内

`enqueue` 的核心顺序是：

```text
找到 RunState
  -> 加锁
  -> 判断 open
  -> countMessages
  -> 判断 maxPending
  -> insertMessage
  -> 返回 queuePosition
  -> 解锁
```

不能把容量检查和入队拆成两个没有锁保护的动作：

```java
// 错误示例
if (queue.size() < maxPending) {
    queue.offer(message);
}
```

并发请求可能同时看到同一个旧容量，从而突破上限。当前 `InMemoryUserMessageAppendStore` 使用 `ArrayDeque` 保存消息，但依靠外层 `RunState.lock` 保证访问顺序，而不是把 `ArrayDeque` 当作并发队列直接使用。

### 5.3、关闭窗口解决 stop/enqueue 竞态

如果只有 `enqueue` 和 `drain`，会出现：

```text
Agent 线程：最后一次 drain 为空
Agent 线程：结束运行
调用线程：         enqueue 成功
```

这条消息既没有进入模型请求，也没有明确的终态。

当前 `closeAndDrain` 在同一个锁内完成：

```text
加锁
  -> open = false
  -> drainMessages
  -> 解锁
```

因此只会有两种结果：

1. `enqueue` 先完成，`closeAndDrain` 会把消息取出，Middleware 发布 `deferred`；
2. `closeAndDrain` 先完成，后续 `enqueue` 返回 `RUN_ALREADY_CLOSED`。

这就是“追加消息最终要么进入某次模型请求，要么被明确延后”的一致性边界。

### 5.4、InMemory 实现与可替换性

`InMemoryUserMessageAppendStore` 的职责很单纯：

- 用 `ConcurrentHashMap<Object, ArrayDeque<AppendedUserMessage>>` 按 `memoryId` 保存队列；
- 用 `AtomicLong` 为没有 ID 的消息生成本地 `appendId`；
- `insertMessage` 尾插，`listMessages` 按队列顺序复制，`removeMessage` 按 ID 精确删除；
- 其余并发和窗口逻辑由抽象父类负责。

Service 模块中的 `MysqlUserMessageAppendStore` 复用了同一个抽象父类，只把这六个方法映射到 Mapper。这个例子说明：扩展持久化时，不需要复制 Middleware，也不需要改 ReAct Agent。

<br/>

## 六、Middleware 如何把消息送进当前模型请求

### 6.1、四个生命周期回调

`UserMessageAppendMiddleware` 只实现四个回调：

| 回调 | 当前实现 |
|---|---|
| `onInitComplete` | `appendService.open(memoryId)`，为本次 Run 打开窗口 |
| `beforeModelCall` | drain、合并消息、写入 Memory、重建 Request、通知 effective |
| `onStop` | `closeAndDrain`，通知剩余消息 deferred |
| `onStopWithError` | 与正常停止相同的关闭逻辑 |

它不实现流式增量回调，因为追加消息只关心“下一次模型调用前”的统一边界。

### 6.2、当前实现的核心代码

```java
public class UserMessageAppendMiddleware implements IAgentMiddleware {
    private final IUserMessageAppendService appendService;
    private final UserMessageAppendEventListener eventListener;

    public UserMessageAppendMiddleware(
            IUserMessageAppendService appendService,
            UserMessageAppendEventListener eventListener) {
        this.appendService = appendService;
        this.eventListener = eventListener;
    }

    @Override
    public void onInitComplete(AgentChatContext context) {
        appendService.open(context.getMemoryId());
    }

    @Override
    public ChatRequest beforeModelCall(
            int currentStep,
            ChatRequest request,
            AgentChatContext context) {
        List<AppendedUserMessage> messages =
                appendService.drain(context.getMemoryId());
        if (messages.isEmpty()) {
            return request;
        }

        context.getChatMemory().add(
                UserMessage.from(mergeMessages(messages)));

        ChatRequest rebuilt = ChatRequest.builder()
                .messages(context.getChatMemory().messages())
                .parameters(request.parameters())
                .build();

        for (AppendedUserMessage message : messages) {
            eventListener.effective(
                    context.getMemoryId(), message, currentStep);
        }
        return rebuilt;
    }

    @Override
    public void onStop(
            int currentStep,
            StopResult result,
            AgentChatContext context) {
        close(context);
    }

    @Override
    public void onStopWithError(
            int currentStep,
            Throwable error,
            AgentChatContext context) {
        close(context);
    }

    private void close(AgentChatContext context) {
        for (AppendedUserMessage message :
                appendService.closeAndDrain(context.getMemoryId())) {
            eventListener.deferred(context.getMemoryId(), message);
        }
    }
}
```

### 6.3、为什么批量消息合并成一条 UserMessage

当前 `mergeMessages` 的语义是：同一个模型调用边界 drain 出来的多条消息，合并成一条用户消息，并使用明确的运行期补充提示：

```java
private String mergeMessages(List<AppendedUserMessage> messages) {
    return messages.stream()
            .map(message -> "- " + message.getContent())
            .collect(Collectors.joining(
                    "\n",
                    "The user added the following instructions "
                            + "during the current Agent run:\n",
                    ""));
}
```

例如队列中依次是：

```text
先不要修改数据库
完成后补充测试
```

模型实际看到的是一条新的 `UserMessage`，内容保持 FIFO：

```text
The user added the following instructions during the current Agent run:
- 先不要修改数据库
- 完成后补充测试
```

这样既保留了追加消息的顺序，又不会把一次批量消费拆成多个彼此独立的用户轮次。需要注意：这是一种当前实现的提示词策略，不是 Store 的硬性要求，后续可以替换为结构化消息或逐条注入。

### 6.4、当前实现中的提交顺序

`beforeModelCall` 的顺序非常关键：

```text
1. drain：消息从待处理队列移出
2. ChatMemory.add：写入会话记忆
3. rebuild：用最新 Memory 和原 parameters 构建请求
4. effective：通知上层消息已进入当前模型请求
5. return：交给 AgentMiddlewareManager 继续调用模型
```

只有第 3 步完成后，消息才具备 `effective` 的事实基础。这里的 `effective` 不是“用户提交成功”，而是“该条消息已写入 Memory，并被这一次即将发出的模型请求包含”。

当前代码没有在 Middleware 内捕获 `eventListener` 的异常。如果监听器在第 4 步抛出异常，`AgentMiddlewareManager` 会按通用 Middleware 规则记录异常并继续，但由于本方法没有正常返回，管理器可能继续使用上一个 `ChatRequest`。因此，实际接入时事件监听器应保持轻量且不抛异常；后续扩展部分会说明更稳妥的隔离方式。

<br/>

## 七、事件状态与 Service 层适配

### 7.1、Core 使用监听器，而不是绑定具体事件协议

Core 的监听器接口是：

```java
public interface UserMessageAppendEventListener {
    void queued(Object memoryId, AppendedUserMessage message, int queuePosition);

    void effective(Object memoryId, AppendedUserMessage message, int currentStep);

    void deferred(Object memoryId, AppendedUserMessage message);

    void canceled(Object memoryId, AppendedUserMessage message);
}
```

它没有依赖 SSE DTO、数据库 POJO 或 Spring 事件。上层可以把这些回调映射为任意协议。

### 7.2、四种状态的事实来源

当前 Service 层把四个回调映射为 `QUEUED/EFFECTIVE/DEFERRED/CANCELED`。它们的事实来源不同：

| 回调 | 由谁触发 | 表示什么 |
|---|---|---|
| `queued` | 外部追加入口在 `append` 返回成功后触发 | 消息已入队，模型还没有看到 |
| `effective` | Core Middleware 在 `beforeModelCall` 中触发 | 消息已经进入当前模型请求 |
| `deferred` | Core Middleware 在 `onStop/onStopWithError` 中触发 | Run 结束时仍未被模型消费 |
| `canceled` | 外部取消入口在 `cancel` 成功后触发 | 消息在 drain 前被移除 |

因此，`queued` 和 `canceled` 不是 `UserMessageAppendMiddleware` 负责的。当前工程中，Controller 调用 Service 后，再调用同一个 Listener 发布这两类事件；Middleware 负责 `effective/deferred`。

### 7.3、推荐的上层适配方式

Service 层可以同时做三件事：

```text
Core Listener
  -> 发布当前连接的 SSE / WebSocket 事件
  -> 收集会话事件历史
  -> 更新前端可恢复的状态
```

但这些动作属于旁路能力，不能反过来影响 Agent Loop。建议：

- Listener 不做长耗时网络调用；
- Listener 内部捕获外部系统异常，避免回调异常影响模型请求；
- 事件内容携带 `appendId`，让前端能把 QUEUED、EFFECTIVE、DEFERRED、CANCELED 关联到同一条消息；
- `effectiveStep` 只在生效事件中填写，表示消息进入的 Agent Loop 步骤。

<br/>

## 八、如何装配到 ReAct Agent

### 8.1、Builder 只负责按顺序注册 Middleware

当前 `ReActAgentBuilder` 提供：

```java
public ReActAgentBuilder middleware(IAgentMiddleware middleware);

public ReActAgentBuilder middlewares(
        List<IAgentMiddleware> middlewares);
```

`ReActAgent` 构造时把这些 Middleware 注册到 `AgentMiddlewareManager`，管理器按注册顺序触发回调。因此追加能力的接入不需要给 `ReActAgent` 增加专用字段。

当前 Service 层工厂的装配顺序是：

```java
List<IAgentMiddleware> middlewares =
        Arrays.<IAgentMiddleware>asList(
                userMessageAppendMiddleware,
                new LoggingIAgentMiddleware(),
                sessionLifecycleMiddleware,
                streamingMiddleware,
                agentTraceMiddleware);

ReActAgent agent = ReActAgent.builder()
        .agentName(agentName)
        .chatModel(chatModel)
        .streamingChatModel(streamingChatModel)
        .chatMemoryProvider(chatMemoryProvider)
        .middlewares(middlewares)
        .toolService(toolService)
        .build();
```

追加 Middleware 放在前面，是为了让后续的日志、会话事件和 Trace 看到已经包含追加内容的 `ChatRequest`。如果某个业务 Middleware 也会改写请求，应该明确它们的注册顺序。

### 8.2、Service 组件的实际依赖关系

当前工程的 Service 装配可以简化为：

```text
MysqlUserMessageAppendStore
          |
          v
UserMessageAppendServiceImpl
          |
          +--> UserMessageAppendController
          +--> UserMessageAppendMiddleware

ServiceUserMessageAppendEventListener
          |
          +--> SSE / 会话事件历史
```

这里的关键是 Controller 和 Middleware 注入同一个 `IUserMessageAppendService`。追加请求只负责入队，Agent 执行线程在后续 `beforeModelCall` 消费；两者不需要共享线程，也不需要直接调用对方。

### 8.3、不要把 append 直接塞进 Agent 主类

下面这种设计会让 Agent 同时承担业务队列、存储和协议职责：

```java
// 不推荐
class ReActAgent {
    private final Map<Object, Queue<String>> appendQueues;
    public void appendUserMessage(Object memoryId, String content) { ... }
}
```

它会带来几个问题：

- Agent 被固定绑定一种队列存储；
- 同一个 Agent 实例复用多个会话时，生命周期更难管理；
- HTTP 入口无法复用同一套校验和结果码；
- 后续迁移 MySQL、Redis 时需要修改 Agent Core。

当前的 Service/Store/Middleware 分层已经把这些变化隔离开，新增业务入口只需要调用 Service。

<br/>

## 九、并发与边界语义

### 9.1、按 `memoryId` 隔离

一个 `ReActAgent` 可以被多个会话复用，因此待处理消息不能放在单个全局队列中。当前模型是：

```text
memoryId -> RunState(lock, open) -> 具体 Store 中的消息集合
```

不同 `memoryId` 可以并发；同一个 `memoryId` 的 `enqueue/remove/drain/closeAndDrain` 由同一个 `ReentrantLock` 串行化。

### 9.2、追加和取消的竞态

同一个消息的 `cancel` 与 `drain` 只能有一个先拿到锁：

```text
remove 先拿锁
  -> remove 成功
  -> 外部发布 CANCELED
  -> drain 看不到该消息

drain 先拿锁
  -> 消息进入当前消费批次
  -> remove 返回 empty
  -> Middleware 最终发布 EFFECTIVE
```

外部入口可以把 `Optional.empty()` 映射为“已生效或已进入终态”，不应该再尝试重复取消。

### 9.3、追加发生在模型请求已经发出之后

模型请求一旦发出，追加消息不会修改这次在途请求：

- 如果模型返回工具调用，下一轮 `beforeModelCall` 会消费并生效；
- 如果模型直接给出最终回答，Run 会进入停止回调，追加消息被 `closeAndDrain` 取出并标记为 `deferred`；
- Core 不承诺为了追加消息中断、重启或回滚当前模型调用。

这使消息语义清晰：`effective` 表示进入了某次请求，`deferred` 表示本次 Run 没有机会消费。

### 9.4、打开窗口的时机

`onInitComplete` 在同步 `run()` 开始后、第一轮循环前触发；流式路径在 `TokenStream.start()` 时触发。于是：

- Agent 尚未开始这次 Run 时，`append` 可能返回 `RUN_NOT_OPEN`；
- 同一 `memoryId` 已有 OPEN 窗口时，`open` 返回 `false`；
- 当前 Middleware 没有使用 `open` 的返回值，应用层应保证同一 `memoryId` 不并发启动两个 Run；
- 关闭后再次开始新 Run 时，Store 可以重新打开窗口，并在 `open` 时清理存储中的旧消息。

如果要从 Core 层强制阻止同一会话重入，应该新增显式的 Run 注册/租约机制，而不是让 Middleware 猜测调用方意图。

### 9.5、当前实现的异常边界

`AgentMiddlewareManager` 会隔离大多数 Middleware 回调异常，但追加消息的 `drain` 是破坏性消费：消息一旦 drain，就已经从 Store 移除。如果后续 Memory 写入或 Request 构建失败，简单的 drain 无法自动回滚。

当前实现适合“合法 `ChatMemory` 和 `ChatRequest` 构建不会失败”的场景。生产扩展时需要重点考虑：

- 事件监听器异常不能影响模型调用；
- drain 后的注入失败要有日志和补偿策略；
- 如果需要严格投递语义，应把 `drain` 升级为 claim/ack/rollback 协议。

<br/>

## 十、后续扩展实现指南

### 10.1、扩展持久化：优先复用抽象 Store

新增 Redis、MongoDB 或其他数据库时，推荐继承 `AbstractUserMessageAppendStore`，只实现存储 CRUD：

```java
public final class RedisUserMessageAppendStore
        extends AbstractUserMessageAppendStore {

    public RedisUserMessageAppendStore(int maxPending) {
        super(maxPending);
    }

    @Override
    protected AppendedUserMessage insertMessage(
            Object memoryId, AppendedUserMessage message) {
        // Redis RPUSH / Lua：写入并返回最终 ID
    }

    @Override
    protected List<AppendedUserMessage> listMessages(Object memoryId) {
        // 按 queuedAt 或序列号升序读取
    }

    @Override
    protected Optional<AppendedUserMessage> removeMessage(
            Object memoryId, Long appendId) {
        // 只允许删除 QUEUED 消息
    }

    // deleteMessages / clearMessages / countMessages
}
```

需要特别注意：父类中的 `ReentrantLock` 只对单 JVM 内的线程有效。多节点部署时，不能仅仅继承父类就声称具备分布式一致性，还需要：

- 数据库行锁或版本条件更新；
- Redis Lua 保证“检查状态 + 入队/关闭”原子执行；
- 分布式锁或 Run 租约，防止同一个 `memoryId` 被多个节点同时执行。

### 10.2、扩展消息格式：抽出 Formatter，而不是复制 Middleware

当前 Middleware 的 `mergeMessages` 是私有方法，固定了英文前缀和 Markdown bullet 格式。如果业务需要：

- 逐条注入多条 `UserMessage`；
- 使用 XML/JSON 包裹追加指令；
- 为消息增加时间、来源、优先级；
- 对不同模型使用不同提示词；

建议将格式化策略抽成独立接口：

```java
public interface AppendedMessageFormatter {
    String format(List<AppendedUserMessage> messages);
}
```

然后让 Middleware 依赖 Formatter：

```java
public UserMessageAppendMiddleware(
        IUserMessageAppendService appendService,
        UserMessageAppendEventListener eventListener,
        AppendedMessageFormatter formatter) {
    ...
}
```

这样变化只影响格式化策略，不需要复制 `open/drain/close` 的生命周期代码。

### 10.3、扩展事件：用适配器连接协议和观察能力

`UserMessageAppendEventListener` 已经是一个天然扩展点，可以分别实现：

- SSE 适配器：推送当前连接；
- WebSocket 适配器：推送多端会话；
- 审计适配器：记录谁在何时追加了什么内容；
- 指标适配器：统计排队时长、延后率和取消率；
- 测试适配器：收集事件并断言状态顺序。

如果多个适配器都需要工作，可以在 Service 层组合多个 Listener。每个 Listener 都应是旁路逻辑，不能决定是否继续模型调用。

### 10.4、扩展消息优先级和容量策略

当前 Store 是 FIFO，并且只有一个 `maxPending`。如果后续要支持优先级、租户配额或消息大小限制，可以按以下顺序扩展：

1. 先在 `IUserMessageAppendService.append` 做输入标准化和业务校验；
2. 在 `UserMessageAppendResult.Code` 增加可识别的拒绝原因；
3. 只有当规则依赖队列当前状态时，才下沉到 Store，并保证判断和写入仍在同一个原子区间；
4. 如果改变 FIFO，要同步修改队列位置的定义、测试和前端展示。

不要把“消息长度限制、权限校验、会话归属校验”塞进 Middleware；它们不依赖模型调用边界，应留在 Service 或应用层。

### 10.5、扩展可靠投递：从 drain 演进为 claim/ack

当前 `drain` 是“取出即删除”：实现简单，适合内存队列和短生命周期追加消息，但不适合强可靠场景。如果要求模型请求失败后可以重试追加消息，可以把 Store 协议演进为：

```text
claim(memoryId) -> PROCESSING
  -> ChatRequest 构建并调用模型
  -> ack(appendId) / rollback(appendId)
```

也可以使用批次 ID：

```text
claimBatch -> rebuild request -> model call
      |                 |
      +-- ack           +-- release / retry
```

这属于新的投递语义，不能只在现有 `drain` 外面包一层重试，否则无法区分“模型调用失败”和“消息已经被正常消费”。

### 10.6、扩展多 Run 关联：引入 `runId`

当前所有 Core 接口都以 `memoryId` 定位：

```java
append(Object memoryId, String content)
```

如果一个会话允许并行运行多个 Agent，需要将消息归属从会话级升级为运行级，例如：

```java
append(Object memoryId, String runId, String content)
```

同时，`AgentChatContext`、事件对象和 Store 的索引都要携带 `runId`。只在表中增加字段而不改 Core 操作键，会导致消息仍然可能进入错误的 Run。

<br/>

## 十一、测试设计与总结

### 11.1、当前 Store 测试的重点

`InMemoryUserMessageAppendStoreTest` 应至少覆盖：

1. 未 `open` 时入队返回 `RUN_NOT_OPEN`；
2. 打开后按 FIFO 入队和 `drain`；
3. 达到上限后返回 `QUEUE_FULL`；
4. `remove` 只删除仍在队列中的消息；
5. `closeAndDrain` 返回残留消息并关闭窗口；
6. 关闭后入队返回 `RUN_ALREADY_CLOSED`；
7. 重复关闭不会重复取出消息；
8. 多个 `memoryId` 互不串消息；
9. 重复 `open` 不会清掉仍处于 OPEN 状态的队列。

### 11.2、Middleware 测试的重点

建议使用假的 `IUserMessageAppendService`、`ChatMemory` 和事件监听器，断言：

1. 队列为空时返回原始 `ChatRequest`；
2. 有多条消息时按照 FIFO 合并为一条 `UserMessage`；
3. 新请求包含最新 Memory；
4. 原请求的 `parameters` 原样保留；
5. `effective` 收到正确的 `memoryId`、消息 ID 和 `currentStep`；
6. `onStop/onStopWithError` 会把残留消息通知为 `deferred`；
7. 重复停止不会重复消费残留消息；
8. 监听器失败时不会让业务层悄悄丢失错误信息，至少要有日志或隔离策略。

### 11.3、同步和流式集成测试

最有价值的集成场景是：

```text
第 1 轮模型返回工具调用
  -> 工具执行期间 append("请补充单测")
  -> 第 2 轮模型请求包含追加 UserMessage
```

还应覆盖：

- 最后一轮模型调用期间追加，最终收到 `deferred`；
- 同一个 Agent 服务多个 `memoryId` 并行运行，消息不串会话；
- `cancel` 与 `beforeModelCall` 并发时，只产生一个合法终态；
- 非流式和流式都能执行 `open -> drain -> closeAndDrain`；
- Store 替换为数据库实现后，ID、FIFO 和容量语义不变化。

### 11.4、总结

当前内置追加 Middleware 的设计可以归纳为四句话：

1. **Service 负责提供标准追加门面，Store 负责保存尚未生效的消息。**
2. **Middleware 在 `beforeModelCall` 消费消息，同时修改 Memory 和当前 Request。**
3. **`closeAndDrain` 把停止竞态收敛为“生效”或“延后”，事件监听器负责向上层传播状态。**
4. **后续扩展优先替换 Store、Formatter 和事件适配器，尽量不修改 ReAct 主循环。**

因此，运行期追加对话本质上是一条受 Agent 生命周期约束的消息通道，而不是对聊天历史的随意写入。只要保持“队列隔离、模型边界注入、停止时收口、协议层解耦”这四个原则，后续无论接入 MySQL、Redis、SSE、WebSocket，还是升级为可靠投递，都可以沿着当前 Core 的扩展面演进。

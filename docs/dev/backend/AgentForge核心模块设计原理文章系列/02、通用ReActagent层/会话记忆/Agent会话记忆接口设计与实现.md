# AgentForge ReAct 层05、标准会话记忆接口设计与实现：ChatMemory / ChatMemoryProvider / ChatMemoryStore 三层抽象

> 适用版本：release_1.x
> 适用模块：`agentforge-framework/agentforge-agent-core`
> 源码位置：`agent/memory`、`agent/extend/memory/window`
> 维护者：长路

系列定位：本文属于「AgentForge 核心模块设计原理文章系列 / 02、通用 ReAct Agent 层」，是会话记忆专题的第 01 篇。全文回答三个问题：

1. Agent 的「会话记忆」到底记的是什么，和业务聊天记录有什么区别；
2. `ChatMemory`、`ChatMemoryProvider`、`ChatMemoryStore` 三个接口为什么这样切分；
3. 这份记忆如何在 ReAct 的 `think / act` 循环里被读取、写回，以及怎样替换成 MySQL、Redis 等存储。

一句话结论：

> **`ChatMemory` 是 Agent 眼里的上下文，`ChatMemoryProvider` 是会话到记忆的寻址入口，`ChatMemoryStore` 是记忆的存储机制；三层各管一件事，ReAct 内核只依赖第一层。**

<br/>

## 文档导航

| 文档 | 说明 |
|---|---|
| [核心ReAct 框架设计，支持非流式、流式 一套调用.md](../%E6%A0%B8%E5%BF%83ReAct%20%E6%A1%86%E6%9E%B6%E8%AE%BE%E8%AE%A1%EF%BC%8C%E6%94%AF%E6%8C%81%E9%9D%9E%E6%B5%81%E5%BC%8F%E3%80%81%E6%B5%81%E5%BC%8F%20%E4%B8%80%E5%A5%97%E8%B0%83%E7%94%A8.md) | 第 01 篇：ReAct 分层、`step → think → act` 主循环、流式递归驱动 |
| [标准Agent的middleware接口扩展实现.md](../middleware/%E6%A0%87%E5%87%86Agent%E7%9A%84middleware%E6%8E%A5%E5%8F%A3%E6%89%A9%E5%B1%95%E5%AE%9E%E7%8E%B0.md) | 第 02 篇：中间件契约、分发器与流式事件接入 |
| [内置middleware扩展：日志Loggingmiddlware实现.md](../middleware/%E5%86%85%E7%BD%AEmiddleware%E6%89%A9%E5%B1%95%EF%BC%9A%E6%97%A5%E5%BF%97Loggingmiddlware%E5%AE%9E%E7%8E%B0.md) | 第 03 篇：日志中间件与会话级统计 |
| [内置middlware扩展：Agent Loop TraceMiddlware实现.md](../middleware/%E5%86%85%E7%BD%AEmiddlware%E6%89%A9%E5%B1%95%EF%BC%9AAgent%20Loop%20TraceMiddlware%E5%AE%9E%E7%8E%B0.md) | 第 04 篇：Trace 中间件与链路可视化 |
| 当前文章（会话记忆专题01） | 三层接口、默认实现、消息编解码、ReAct 读写点 |
| [会话记忆扩展-窗口会话记忆设计与实现.md](%E4%BC%9A%E8%AF%9D%E8%AE%B0%E5%BF%86%E6%89%A9%E5%B1%95-%E7%AA%97%E5%8F%A3%E4%BC%9A%E8%AF%9D%E8%AE%B0%E5%BF%86%E8%AE%BE%E8%AE%A1%E4%B8%8E%E5%AE%9E%E7%8E%B0.md) | 会话记忆专题02：窗口裁剪、系统消息常驻、工具消息成组淘汰 |

<br/>

## 目录

- [一、背景：Agent 为什么会“失忆”](#一背景agent-为什么会失忆)
- [二、概念：先把“记忆”说清楚](#二概念先把记忆说清楚)
- [三、接口设计：三层抽象](#三接口设计三层抽象)
- [四、默认实现：内存存储与消息编解码](#四默认实现内存存储与消息编解码)
- [五、接入 ReAct 主链路](#五接入-react-主链路)
- [六、一次完整对话的消息轨迹](#六一次完整对话的消息轨迹)
- [七、扩展点：换存储、换策略、加能力](#七扩展点换存储换策略加能力)
- [八、测试：接口语义如何被证明](#八测试接口语义如何被证明)
- [九、设计取舍与边界](#九设计取舍与边界)
- [十、总结](#十总结)

---

## 一、背景：Agent 为什么会“失忆”

### 1.1、一个多轮对话的真实场景

用户先问：

```text
杭州今天天气怎么样？
```

Agent 调用天气工具，回答“杭州今天多云，26℃”。用户紧接着追问：

```text
那明天呢？
```

如果第二轮只把“那明天呢”这一句话发给模型，模型并不知道“那”指的是杭州，也不知道用户问的是天气。**大模型 API 本身是无状态的**，它不会替我们保存上一轮对话。

Agent 要表现得“记得住”，唯一可靠的办法是：**每次调用模型前，把历史消息重新组装进 `messages` 数组。**

这段被反复组装、裁剪、再发送给模型的消息列表，就是本文要讲的 **会话记忆（ChatMemory）**。

### 1.2、没有记忆抽象会怎样

在早期实现里，记忆很容易退化成一堆散落在 `think()` 方法里的 `List<ChatMessage>`：

```java
// 反面示例：状态、裁剪、存储全部耦合在 Agent 里
List<ChatMessage> history = new ArrayList<ChatMessage>();

public String think(String question) {
    history.add(UserMessage.from(question));
    ChatResponse response = model.chat(history);
    history.add(response.aiMessage());
    if (response.aiMessage().hasToolExecutionRequests()) {
        // 执行工具后还要记得把结果也塞回去
        history.addAll(executeTools(response));
    }
    return response.aiMessage().text();
}
```

这段代码能跑，但会带来四个问题：

1. **状态无法持久化**：`history` 活在 JVM 堆里，服务重启即清零；
2. **多实例互不可见**：同一个会话落到另一台机器，上下文直接断档；
3. **存储与策略耦合**：想换成 MySQL，必须改 Agent 主循环；
4. **裁剪逻辑重复**：一旦引入窗口限制，每个 Agent 实现都要自己写一遍淘汰规则。

AgentForge 的解法不是“加一个工具类”，而是**把记忆提升为一等抽象**，并把它拆成三层职责明确的接口。

### 1.3、本文要解决的问题

> 为 ReAct Agent 定义一套稳定的会话记忆契约：上层只关心“读写上下文”，下层可以自由替换存储介质，中间不丢消息语义。

这里要特别划清边界：本文的“记忆”只指**喂给模型的消息列表**，不负责业务侧的会话列表、消息分页、历史回放。两者的生命周期和查询方式完全不同，混在一起设计，接口一定会失控。

<br/>

## 二、概念：先把“记忆”说清楚

### 2.1、模型上下文与业务聊天记录不是一回事

| 维度 | 模型上下文（ChatMemory） | 业务聊天记录 |
|---|---|---|
| 内容 | `SystemMessage` / `UserMessage` / `AiMessage` / `ToolExecutionResultMessage` | 用户可见的问答气泡 |
| 数量 | 受窗口约束，通常几十到几百条 | 全量历史，可能上万条 |
| 用途 | 拼成模型请求的 `messages` 数组 | 展示、检索、导出、审计 |
| 结构 | 强结构化，包含 tool_calls、thinking、工具结果 | 弱结构化，以文本为主 |
| 写入方式 | 每轮可能整段覆盖（窗口裁剪后写回） | 基本只追加 |

**重点**：模型上下文会随着窗口裁剪**变短**，它是一份“会被覆盖的工作集”；业务聊天记录则只增不减。把两者塞进同一张表、同一套接口，后面一定会互相拖累。

### 2.2、ChatMessage：provider 中立的消息模型

会话记忆里流动的不是字符串，而是结构化的 `ChatMessage`：

```java
public interface ChatMessage {
    ChatMessageType type();
    default String text() { ... }
}
```

`ChatMessageType` 定义了五种消息：

| 类型 | 对应实现 | 作用 |
|---|---|---|
| `SYSTEM` | `SystemMessage` | 系统提示词，约束 Agent 的行为 |
| `USER` | `UserMessage` | 用户输入，可带 `name` 与多段 `Content` |
| `AI` | `AiMessage` | 模型回复，可能同时包含文本、思考、工具调用 |
| `TOOL_EXECUTION_RESULT` | `ToolExecutionResultMessage` | 工具执行结果，通过 `id` 与工具调用配对 |
| `CUSTOM` | `CustomMessage` | 厂商私有消息，只携带 `attributes` |

其中 `AiMessage` 是记忆里最复杂、也最容易在序列化时出问题的一类：

```java
AiMessage.builder()
        .text("我来查询杭州天气")
        .thinking("用户问的是天气，需要调用 getWeather")
        .toolExecutionRequests(
                Arrays.asList(
                        ToolExecutionRequest.from(
                                "call-1", "getWeather", "{\"city\":\"杭州\"}")))
        .build();
```

**注意**：`toolExecutionRequests` 和 `ToolExecutionResultMessage.id` 是一对必须同时存活的信息。如果持久化时只存了文本、丢了工具调用，下一轮模型会看到一条“没有来源的工具结果”，轻则答非所问，重则直接报协议错误。

### 2.3、ReAct 循环里，记忆的读写点在哪里

无论非流式还是流式，记忆的读写位置都是同一组：

```text
Agent.init(request)
  ├─ provider.get(request.memoryId)          拿到本会话记忆
  └─ chatMemory.add(UserMessage)             写入本轮用户问题

step -> think
  ├─ chatMemory.messages()                   读出全部上下文
  ├─ model.chat(messages)                    发起模型调用
  └─ chatMemory.add(AiMessage)               写回模型回复（含工具调用）

step -> act
  ├─ toolExecutor.execute(...)               执行工具
  └─ chatMemory.add(ToolExecutionResultMessage)  写回每条工具结果

下一轮 step -> think
  └─ chatMemory.messages()                   再次读出“问题 + 回复 + 工具结果”
```

对应到 AgentForge 的代码：

- `BaseAgent.init()` 负责绑定记忆并写入用户消息（`BaseAgent.java:33`）；
- `ReActAgent.think()` 从 `chatMemory.messages()` 读取上下文（`ReActAgent.java:151`）；
- 模型回复写回发生在 `ReActAgent.java:220`；
- 工具结果写回发生在 `AgentToolExecutor.java:148`（串行）与 `AgentToolExecutor.java:304`（并发）；
- 流式路径同样从记忆取消息（`ReActTokenStream.java:178`）。

**一句话**：ReAct 内核只做两件事——**从记忆读、往记忆写**。它不应该知道消息存在内存里还是 MySQL 里。

### 2.4、接口必须回答的三个问题

把需求压缩后，其实只剩三个问题：

| 问题 | 对应抽象 |
|---|---|
| 这是谁的记忆？ | `ChatMemory.id()` |
| Agent 怎么读写这份记忆？ | `ChatMemory.add / messages / clear` |
| 消息最终存在哪里？ | `ChatMemoryStore` |

这就是三层接口的由来。

<br/>

## 三、接口设计：三层抽象

### 3.1、ChatMemory：Agent 眼里的上下文

`ChatMemory` 是整个记忆体系里**唯一会被 ReAct 内核直接依赖**的接口（`ChatMemory.java:12`）：

```java
public interface ChatMemory {

    /** 会话ID */
    Object id();

    /** 追加一条消息 */
    void add(ChatMessage message);

    /** 当前记忆中的全部消息，用于发起下一次模型调用 */
    List<ChatMessage> messages();

    /** 清空会话上下文 */
    void clear();
}
```

四个方法，对应 Agent 生命周期里的四个动作：

| 方法 | 调用方 | 语义 |
|---|---|---|
| `id()` | `ChatMemoryStore` 实现、调试 | 当前记忆的寻址标识 |
| `add(message)` | `BaseAgent`、`ReActAgent`、`AgentToolExecutor` | 追加一条消息 |
| `messages()` | `think()`、流式 `chat()` | 取当前完整上下文 |
| `clear()` | `clearContext()`、系统提示词重排 | 清空上下文 |

**为什么接口这么小？**

因为 `ChatMemory` 是“给 Agent 用的”，不是“给管理后台用的”。分页查询、按消息 ID 删除、全文检索这些能力，属于业务聊天记录，不属于模型上下文。接口越小，实现者越容易保证语义一致，内核也越不容易被存储细节污染。

**为什么 `id()` 返回 `Object` 而不是 `String`？**

AgentForge 不假设上层用什么做主键。业务层可能用 `Long` 的会话 ID，也可能用 `agentId:sessionId` 这样的复合字符串。把类型收窄成 `String` 会逼着所有调用方提前转换，而真正需要字符串的地方只有持久化实现，它可以在自己的边界内做 `String.valueOf`。

### 3.2、ChatMemoryProvider：会话到记忆的寻址入口

Agent 每一轮运行只拿到一个 `memoryId`，需要据此找到对应的记忆实例：

```java
public interface ChatMemoryProvider {

    /** 获取（不存在则创建）某个会话的记忆 */
    ChatMemory get(Object memoryId);
}
```

这个接口只有一行，但它承担了两个关键语义：

1. **按会话隔离**：不同 `memoryId` 必须拿到互不影响的记忆；
2. **同一会话复用**：同一个 `memoryId` 反复 `get`，应返回同一个逻辑上下文。

**注意**：`get` 的“不存在则创建”是逻辑上的创建，不代表立刻分配存储。默认实现会创建一个轻量的记忆对象，真正的消息仍然由 `ChatMemoryStore` 按需读取。

为什么不让 `ChatMemory` 自己按 ID 查找？因为“创建哪种记忆”是装配策略：有人要窗口记忆，有人要摘要记忆，有人要带向量检索的记忆。把这层选择放在 Provider 里，Agent 只需要声明“我要一个 Provider”，不必关心具体实现。

### 3.3、ChatMemoryStore：只做机制，不做策略

最底层的存储契约只有三个方法（`ChatMemoryStore.java:13`）：

```java
public interface ChatMemoryStore {

    /** 读取该会话存储的全部消息；不存在时返回空列表 */
    List<ChatMessage> getMessages(Object memoryId);

    /** 整段覆盖写入该会话的消息列表 */
    void updateMessages(Object memoryId, List<ChatMessage> messages);

    /** 删除该会话的全部消息 */
    void deleteMessages(Object memoryId);
}
```

它的语义非常克制：

- **整段读**：没有“按条分页”，因为模型上下文每次都需要完整的一段；
- **整段写**：没有“追加一条”，因为窗口裁剪后，写回的列表可能比原来更短；
- **整段删**：对应 `clear()`。

**重点**：`updateMessages` 是“覆盖”而不是“追加”。这不是实现偷懒，而是为了匹配窗口记忆的行为——裁剪发生在写入之前，存储层拿到的已经是最终结果。

把存储层设计成“纯读写”带来两个直接收益：

1. **存储实现可以自由替换**：内存、MySQL、Redis、对象存储，只要实现三个方法；
2. **裁剪策略只有一份**：窗口怎么淘汰、工具消息怎么配对，永远留在记忆实现里，不会出现“MySQL 版和 Redis 版淘汰规则不一致”。

### 3.4、策略与机制分离

| 关注点 | 归属 | 具体内容 |
|---|---|---|
| 策略 | `ChatMemory` 实现 | 窗口大小、系统消息是否常驻、工具消息如何成组淘汰、是否摘要压缩 |
| 寻址 | `ChatMemoryProvider` | 哪个 `memoryId` 对应哪个记忆实例 |
| 机制 | `ChatMemoryStore` | 消息列表怎么读、怎么写、怎么删 |

依赖方向是单向的：

```text
ReActAgent
   │ 只依赖
   ▼
ChatMemory ◄──── ChatMemoryProvider 负责创建/复用
   │ 委托读写
   ▼
ChatMemoryStore ◄──── InMemory / MySQL / Redis 实现
```

**一句话**：上层看到的是“上下文”，下层看到的是“消息列表”，中间那层负责把两者接起来。

<br/>

## 四、默认实现：内存存储与消息编解码

### 4.1、InMemoryChatMemoryStore：单机与测试的默认选择

默认实现非常短（`InMemoryChatMemoryStore.java:17`）：

```java
public final class InMemoryChatMemoryStore implements ChatMemoryStore {

    private final Map<Object, List<ChatMessage>> messagesByMemoryId =
            new ConcurrentHashMap<Object, List<ChatMessage>>();

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        List<ChatMessage> messages = messagesByMemoryId.get(memoryId);
        if (messages == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<ChatMessage>(messages));
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            messagesByMemoryId.remove(memoryId);
            return;
        }
        messagesByMemoryId.put(memoryId, new ArrayList<ChatMessage>(messages));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        messagesByMemoryId.remove(memoryId);
    }
}
```

几个容易被忽略的细节：

1. **写入做防御性拷贝**：`new ArrayList<>(messages)`，调用方之后修改原列表不会影响存储；
2. **读取返回不可变视图**：调用方拿到的是快照，不能反向修改内部集合；
3. **空列表等价于删除**：避免内存里残留大量空 key；
4. **`ConcurrentHashMap` 只保证 Map 操作安全**：它不保证“读-改-写”整体原子。窗口记忆通过 `synchronized` 在记忆层解决这个问题。

**边界**：这个实现只适合单机开发、单元测试和 CLI 场景。进程重启数据即丢失，多实例之间也互不可见。

### 4.2、ChatMessageCodec：把消息变成可持久化的 JSON

一旦使用 MySQL、Redis 等外部存储，就必须回答“消息怎么序列化”。AgentForge 没有直接把厂商 SDK 的对象交给 Jackson，而是在核心模块里提供了一个 provider 中立的编解码器（`ChatMessageCodec.java:28`）：

```java
public final class ChatMessageCodec {

    public String encodeAll(List<? extends ChatMessage> messages) { ... }

    public List<ChatMessage> decodeAll(String json) { ... }

    public Map<String, Object> toMap(ChatMessage message) { ... }
}
```

编码后的结构大致如下：

```json
[
  {
    "type": "SYSTEM",
    "text": "你是一个严谨的天气助手"
  },
  {
    "type": "USER",
    "text": "杭州今天天气怎么样？"
  },
  {
    "type": "AI",
    "text": "我来查询一下",
    "thinking": "需要调用天气工具",
    "toolRequests": [
      {
        "id": "call-1",
        "name": "getWeather",
        "arguments": "{\"city\":\"杭州\"}"
      }
    ]
  },
  {
    "type": "TOOL_EXECUTION_RESULT",
    "toolResult": {
      "id": "call-1",
      "toolName": "getWeather",
      "text": "多云，26℃",
      "isError": false
    }
  }
]
```

字段设计对应关系：

| 消息类型 | 编码字段 |
|---|---|
| `SYSTEM` | `type` + `text` |
| `USER` | `type` + `name`（可选）+ `text` 或 `contents` |
| `AI` | `type` + `text` + `thinking` + `toolRequests` + `attributes` |
| `TOOL_EXECUTION_RESULT` | `type` + `toolResult{ id, toolName, text, isError }` + `attributes` |
| `CUSTOM` | `type` + `attributes` |

**为什么不用通用 JSON 序列化框架直接序列化对象？**

因为 `ChatMessage` 是接口，反序列化时需要根据 `type` 还原成具体子类。通用框架通常依赖类型字段或白名单配置，一旦字段改名、增加子类型，旧数据就可能反序列化失败，甚至触发安全问题。手写编解码器把“类型 → 实现”的映射显式写在代码里，代价可控，行为也更容易测试。

解码端的两个关键约定：

```java
private ChatMessageType parseType(String type) {
    if (type == null) {
        throw new IllegalStateException("Chat message JSON has no type");
    }
    try {
        return ChatMessageType.valueOf(type);
    } catch (IllegalArgumentException e) {
        throw new IllegalStateException("Unknown chat message type: " + type, e);
    }
}
```

- **空内容返回空列表**：首次会话没有历史，是正常情况；
- **未知类型显式抛异常**：绝不静默降级成文本消息。静默降级会把工具调用悄悄丢掉，问题会延迟到模型调用时才暴露，排查成本更高。

**注意**：`attributes` 是厂商私有字段的逃生通道，编解码器原样透传。它不参与业务语义判断，但必须保证 round-trip 不丢失，否则某些依赖 `attributes` 的模型适配器会行为异常。

### 4.3、MessageWindowChatMemory：策略层的一个具体实现

标准接口之上，AgentForge 提供了一个[窗口记忆实现](%E4%BC%9A%E8%AF%9D%E8%AE%B0%E5%BF%86%E6%89%A9%E5%B1%95-%E7%AA%97%E5%8F%A3%E4%BC%9A%E8%AF%9D%E8%AE%B0%E5%BF%86%E8%AE%BE%E8%AE%A1%E4%B8%8E%E5%AE%9E%E7%8E%B0.md)，位于扩展包 `agent/extend/memory/window`。它不是本文重点，但能说明三层抽象如何落地。

它自身**不持有任何消息状态**，所有读写都委托给 `ChatMemoryStore`：

```java
public final class MessageWindowChatMemory implements ChatMemory {

    private final Object id;
    private final int maxMessages;
    private final ChatMemoryStore store;

    @Override
    public synchronized void add(ChatMessage... newMessages) {
        List<ChatMessage> currentMessages = store.getMessages(id);
        List<ChatMessage> allMessages = new LinkedList<ChatMessage>(currentMessages);
        // 合并新消息、处理系统消息、裁剪窗口
        ensureCapacity(allMessages);
        store.updateMessages(id, allMessages);
    }

    @Override
    public synchronized List<ChatMessage> messages() {
        List<ChatMessage> messages = new LinkedList<ChatMessage>(store.getMessages(id));
        ensureCapacity(messages);
        return Collections.unmodifiableList(new ArrayList<ChatMessage>(messages));
    }
}
```

它承担两类裁剪语义：

1. **系统消息常驻**：淘汰从索引 1 开始，永远不动第 0 条系统消息；重复的系统消息会被替换，而不是追加；
2. **工具消息成组淘汰**：淘汰带工具调用的 `AiMessage` 时，紧跟其后的 `ToolExecutionResultMessage` 必须一起删除，避免出现孤儿结果。

```java
private void ensureCapacity(List<ChatMessage> messages) {
    while (messages.size() > maxMessages) {
        int messageToEvictIndex =
                !messages.isEmpty() && messages.get(0).type() == ChatMessageType.SYSTEM ? 1 : 0;
        if (messageToEvictIndex >= messages.size()) {
            return;
        }
        ChatMessage evicted = messages.remove(messageToEvictIndex);
        if (evicted instanceof AiMessage && ((AiMessage) evicted).hasToolExecutionRequests()) {
            while (messages.size() > messageToEvictIndex
                    && messages.get(messageToEvictIndex).type()
                            == ChatMessageType.TOOL_EXECUTION_RESULT) {
                messages.remove(messageToEvictIndex);
            }
        }
    }
}
```

**重点**：`add(ChatMessage...)` 的批量重载是一次优化。一次 `add` 只做一次 store 读取、一次裁剪、一次写回；如果逐条调用 `add`，每条消息都会触发一轮完整的读写，接 MySQL 后就是 N 次查询加 N 次更新。

对应的 Provider 会按 `memoryId` 复用实例，并让所有实例共享同一个 Store：

```java
@Override
public ChatMemory get(Object memoryId) {
    ChatMemory memory = memories.get(memoryId);
    if (memory == null) {
        memory = MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(maxMessages)
                .chatMemoryStore(store)
                .build();
        ChatMemory previous = memories.putIfAbsent(memoryId, memory);
        if (previous != null) {
            memory = previous;
        }
    }
    return memory;
}
```

**注意**：`memories` 这个 Map 本身只缓存“记忆对象”，不缓存“消息内容”。消息内容始终在 Store 里，所以换掉 Store 就能改变持久化行为，不需要修改 Provider。

窗口裁剪的完整规则、并发边界和测试，会在[会话记忆专题 02](%E4%BC%9A%E8%AF%9D%E8%AE%B0%E5%BF%86%E6%89%A9%E5%B1%95-%E7%AA%97%E5%8F%A3%E4%BC%9A%E8%AF%9D%E8%AE%B0%E5%BF%86%E8%AE%BE%E8%AE%A1%E4%B8%8E%E5%AE%9E%E7%8E%B0.md) 中展开。

<br/>

## 五、接入 ReAct 主链路

### 5.1、BaseAgent：初始化时绑定记忆并写入用户消息

`BaseAgent.init()` 是所有 Agent 共享的入口（`BaseAgent.java:33`）：

```java
protected AgentChatContext init(AgentRequest request) {
    AgentChatContext chatContext = initParams(request);
    initMessages(chatContext);
    return chatContext;
}

private AgentChatContext initParams(AgentRequest request) {
    ChatMemory chatMemory = getChatMemoryProvider().get(request.getMemoryId());

    return AgentChatContext.builder()
            .request(request)
            .chatModel(getChatModel())
            .chatMemory(chatMemory)
            .extensions(...)
            .build();
}
```

随后 `initMessages` 把本轮问题写进记忆：

```java
protected void initMessages(AgentChatContext chatContext) {
    addMessage(chatContext, buildUserMessage(chatContext));
}

protected UserMessage buildUserMessage(AgentChatContext chatContext) {
    String question = chatContext.getQuestion();
    return UserMessage.from(
            question == null || question.trim().isEmpty() ? "请结合上下文回答。" : question.trim());
}
```

**为什么用户消息要在进入循环前写入？**

因为 `think()` 的第一件事就是读 `messages()`。如果等到模型调用前才写，非流式和流式两条路径都要各自处理一次，容易漏。放在 `init()` 里，两条驱动器天然共享同一份上下文。

### 5.2、ReActAgent：每轮运行重建系统提示词

`ReActAgent` 重写了 `initMessages`（`ReActAgent.java:120`），先把系统消息摘出来，再重新拼装：

```java
@Override
protected void initMessages(AgentChatContext chatContext) {
    ChatMemory chatMemory = chatContext.getChatMemory();
    List<ChatMessage> messages = chatMemory.messages();

    // 过滤掉所有系统消息，保留其他类型的消息
    List<ChatMessage> nonSystemMessages = new ArrayList<ChatMessage>();
    for (ChatMessage message : messages) {
        if (message.type() != ChatMessageType.SYSTEM) {
            nonSystemMessages.add(message);
        }
    }

    // 重新构建：新系统消息 + 其他非系统消息
    List<ChatMessage> newMessages = new ArrayList<ChatMessage>();
    if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
        newMessages.add(SystemMessage.from(systemPrompt));
    }
    newMessages.addAll(nonSystemMessages);

    chatMemory.clear();
    for (ChatMessage message : newMessages) {
        chatMemory.add(message);
    }

    super.initMessages(chatContext);
}
```

这段代码解决的是**系统提示词版本漂移**问题：Agent 的 `systemPrompt` 可能在配置中心被修改，如果记忆里一直保留旧系统消息，新配置永远不生效。因此每次运行都按“当前配置”重建。

**代价**：这是一次 `clear + 逐条 add`，在窗口记忆下会触发多次 Store 读写。当前实现优先保证语义正确，后续可以增加批量写接口来优化。

### 5.3、think：整段读取上下文

`think()` 不关心里程碑式的消息管理，它只做一次整段读取（`ReActAgent.java:151`）：

```java
ChatMemory chatMemory = chatContext.getChatMemory();
List<ChatMessage> curCallMessages = chatMemory.messages();

ChatRequest chatRequest =
        ChatRequest.builder()
                .messages(curCallMessages)
                .parameters(...)
                .build();
```

**注意**：这里没有“只取最近 N 条”的逻辑。窗口限制属于记忆实现，不应该泄漏到 `think()`。如果换成摘要记忆，`messages()` 返回的可能是“摘要 + 最近若干条”，`think()` 依然不需要改。

### 5.4、写回：模型回复与工具结果

模型调用成功后，回复立即写回记忆（`ReActAgent.java:220`）：

```java
AiMessage aiMessage = chatResponse.aiMessage();
addMessage(chatContext, aiMessage);
```

工具执行结果由 `AgentToolExecutor` 写回，串行与并发两条路径都做了同样的事：

```java
// 串行：AgentToolExecutor.java:148
chatContext.getChatMemory().add(toolMessage);

// 并发：AgentToolExecutor.java:304
chatContext.getChatMemory().add(toolMessage);
```

**重点**：工具结果必须逐条写回，并且保留 `id`。下一轮 `think()` 读到的上下文里，`AiMessage` 的 `toolExecutionRequests` 与 `ToolExecutionResultMessage` 才能一一配对。

### 5.5、流式路径：读同一个记忆，写同一组位置

流式路径没有另起一套记忆。`ReActTokenStream` 在发起模型调用前，同样从 `chatContext.getChatMemory().messages()` 取上下文（`ReActTokenStream.java:178`）；模型回复与工具结果的写回点也与非流式一致。

这正是记忆抽象的价值：**非流式与流式共享同一个 `ChatMemory`，差异只在“怎么发起模型调用”，不在“上下文从哪里来”。**

### 5.6、clearContext：把“清空上下文”做成对外能力

`BaseAgent.clearContext()`（`BaseAgent.java:123`）直接委托给记忆：

```java
@Override
public boolean clearContext(Object memoryId) {
    ChatMemoryProvider chatMemoryProvider = getChatMemoryProvider();
    if (chatMemoryProvider == null) {
        throw new AgentException("ChatMemoryProvider is null, need set");
    }
    ChatMemory chatMemory = chatMemoryProvider.get(memoryId);
    chatMemory.clear();
    clearCancelFlag(memoryId);
    return true;
}
```

**注意**：这里同时清理了取消标志。清空上下文和重置会话状态是两个动作，但对调用方来说应该是一次原子操作，否则用户点“清空对话”后，下一轮可能仍带着上一轮的取消标记。

<br/>

## 六、一次完整对话的消息轨迹

假设 `maxMessages = 10`，用户连续问两轮。记忆里的内容变化如下：

| 阶段 | 记忆中的消息 |
|---|---|
| 第 1 轮 init | `System(天气助手)`、`User(杭州今天天气怎么样？)` |
| 第 1 轮 think 返回 | 追加 `Ai(我来查询一下 + toolRequests[call-1])` |
| 第 1 轮 act 完成 | 追加 `ToolResult(call-1, getWeather, 多云 26℃)` |
| 第 1 轮第二次 think | 读取以上 4 条，模型生成最终回答 |
| 第 1 轮结束 | 追加 `Ai(杭州今天多云，26℃)` |
| 第 2 轮 init | 追加 `User(那明天呢？)` |
| 第 2 轮 think | 读取全部历史，模型能从“杭州”“天气”推断出追问含义 |

关键点在第 2 轮：用户只说了“那明天呢”，但模型看到的是完整上下文，因此能正确理解为“杭州明天的天气”。

如果中途发生窗口淘汰，规则也必须保证成组性：

```text
淘汰前：System, User(q1), Ai(tool call), ToolResult, Ai(answer), User(q2)
淘汰 Ai(tool call) 时：
        System, User(q1), ToolResult, Ai(answer), User(q2)   ← 错误：孤儿工具结果
正确结果：
        System, User(q1), Ai(answer), User(q2)                ← 工具调用与结果一起淘汰
```

这也是为什么裁剪必须留在记忆层：只有它同时看得到 `AiMessage` 和紧随其后的 `ToolExecutionResultMessage`。

<br/>

## 七、扩展点：换存储、换策略、加能力

### 7.1、替换存储：只实现三个方法

把 `InMemoryChatMemoryStore` 换成 MySQL，只需要实现 `ChatMemoryStore`，并复用 `ChatMessageCodec`：

```java
public class MysqlChatMemoryStore implements ChatMemoryStore {

    private final AgentMemoryMapper agentMemoryMapper;
    private final ChatMessageCodec chatMessageCodec = new ChatMessageCodec();

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        AgentMemoryPojo pojo = agentMemoryMapper.selectByMemoryId(toMemoryId(memoryId));
        if (pojo == null || pojo.getContent() == null) {
            return Collections.emptyList();
        }
        return chatMessageCodec.decodeAll(pojo.getContent());
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        // 先更新，影响 0 行再插入
    }

    @Override
    public void deleteMessages(Object memoryId) {
        agentMemoryMapper.deleteByMemoryId(toMemoryId(memoryId));
    }
}
```

装配时只需要换一个 Store：

```java
ChatMemoryStore store = new MysqlChatMemoryStore(agentMemoryMapper);
ChatMemoryProvider provider = new MessageWindowChatMemoryProvider(100, store);

ReActAgent agent = ReActAgent.builder()
        .chatModel(chatModel)
        .chatMemoryProvider(provider)
        .toolService(toolService)
        .build();
```

**重点**：Agent、窗口裁剪、消息写回逻辑一行都不用改。这就是“机制可替换”的实际收益。

### 7.2、替换策略：自定义 ChatMemory 实现

如果窗口不够用，可以基于同一套接口实现摘要记忆：

```java
public final class SummaryChatMemory implements ChatMemory {

    @Override
    public List<ChatMessage> messages() {
        // 返回“历史摘要 + 最近 N 条原文”
    }

    @Override
    public void add(ChatMessage message) {
        // 追加消息，超过阈值时触发摘要并压缩
    }
}
```

对 `think()` 来说，它仍然只是调用 `messages()`。**策略可以变，内核契约不变。**

### 7.3、多租户与 memoryId 设计

`memoryId` 是记忆隔离的唯一依据，推荐由业务层显式构造：

```text
agentId:sessionId
tenantId:userId:sessionId
```

`AgentRequest` 的 Builder 已经强制 `memoryId` 非空（`AgentRequest.java:74`），避免出现“所有会话共用一个默认记忆”的串话事故。

**注意**：不要把用户可控的原始字符串直接拼进 key 而不做规范化。不同租户使用相同 `sessionId` 时，必须靠前缀隔离。

### 7.4、并发与失败边界

- **同一会话并发**：窗口记忆用 `synchronized` 保证单实例内的读-改-写原子性，但跨实例仍然需要业务层保证同一 `memoryId` 不并发运行；
- **存储失败**：MySQL 实现选择“记录日志、不向上抛”，因为记忆属于尽力而为的数据，一次数据库抖动不应该让整轮对话失败；
- **实例缓存增长**：Provider 的 `memories` Map 会随会话数增长，长生命周期服务需要配合会话过期策略清理。

<br/>

## 八、测试：接口语义如何被证明

### 8.1、编解码 round-trip

`ChatMessageCodecTest` 覆盖了最容易出问题的三类场景：

```java
@Test
public void shouldRoundTripAiMessageWithThinkingAndToolCalls() {
    AiMessage original = AiMessage.builder()
            .text("查询天气")
            .thinking("需要调用工具")
            .toolExecutionRequests(Arrays.asList(
                    ToolExecutionRequest.from("call-1", "getWeather", "{\"city\":\"杭州\"}")))
            .build();

    List<ChatMessage> decoded = codec.decodeAll(codec.encodeAll(Arrays.asList(original)));

    AiMessage restored = (AiMessage) decoded.get(0);
    assertEquals("查询天气", restored.text());
    assertEquals("需要调用工具", restored.thinking());
    assertEquals("getWeather", restored.toolExecutionRequests().get(0).name());
}
```

另外三个断言同样重要：

| 测试 | 证明的语义 |
|---|---|
| `shouldRoundTripAllTextMessageTypes` | 四种文本消息都能无损往返 |
| `shouldRoundTripAttributes` | 厂商私有 `attributes` 不丢 |
| `shouldRejectUnknownMessageType` | 未知类型显式失败，不静默降级 |

### 8.2、窗口记忆的批处理与成组淘汰

`MessageWindowChatMemoryTest` 用三个用例锁住关键行为：

```java
@Test
public void shouldEvictToolResultsTogetherWithAiToolCall() {
    MessageWindowChatMemory memory = MessageWindowChatMemory.withMaxMessages(3);
    ToolExecutionRequest request = ToolExecutionRequest.from("call-1", "echo", "{}");

    memory.add(
            SystemMessage.from("system"),
            AiMessage.from(request),
            new ToolExecutionResultMessage("call-1", "echo", "ok"),
            UserMessage.from("next"));

    List<ChatMessage> messages = memory.messages();
    assertEquals(2, messages.size());
    assertEquals("system", messages.get(0).text());
    assertEquals("next", messages.get(1).text());
}
```

这个用例同时证明了：

1. 系统消息在淘汰时被保留；
2. 工具调用与工具结果被一起删除；
3. 批量 `add` 只产生一条连续的最终结果。

### 8.3、Provider 的复用语义

```java
@Test
public void shouldReuseMessageWindowMemoryPerSession() {
    ChatMemoryProvider provider = new MessageWindowChatMemoryProvider(10);
    ChatMemory first = provider.get("s1");

    assertSame(first, provider.get("s1"));
    assertTrue(provider.get("s2") != first);
}
```

同一个 `memoryId` 返回同一实例，不同 `memoryId` 互不复用——这是会话隔离的底线。

<br/>

## 九、设计取舍与边界

| 取舍 | 选择 | 原因 |
|---|---|---|
| 接口粒度 | `ChatMemory` 只保留 4 个方法 | 只暴露 Agent 循环真正需要的能力，避免存储细节泄漏 |
| ID 类型 | `Object memoryId` | 不绑定业务主键类型，持久化实现自行转字符串 |
| 序列化方式 | 手写 `ChatMessageCodec` | 类型映射显式可控，避免通用框架的类型白名单与兼容性问题 |
| 存储语义 | 整段读、整段覆盖写 | 与窗口裁剪天然对齐，存储实现只需关心“取”和“放” |
| 裁剪位置 | 放在 `ChatMemory` 实现 | 工具消息成组淘汰需要同时看到请求与结果，不能下放到 Store |
| 系统提示词 | 每次运行重建 | 保证配置变更立即生效，代价是一次全量重写 |
| 未知消息类型 | 抛异常 | 静默降级会丢工具调用，问题延迟暴露更难排查 |
| 默认存储 | 进程内 Map | 零依赖、易测试；集群场景显式替换为 MySQL/Redis |

已知边界：

1. **`InMemoryChatMemoryStore` 不跨进程**：只适合单机与测试；
2. **窗口重写有放大**：每次 `add` 都可能整段覆盖，消息很多时写入成本上升，需要靠窗口上限控制；
3. **Provider 实例缓存无自动过期**：长期运行的服务需要配合会话清理；
4. **记忆不负责审计**：如果需要完整对话留痕，应写入业务聊天记录表，而不是依赖会被裁剪的 `ChatMemory`。

<br/>

## 十、总结

回到最初的问题：Agent 为什么会“失忆”？

因为模型 API 本身无状态，Agent 必须在每次调用前重新组装上下文。AgentForge 把这件反复发生的事收敛成三层抽象：

- **`ChatMemory`**：Agent 眼里的上下文，只提供 `id / add / messages / clear`；
- **`ChatMemoryProvider`**：按 `memoryId` 寻址，保证同会话复用、不同会话隔离；
- **`ChatMemoryStore`**：只负责整段读写，内存、MySQL、Redis 可以自由替换。

三条可以带走的结论：

1. **记忆是策略，存储是机制。** 裁剪规则留在记忆实现里，存储只做纯读写，换介质不影响语义。
2. **接口只服务调用方。** `ChatMemory` 不需要分页、检索、按条删除，那些是业务聊天记录的能力。
3. **上下文必须结构化。** `thinking`、`toolRequests`、`toolResult.id` 这些字段一旦在持久化时丢失，下一轮模型调用就会出问题；编解码器必须保证无损往返，并对未知类型显式失败。

下一篇会进入[窗口记忆的具体实现](%E4%BC%9A%E8%AF%9D%E8%AE%B0%E5%BF%86%E6%89%A9%E5%B1%95-%E7%AA%97%E5%8F%A3%E4%BC%9A%E8%AF%9D%E8%AE%B0%E5%BF%86%E8%AE%BE%E8%AE%A1%E4%B8%8E%E5%AE%9E%E7%8E%B0.md)：系统消息为什么必须常驻、工具消息为什么要成组淘汰，以及批量 `add` 如何把多次 Store 读写压缩成一次。

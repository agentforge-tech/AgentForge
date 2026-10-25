# 基于 MySQL 实现 Agent 的 ChatMemory 上下文持久化

在开发 AgentForge 的多实例部署方案时，我们就遇到了这样一个具体问题：本地单机跑得好好的对话，一旦把服务部署两台机器做负载均衡，同一个 `sessionId` 的第二次提问落到另一台机器上，模型就像失忆了一样，完全不记得上一轮说了什么。更麻烦的是服务一重启，所有正在进行的对话上下文全部清零。

这篇文章就从这个真实问题出发，完整讲一遍怎么把 Agent 的模型上下文从内存搬到 MySQL 里。

---

# 一、背景与问题引入

## 1.1 问题是怎么暴露出来的

AgentForge 的对话主链路是这样的：

```
BaseAgent.init(request)
  └─ getChatMemoryProvider().get(memoryId)      // 拿到会话记忆
  └─ chatMemory.add(userMessage)                // 写入本轮用户消息
  └─ 模型调用 / 工具执行 / 结果回写 ...
```

其中 `ChatMemory` 就是**模型上下文**的载体——每一轮 think 的输入消息都从这里读，模型回复与工具结果都写回这里。

改造**之前**，`MessageWindowChatMemory` 自己持有一个 `ArrayList` 存消息：

```java
public final class MessageWindowChatMemory implements ChatMemory {

    private final Object id;
    private final int maxMessages;
    private final List<ChatMessage> messages = new ArrayList<ChatMessage>();   // 状态在对象里
}

public final class MessageWindowChatMemoryProvider implements ChatMemoryProvider {

    private final Map<Object, ChatMemory> memories = new ConcurrentHashMap<Object, ChatMemory>();

    @Override
    public ChatMemory get(Object memoryId) {
        // 只查本进程的 Map
    }
}
```

**重点**：消息状态完全活在 JVM 堆里。它带来两个致命问题：

1. **进程重启即丢失**：上下文只活在堆内存里，重启清零。
2. **多实例互不可见**：A 机器写的上下文，B 机器读不到。

在单机 Demo 阶段这些问题根本不会显现，但只要上集群，立刻就是线上事故。

## 1.2 现有抽象长什么样

先看清楚改造后的契约，这是整套方案的地基：

| 类型 | 职责 |
| --- | --- |
| `ChatMemory` | `id()` / `add(ChatMessage)` / `messages()` / `clear()` |
| `ChatMemoryProvider` | `ChatMemory get(Object memoryId)`，不存在则创建 |
| `ChatMemoryStore` | `getMessages` / `updateMessages` / `deleteMessages`，**纯存储，不做裁剪** |
| `MessageWindowChatMemory` | 只做窗口裁剪，**自身不持有任何消息状态** |
| `MessageWindowChatMemoryProvider` | 按 memoryId 创建窗口记忆，所有实例共享同一个 Store |

对比改造前后，关键差异只有一个：

| | 改造前 | 改造后 |
| --- | --- | --- |
| 消息状态放哪 | `MessageWindowChatMemory` 内的 `List` | `ChatMemoryStore` |
| 窗口负责什么 | 裁剪 + 存数据 | 只裁剪 |
| 能否换存储 | 不能 | 换一个 `ChatMemoryStore` 实现即可 |

**注意**：这里的"记忆"专指**喂给模型的多轮 message**，不是业务平台里给用户看的会话消息列表。两者的生命周期、查询方式、数据量级完全不同，本文只解决前者。

## 1.3 我们到底要解决什么

一句话概括需求：

> 把 `ChatMemory` 里那份"给模型看的消息列表"，从进程内搬到进程外，做到重启可恢复、多实例可共享。

至于会话列表、消息分页、历史回放这些业务功能，不在本次范围内。

---

# 二、核心概念讲解

## 2.1 什么是模型的 ChatMemory

很多人第一反应是"聊天记录存库嘛"，但**模型上下文**和**聊天记录**是两回事：

| 维度 | 模型上下文（ChatMemory） | 业务聊天记录 |
| --- | --- | --- |
| 内容 | `SystemMessage` / `UserMessage` / `AiMessage` / `ToolExecutionResultMessage` | 用户可见的问答气泡 |
| 数量 | 受窗口限制，通常几十到几百条 | 全量历史，可能上万 |
| 用途 | 拼成 API 请求的 `messages` 数组 | 展示、检索、导出 |
| 结构 | 强结构化，含 tool_calls、thinking | 弱结构化 |

**重点**：模型上下文会随窗口裁剪而**变短**，也就是说它是"会被覆盖"的；而业务聊天记录只会"追加"。这个差异直接决定了存储方案的选择。

## 2.2 消息的结构长什么样

AgentForge 的消息类型是 provider 中立的：

```java
public interface ChatMessage {
    ChatMessageType type();      // SYSTEM / USER / AI / TOOL_EXECUTION_RESULT / CUSTOM
    default String text() { ... }
}
```

其中 `AiMessage` 最复杂，它可能同时携带：

- `text`：文本回复
- `thinking`：思考过程
- `toolExecutionRequests`：工具调用请求（id / name / arguments）
- `attributes`：厂商私有属性

`ToolExecutionResultMessage` 则要能和上面的请求通过 `id` 配对。

**注意**：如果序列化时丢掉了 `toolExecutionRequests`，下一轮模型调用就会报"tool call 与 tool result 不匹配"，这是最容易踩的坑。

## 2.3 窗口裁剪规则

`MessageWindowChatMemory` 的裁剪有两个特点，换存储实现时必须保留：

1. **系统消息常驻**：淘汰时从索引 1 开始，永远不动第 0 条系统消息；重复的系统消息会被替换而不是追加。
2. **工具消息成组淘汰**：淘汰带工具调用的 `AiMessage` 时，紧跟其后的 `ToolExecutionResultMessage` 必须一起删，否则就是孤儿消息。

**弊端说明**：如果把这些逻辑下放到存储层，每个 Store 实现都要重写一遍，MySQL 版、Redis 版很容易写得不一致。所以裁剪必须留在窗口层，存储层只做"整段存取"。

## 2.4 为什么要拆出 ChatMemoryStore

一句话：**窗口是策略，存储是机制**。

- **策略**（窗口裁剪）：什么时候淘汰、淘汰哪条、工具消息怎么配对 —— 与具体存储介质无关，永远只有一份实现。
- **机制**（读写消息列表）：内存、MySQL、Redis、对象存储 —— 只关心"取出来"和"整段写回"。

**重点**：拆开之后，换存储不需要动裁剪逻辑，改裁剪也不会影响存储实现。这就是 §1.2 里那次重构的全部意义。

---

# 三、实现思路：三种方案对比

## 3.1 方案一：进程内存储（当前默认）

```java
ChatMemoryStore store = new InMemoryChatMemoryStore();
ChatMemoryProvider provider = new MessageWindowChatMemoryProvider(100, store);
```

**优点**：零依赖，零 IO，单测与本地开发最方便。
**缺点**：进程重启即丢；多实例互不可见。

**适用场景**：单机开发、单元测试、CLI 工具。

## 3.2 方案二：MySQL 一个会话一行（本文采用）

`memory_id` 唯一，`content` 存整段消息 JSON，读 = load 全量，写 = 覆盖。

**优点**：多实例共享；实现简单；数据可查；与现有 `MessageWindowChatMemory` 裁剪逻辑天然契合（裁完再写回）。
**缺点**：覆盖写有放大；单行体积随上下文增长；无法按消息检索。

**适用场景**：中小规模、需要多实例、上下文规模可控。

## 3.3 方案三：MySQL 一条消息一行

`(memory_id, seq)` 唯一，增量追加。

**优点**：无覆盖写；可按消息分页检索；写入成本低。
**缺点**：需要原子取号；并发写同一会话可能交错；孤儿工具消息治理复杂；**与 `ChatMemoryStore` 的"整段覆盖"语义不匹配**，需要额外改造接口。

**适用场景**：超长会话、需要消息级检索的业务平台。

## 3.4 为什么选方案二

**重点**：本文的场景是"模型上下文"，它受 `maxMessages` 窗口约束，体积有天然上限。既然上下文本来就会被裁剪，那一行整段 JSON 的体积就是可控的，方案三带来的取号与并发复杂度换不回相应收益。

更关键的是一致性：`ChatMemoryStore` 的 `updateMessages(memoryId, messages)` 就是"整段覆盖"语义，方案二可以直接实现，方案三则要推翻这个接口。

所以结论是：**表结构统一为 `ai_agent_memrory`，只做必要加固**。

---

# 四、实战代码

## 4.1 表结构

```sql
CREATE TABLE IF NOT EXISTS `ai_agent_memrory` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `memory_id`   VARCHAR(255) NOT NULL COMMENT '聊天记忆ID',
    `content`     LONGTEXT     NOT NULL COMMENT '聊天内容(JSON格式)',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modify_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_memory_id` (`memory_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='聊天消息存储表';
```

几个细节：

- `memory_id` 用 `VARCHAR(255)`。常见初版设计会取 64，但机器人场景的 `robotCode+群组id+用户id` 组合很容易超长，这里直接取 255 避免二次改表。
- `content` 必须是 `LONGTEXT`，普通 `TEXT` 只有 64KB，多轮带工具结果很容易超。
- 唯一键命名为 `uk_memory_id`。有些建表脚本会叫 `idx_memory_id` 但类型其实是 `UNIQUE KEY`，命名与语义不符，容易误解。

## 4.2 分层结构

改造后包按职责分了三层，框架层不依赖 MyBatis：

```
agentforge-agent-core                                  ← 无 DB 依赖
└── agent.memory
    ├── ChatMemory.java                                  ← 契约
    ├── ChatMemoryProvider.java                          ← 契约
    ├── store/
    │   ├── ChatMemoryStore.java                          ← 存储 SPI（纯读写）
    │   └── InMemoryChatMemoryStore.java                  ← 进程内默认实现
    └── window/
        ├── MessageWindowChatMemory.java                  ← 只做裁剪，状态走 Store
        └── MessageWindowChatMemoryProvider.java          ← 按 memoryId 管理窗口

agentforge-service-web                                 ← MyBatis + MySQL
└── modules.agent.memory
    ├── domain.AgentMemoryPojo
    ├── dao.AgentMemoryMapper(+.xml)
    ├── codec.ChatMessageCodec                            ← 消息 ⇄ JSON
    └── store.MysqlChatMemoryStore                        ← 实现 ChatMemoryStore
```

**注意**：`window` 与 `store` 是两个独立维度，前者是策略，后者是机制。包名就直接体现了这层关系。

## 4.3 存储 SPI

现有接口只做纯存取，**不含任何裁剪语义**：

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

**重点**：方法名用的是 `getMessages` / `updateMessages` / `deleteMessages`，与 LangChain4j 的 `ChatMemoryStore` 保持一致，读代码时不用做心智转换。

## 4.4 默认实现：InMemoryChatMemoryStore

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

**注意**两个防御性细节：

1. 写入时 `new ArrayList<>(messages)` 做拷贝，避免调用方后续修改集合影响存储。
2. 读取时返回 `unmodifiableList`，避免调用方原地改掉存储内的数据。

这两点在 MySQL 实现里天然成立（都是反序列化出的新对象），但内存实现必须显式做，否则会出现"改了一个快照，存储里的也变了"的诡异 bug。

## 4.5 窗口实现：只裁剪，不存状态

这是本次改造的核心。`MessageWindowChatMemory` 现在**没有任何 List 字段**，所有数据都从 Store 拿、写回 Store：

```java
public final class MessageWindowChatMemory implements ChatMemory {

    private final Object id;
    private final int maxMessages;
    private final ChatMemoryStore store;      // 唯一的数据来源

    public synchronized void add(ChatMessage... newMessages) {
        if (newMessages == null || newMessages.length == 0) {
            return;
        }
        // ① 一次性读出当前所有消息
        List<ChatMessage> currentMessages = store.getMessages(id);
        List<ChatMessage> allMessages = new LinkedList<ChatMessage>(currentMessages);

        // ② 合并新消息：系统消息去重 / 替换
        for (ChatMessage message : newMessages) {
            if (message == null) {
                continue;
            }
            if (message.type() == ChatMessageType.SYSTEM) {
                int existingSystem = firstSystemIndex(allMessages);
                if (existingSystem >= 0) {
                    if (allMessages.get(existingSystem).equals(message)) {
                        continue;
                    }
                    allMessages.remove(existingSystem);
                }
            }
            allMessages.add(message);
        }

        // ③ 统一裁剪一次
        ensureCapacity(allMessages);

        // ④ 一次性写回
        store.updateMessages(id, allMessages);
    }

    @Override
    public synchronized List<ChatMessage> messages() {
        List<ChatMessage> messages = new LinkedList<ChatMessage>(store.getMessages(id));
        ensureCapacity(messages);
        return Collections.unmodifiableList(new ArrayList<ChatMessage>(messages));
    }

    @Override
    public synchronized void clear() {
        store.deleteMessages(id);
    }
}
```

**重点**：`add` 是一次"读—改—写"的完整闭环，一次 `add` 只产生**一次 store 读 + 一次 store 写**。

**弊端说明**：如果按最朴素的方式实现（每条消息 `getMessages` 一次、`updateMessages` 一次），ReActAgent 一轮会产生 用户消息 + AI(工具调用) + 工具结果 + AI(最终回答) 四条消息，那就是 4 次读 + 4 次写。换成 MySQL 后就是 8 次网络往返，性能直接腰斩。所以 `add(ChatMessage...)` 这个批量重载非常关键。

`ensureCapacity` 保留了全部原有裁剪语义：

```java
private void ensureCapacity(List<ChatMessage> messages) {
    while (messages.size() > maxMessages) {
        // 系统消息常驻，从索引 1 开始淘汰
        int messageToEvictIndex =
                !messages.isEmpty() && messages.get(0).type() == ChatMessageType.SYSTEM ? 1 : 0;
        if (messageToEvictIndex >= messages.size()) {
            return;
        }
        ChatMessage evicted = messages.remove(messageToEvictIndex);
        // 淘汰带工具调用的 AI 消息时，连带淘汰后续的工具结果
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

## 4.6 Provider：所有窗口共享一个 Store

```java
public final class MessageWindowChatMemoryProvider implements ChatMemoryProvider {

    private final int maxMessages;
    private final ChatMemoryStore store;
    private final Map<Object, ChatMemory> memories = new ConcurrentHashMap<Object, ChatMemory>();

    /** 不传 store 时默认用进程内实现 */
    public MessageWindowChatMemoryProvider(int maxMessages) {
        this(maxMessages, new InMemoryChatMemoryStore());
    }

    public MessageWindowChatMemoryProvider(int maxMessages, ChatMemoryStore store) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages must be greater than zero");
        }
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        this.maxMessages = maxMessages;
        this.store = store;
    }

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
}
```

**重点**：单参构造器保留了向后兼容——现有所有调用点不用改一行代码，默认行为与改造前完全一致。

**注意**：所有 `MessageWindowChatMemory` 实例共享同一个 `store`，这是"换存储只要换一个实现"的前提。如果每个窗口自己 new 一个 Store，那 MySQL 版就退化成内存版了。

## 4.7 接 MySQL：实现 ChatMemoryStore

有了 SPI，MySQL 版只需要实现三个方法，完全不用碰窗口逻辑：

```java
public class MysqlChatMemoryStore implements ChatMemoryStore {

    private final AgentMemoryMapper mapper;
    private final ChatMessageCodec codec;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        AgentMemoryPojo pojo = mapper.selectByMemoryId(String.valueOf(memoryId));
        return pojo == null
                ? Collections.<ChatMessage>emptyList()
                : codec.decodeAll(pojo.getContent());
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String content = codec.encodeAll(messages);
        // 写入体积兜底，防 PacketTooBigException
        content = truncateIfTooLarge(content);
        String memoryIdValue = String.valueOf(memoryId);
        int updated = mapper.updateByMemoryId(memoryIdValue, content);
        if (updated == 0) {
            mapper.insert(memoryIdValue, content);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        mapper.deleteByMemoryId(String.valueOf(memoryId));
    }
}
```

SQL 如下：

```xml
<select id="selectByMemoryId" resultMap="ChatMemoryResult">
    select id, memory_id, content, create_time, modify_time
    from ai_agent_memrory where memory_id = #{memoryId}
</select>

<update id="updateByMemoryId">
    update ai_agent_memrory set content = #{content} where memory_id = #{memoryId}
</update>

<insert id="insert">
    insert into ai_agent_memrory (memory_id, content) values (#{memoryId}, #{content})
</insert>

<delete id="deleteByMemoryId">
    delete from ai_agent_memrory where memory_id = #{memoryId}
</delete>
```

**注意**：没有用 `INSERT ... ON DUPLICATE KEY UPDATE`。虽然能一条 SQL 搞定，但它会让自增 `id` 跳号、`create_time` 语义变模糊；改用"先 `update` 后 `insert`"更好排查。

## 4.8 消息编解码

常见的做法是直接引入 LangChain4j 的 `ChatMessageSerializer`。我们不想为此增加外部依赖，而 `agentforge-model-core` 里已经有一个自研的 `internal.json.Json`，直接复用它：

```java
public final class ChatMessageCodec {

    public String encodeAll(List<? extends ChatMessage> messages);
    public List<ChatMessage> decodeAll(String json);
}
```

编码后每条消息长这样：

```json
{
  "type": "AI",
  "text": "我来查一下天气",
  "thinking": "用户想了解天气，需要调用工具",
  "toolRequests": [
    { "id": "call-1", "name": "getWeather", "arguments": "{\"city\":\"杭州\"}" }
  ]
}
```

**重点**：自己写编解码的好处是没有反序列化白名单问题。LangChain4j 的 `Deserializer` 出于安全考虑要做类型白名单校验，跨版本升级偶尔会因为新增消息类型而反序列化失败；自研 codec 只认自己的 `type` 字段，未知类型直接抛异常。

## 4.9 装配到 Agent

改造后装配只需要换一个构造参数：

```java
ReActAgent.builder()
        .chatMemoryProvider(new MessageWindowChatMemoryProvider(maxMessages, mysqlChatMemoryStore))
        .build();
```

不接 MySQL 时保持原样即可（默认走 `InMemoryChatMemoryStore`）：

```java
ReActAgent.builder()
        .chatMemoryProvider(new MessageWindowChatMemoryProvider(maxMessages))
        .build();
```

在 `ChatMemoryConfig` 里加一个开关，默认关闭，方便灰度：

```java
private Boolean persistEnabled = false;
```

## 4.10 落库失败怎么办

`updateMessages` 在框架层是同步调用。**注意**：MySQL 抖动时如果直接把异常抛给 `BaseAgent`，一次存储故障就会让整通对话失败。

建议在 `MysqlChatMemoryStore` 内部做兜底：

```java
@Override
public void updateMessages(Object memoryId, List<ChatMessage> messages) {
    try {
        // ... update / insert
    } catch (RuntimeException e) {
        log.warn("chat memory persist failed, memoryId={}, size={}", memoryId, messages.size(), e);
        // 不向上抛，本轮对话继续
    }
}
```

代价是这一轮上下文可能没落库，下一轮读到的还是旧快照。对模型上下文这种"尽力而为"的数据来说，这个降级是可接受的。

---

# 五、验证测试

## 5.1 单元测试要点

| 用例 | 断言 |
| --- | --- |
| 批量追加 + 系统消息替换 | 一次 `add` 多条形如 `[oldSystem, q1, newSystem, a1]` 后，窗口只剩 `q1, newSystem, a1` |
| 窗口裁剪 | 超过 `maxMessages` 后旧消息被淘汰，系统消息仍在 |
| 工具消息成组淘汰 | `AiMessage`(tool call) 被淘汰时，后续 tool result 一并消失 |
| Provider 复用 | 同一 `memoryId` 返回同一个实例，不同 id 不同实例 |
| Store 隔离 | 写入后修改原集合，不影响 Store 内数据 |
| 编解码往返 | `encodeAll` → `decodeAll` 后消息列表 `equals`，含 tool_calls 与 thinking |

## 5.2 窗口裁剪测试

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

## 5.3 编解码往返测试

```java
@Test
public void shouldRoundTripToolCallMessage() {
    ChatMessageCodec codec = new ChatMessageCodec();
    AiMessage original = AiMessage.builder()
            .text("查询天气")
            .thinking("需要调用工具")
            .toolExecutionRequests(Arrays.asList(
                    ToolExecutionRequest.from("call-1", "getWeather", "{\"city\":\"杭州\"}")))
            .build();

    List<ChatMessage> decoded = codec.decodeAll(codec.encodeAll(Arrays.asList(original)));
    assertEquals(original, decoded.get(0));
}
```

## 5.4 集成验证

用 MySQL 版 Store 跑一遍现有的 `ReActAgentStreamTest`，确认：

1. 多轮对话上下文正确累积；
2. 换一个 `MessageWindowChatMemoryProvider` 实例（模拟重启）后，能读到上一实例写入的上下文；
3. `clear()` 后 `getMessages` 返回空列表；
4. 一轮 ReAct 循环（含工具调用）只产生 **1 次 UPDATE + 1 次 INSERT**，而不是每条消息一次。

---

# 六、总结

回顾一下这次改造的要点：

1. **窗口与存储分离**：这是本次重构的核心。`MessageWindowChatMemory` 不再持有 `List`，只做裁剪；状态全部交给 `ChatMemoryStore`。
2. **向后兼容**：`ChatMemory` / `ChatMemoryProvider` 签名不变，`BaseAgent` / `ReActAgent` 零改动，单参构造器保留，现有代码不用改一行。
3. **一次读写**：`add(ChatMessage...)` 批量重载让一次 `add` 只产生一次 store 读 + 一次 store 写，换 MySQL 后不会因往返次数炸掉。
4. **策略与机制分离**：裁剪逻辑只有一份，MySQL / Redis / 内存实现都复用同一套裁剪语义。
5. **统一业务命名**：表结构使用 `ai_agent_memrory`，明确归属 Agent 会话记忆。
6. **三点加固**：体积兜底防 `PacketTooBigException`、落库失败不回抛、取快照保一致性。

明确遗留（有意为之）：

- **不做消息级查询**：当前需求不需要，将来加投影表即可。
- **不加分布式锁**：同一 `memoryId` 多实例并发仍是后写覆盖，属于该方案的固有取舍；真出现问题再做会话粘滞。
- **不加逻辑删除**：物理删除，避免 `content` 与 `is_deleted` 双写不一致。

**重点**：这套方案的适用边界是"上下文有上限"的 Agent 场景。如果将来要做超长会话的全量历史，请转向方案三（一条消息一行），而不是在这张表上继续堆字段。

---

# 参考资料

[1]. [LangChain4j ChatMemoryStore 接口文档](https://docs.langchain4j.dev/tutorials/chat-memory)

[2]. [MySQL LONGTEXT 与 max_allowed_packet 限制](https://dev.mysql.com/doc/refman/8.0/en/server-system-variables.html#sysvar_max_allowed_packet)

[3]. [MySQL INSERT ... ON DUPLICATE KEY UPDATE 语义说明](https://dev.mysql.com/doc/refman/8.0/en/insert-on-duplicate.html)

[4]. [MyBatis Mapper XML 官方文档](https://mybatis.org/mybatis-3/sqlmap-xml.html)

---

整理者：长路　创建时间：2026.10.25　更新时间：2026.10.25

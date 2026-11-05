---
title: 会话记忆设计
sidebar_label: 会话记忆设计
description: 了解 ChatMemory、ChatMemoryProvider 与 ChatMemoryStore 如何共同管理 Agent 上下文
sidebar_position: 6
displayed_sidebar: sdkSidebar
---

# 会话记忆设计

会话记忆决定 Agent 在下一次模型调用时能看到哪些消息。AgentForge 将“记忆策略”和“记忆存储”拆开：`ChatMemory` 负责消息顺序、系统消息和窗口策略，`ChatMemoryStore` 负责按 `memoryId` 读写消息，`ChatMemoryProvider` 负责为不同会话提供可复用的记忆实例。

```text
AgentRequest.memoryId
        │
        ▼
ChatMemoryProvider.get(memoryId)
        │
        ▼
ChatMemory ── 读取/追加/裁剪 ── ChatMemoryStore
        │
        ▼
ChatRequest.messages()
```

## 依赖

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-agent-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

## 1. 三个核心契约

### `ChatMemory`

```java
public interface ChatMemory {
    Object id();
    void add(ChatMessage message);
    List<ChatMessage> messages();
    void clear();
}
```

它是 Agent 使用的运行时接口。`ReActAgent` 不关心消息存在哪里，只从 `messages()` 构建模型请求，并通过 `add(...)` 写回用户消息、AI 消息和工具结果。

### `ChatMemoryProvider`

```java
public interface ChatMemoryProvider {
    ChatMemory get(Object memoryId);
}
```

Provider 负责把 `memoryId` 映射到会话记忆。一个 Agent 可以被多个会话复用，Provider 必须保证不同 `memoryId` 的消息不会串线。

### `ChatMemoryStore`

```java
public interface ChatMemoryStore {
    List<ChatMessage> getMessages(Object memoryId);
    void updateMessages(Object memoryId, List<ChatMessage> messages);
    void deleteMessages(Object memoryId);
}
```

Store 只负责纯读写，不负责窗口裁剪。这样内存、MySQL、Redis 等存储可以替换，而不会把持久化细节带进 ReAct 主循环。

## 2. 在 Agent SDK 中使用

最简单的方式是使用内置窗口记忆：

```java
ChatMemoryProvider memoryProvider =
        new MessageWindowChatMemoryProvider(100);

ReActAgent agent = ReActAgent.builder()
        .agentName("support-agent")
        .chatModel(chatModel)
        .streamingChatModel(streamingChatModel)
        .chatMemoryProvider(memoryProvider)
        .toolService(toolService)
        .build();

ChatResult result = agent.run(AgentRequest.builder()
        .memoryId("session-001")
        .question("帮我查询订单状态")
        .build());
```

同一个 `memoryId` 再次运行时，会继续使用同一段会话消息：

```java
agent.run(AgentRequest.builder()
        .memoryId("session-001")
        .question("刚才的订单如果已发货，预计什么时候送达？")
        .build());
```

清理上下文：

```java
agent.clearContext("session-001");
```

`clearContext` 只清理聊天记忆，不等于取消正在执行的模型请求；取消运行应调用 `agent.cancel(memoryId)`。

## 3. 记忆写入的顺序

一次 ReAct 轮次通常按下面顺序写入：

```text
用户问题
  -> ChatMemory
  -> 模型返回 AiMessage
  -> ChatMemory
  -> 工具返回 ToolExecutionResultMessage
  -> ChatMemory
  -> 下一轮模型调用
```

系统消息是特殊项：`ReActAgent` 会在初始化时整理系统提示词，窗口记忆应保证系统消息不会被普通消息淘汰。带工具调用的 AI 消息与紧随其后的工具结果也应作为一个完整语义单元处理。

## 4. 如何扩展自己的记忆存储

实现 `ChatMemoryStore` 即可接入外部存储：

```java
public final class RedisChatMemoryStore implements ChatMemoryStore {

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        // 从 Redis 读取并反序列化消息列表
        return load(memoryId);
    }

    @Override
    public void updateMessages(
            Object memoryId, List<ChatMessage> messages) {
        // 使用一个 key 原子覆盖当前会话消息
        save(memoryId, messages);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        // 删除该会话的记忆 key
        remove(memoryId);
    }
}
```

再把 Store 交给记忆 Provider：

```java
ChatMemoryStore store = new RedisChatMemoryStore(redisClient);
ChatMemoryProvider provider =
        new MessageWindowChatMemoryProvider(100, store);
```

扩展时要保证：

- `getMessages` 不返回调用方可以直接修改的内部集合；
- `updateMessages` 对同一 `memoryId` 的并发写有明确语义；
- 消息序列化保留 `UserMessage`、`AiMessage`、工具调用和工具结果的类型信息；
- Store 失败要能被上层识别，不能静默返回一段不完整的上下文。

## 5. 设计取舍

| 设计 | 好处 | 需要注意 |
| --- | --- | --- |
| Memory 与 Store 分离 | 存储可替换，策略不污染 Agent | Store 需要实现完整的消息序列化 |
| Provider 按 `memoryId` 复用 | 同会话自然连续，多会话互相隔离 | 长期运行需要会话过期和缓存清理 |
| 窗口按消息条数裁剪 | 简单、稳定、无需绑定某个模型 tokenizer | 消息很长时仍可能超过上下文限制 |
| 工具消息成组淘汰 | 不留下孤立的工具结果 | 自定义消息顺序必须保持合法配对 |

如果需要按 Token 裁剪、摘要历史或分层记忆，优先实现新的 `ChatMemory` 策略，继续复用 `ChatMemoryStore`，不要修改 `ReActAgent` 主循环。

下一步：阅读[窗口会话记忆](./memory/window)。

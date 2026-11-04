---
title: 窗口会话记忆
sidebar_label: 窗口会话记忆
description: 使用 MessageWindowChatMemoryProvider 控制上下文长度并保持工具消息完整
sidebar_position: 1
displayed_sidebar: sdkSidebar
---

# 窗口会话记忆

`MessageWindowChatMemoryProvider` 是 AgentForge 提供的标准记忆实现。它以消息条数作为窗口上限，在写入消息后统一裁剪，并保留系统消息、工具调用和工具结果之间的结构关系。

## 1. 快速使用

```java
ChatMemoryProvider memoryProvider =
        new MessageWindowChatMemoryProvider(50);

ReActAgent agent = ReActAgent.builder()
        .chatModel(chatModel)
        .streamingChatModel(streamingChatModel)
        .chatMemoryProvider(memoryProvider)
        .toolService(toolService)
        .build();
```

这里的 `50` 表示最多保留 50 条消息，不是 50 个对话轮次。一次工具轮次可能包含用户消息、AI 工具调用和多个工具结果，因此实际覆盖的轮数会随工具使用情况变化。

## 2. 内部策略

窗口记忆的写入过程是：

```text
读取当前消息
  -> 合并新消息
  -> 替换重复的 SystemMessage
  -> 从最早位置开始裁剪
  -> 工具调用 AI 消息与后续工具结果成组移除
  -> 一次性写回 Store
```

### 系统消息常驻

系统消息不会被普通窗口淘汰。如果写入新的系统消息，会替换已有系统消息，而不是不断叠加：

```java
memory.add(SystemMessage.from("你现在是一个客服助手"));
memory.add(SystemMessage.from("你现在是一个售后客服助手"));
```

最终窗口中只保留后一条系统消息。

### 工具消息成组淘汰

当最早消息是带工具调用的 `AiMessage` 时，窗口会同时移除紧随其后的 `ToolExecutionResultMessage`，避免下一次模型请求看到没有对应调用的工具结果。

## 3. 使用外部 Store

窗口策略与存储实现通过构造器解耦：

```java
ChatMemoryStore store = new MysqlChatMemoryStore(memoryMapper);
ChatMemoryProvider provider =
        new MessageWindowChatMemoryProvider(100, store);
```

只要实现以下三个方法，就能接入自己的数据库或缓存：

```java
public interface ChatMemoryStore {
    List<ChatMessage> getMessages(Object memoryId);
    void updateMessages(Object memoryId, List<ChatMessage> messages);
    void deleteMessages(Object memoryId);
}
```

推荐按 `memoryId` 保存一份有序消息列表。窗口类会整段覆盖写回，因此 Store 的 `updateMessages` 应具有清晰的覆盖语义。

## 4. 生产参数怎么选

| 场景 | 建议 |
| --- | --- |
| 短问答、无工具 | 20～50 条 |
| 多轮工具 Agent | 50～200 条，并同时观察 Token 使用量 |
| 长任务 | 使用窗口 + 摘要，不能只无限增大条数 |
| 多实例部署 | 使用共享 Store，并保证同一 `memoryId` 的写入顺序 |

`maxMessages` 不是上下文安全线：长文本、工具返回和思考内容都会占用 Token。需要精确控制上下文时，可以在 `ChatMemory` 层实现 Token 估算或摘要策略。

## 5. 扩展思路

如果要增加“按 Token 裁剪”或“窗口满后先摘要”，有两种实现路线：

1. 保留 `ChatMemoryStore`，新增一个实现了 `ChatMemory` 的策略类；
2. 保留 `MessageWindowChatMemory`，把 `ensureCapacity` 抽成策略接口。

第一种更适合差异较大的摘要记忆；第二种适合只替换淘汰规则。无论采用哪种方式，都应继续保留“系统消息不重复、工具调用结果不孤立”的约束。

下一步：回到[会话记忆设计](../memory)。

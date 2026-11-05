---
title: 对话追加 Middleware
sidebar_label: 对话追加 Middleware
description: 在 Agent Loop 运行期间排队新的用户消息，并在下一次模型调用前注入
sidebar_position: 3
displayed_sidebar: sdkSidebar
---

# 对话追加 Middleware

对话追加能力解决一个具体问题：Agent 正在执行工具或进行下一轮推理时，用户想补充约束，但不希望打断并重启当前 Run。

```text
外部入口 append(memoryId, content)
        -> 待处理队列
        -> 下一次 beforeModelCall
        -> ChatMemory + ChatRequest
        -> 模型继续推理
```

## 1. 设计思路

Core 将能力拆成四层：

| 层 | 契约 | 作用 |
| --- | --- | --- |
| Service | `IUserMessageAppendService` | 对外追加、取消、消费和关闭 |
| Store | `UserMessageAppendStore` | 按 `memoryId` 保存 FIFO 队列 |
| Middleware | `UserMessageAppendMiddleware` | 在 Agent 生命周期中注入消息 |
| Event | `UserMessageAppendEventListener` | 通知排队、生效、延后和取消 |

追加消息不能只写 `ChatMemory`。`beforeModelCall` 收到的 `ChatRequest` 已经是消息快照，所以 Middleware 必须写入 Memory 后重建 Request，并保留原来的 `parameters()`。

## 2. 在 Agent SDK 中接入

Core 提供 Service 接口，具体实现由应用层选择。下面的 `appendService` 可以是 Service 模块的 MySQL 实现，也可以是你基于 `InMemoryUserMessageAppendStore` 封装的实现：

```java
IUserMessageAppendService appendService = applicationAppendService;

UserMessageAppendEventListener listener =
        new UserMessageAppendEventListener() {
            @Override
            public void queued(
                    Object memoryId,
                    AppendedUserMessage message,
                    int queuePosition) {
                publish("QUEUED", memoryId, message);
            }

            @Override
            public void effective(
                    Object memoryId,
                    AppendedUserMessage message,
                    int currentStep) {
                publish("EFFECTIVE", memoryId, message);
            }

            @Override
            public void deferred(
                    Object memoryId,
                    AppendedUserMessage message) {
                publish("DEFERRED", memoryId, message);
            }

            @Override
            public void canceled(
                    Object memoryId,
                    AppendedUserMessage message) {
                publish("CANCELED", memoryId, message);
            }
        };

ReActAgent agent = ReActAgent.builder()
        .agentName("assistant")
        .chatModel(chatModel)
        .streamingChatModel(streamingChatModel)
        .chatMemoryProvider(new MessageWindowChatMemoryProvider(100))
        .toolService(toolService)
        .middleware(new UserMessageAppendMiddleware(
                appendService, listener))
        .build();
```

在 Agent 启动后，外部入口调用同一个 Service：

```java
UserMessageAppendResult result = appendService.append(
        "session-001", "不要修改数据库表结构，完成后补充测试");

if (result.isQueued()) {
    System.out.println("queued appendId="
            + result.getMessage().getAppendId());
}
```

Core 不给 `ReActAgent` 增加业务层 `appendUserMessage` 方法；HTTP、WebSocket 或内部命令都可以复用 Service。

## 3. 生命周期和事件

| 时机 | Store 操作 | 事件 |
| --- | --- | --- |
| Run 开始 | `open(memoryId)` | — |
| 外部追加成功 | `enqueue(...)` | `queued` |
| 下一次模型调用前 | `drain(memoryId)` | `effective` |
| 生效前取消 | `remove(memoryId, appendId)` | `canceled` |
| Run 正常/异常结束 | `closeAndDrain(memoryId)` | 残留消息 `deferred` |

`drain` 会一次性取出当前全部消息，并按 FIFO 合并为一条 `UserMessage`：

```text
The user added the following instructions during the current Agent run:
- 不要修改数据库表结构
- 完成后补充测试
```

如果当前模型请求已经发出，追加消息不会修改在途请求：有下一轮工具调用时下一轮生效；如果当前轮直接 final，则进入 `DEFERRED`。

## 4. 如何扩展

### 替换 Store

继承 `AbstractUserMessageAppendStore`，实现消息的插入、查询、删除、清理和计数。父类负责窗口状态、容量检查、FIFO 消费和同一 `memoryId` 的锁。

多节点部署不能只依赖父类的 `ReentrantLock`，还需要数据库行锁、Redis Lua 或分布式租约，保证 `enqueue`、`drain` 和 `closeAndDrain` 的原子性。

### 替换消息格式

当前 Middleware 使用固定前缀和 bullet 合并。若要逐条注入、增加来源或使用结构化标记，可以抽出：

```java
public interface AppendedMessageFormatter {
    String format(List<AppendedUserMessage> messages);
}
```

Formatter 只负责内容表达，不负责队列和生命周期。

### 扩展事件协议

Listener 可以适配 SSE、WebSocket、审计和前端会话重放。建议所有事件带 `appendId`，并将状态事件与聊天消息分开：事件描述生命周期，`UserMessage` 才是送给模型的上下文。

### 可靠投递

当前 `drain` 是“取出即删除”。如果要求模型调用失败后重试追加消息，应将协议升级为 `claim -> ack/rollback`，而不是在 `drain` 外层简单重试。

## 5. 使用边界

- 追加消息是运行期指令，不是立即打断模型的取消信号；
- 同一个 `memoryId` 默认只对应一个活跃 Run；并发 Run 需要引入 `runId`；
- 空白消息、权限和会话归属应在应用 Service 层校验；
- Listener 是旁路能力，不应因为推送失败阻断模型调用；
- 追加内容可能影响工具选择，生产场景应记录状态和审计信息。

下一步：回到 [Middleware 设计总览](../middleware)。

---
title: 消息事件
sidebar_label: 消息事件
description: 理解 AgentForge 的统一消息模型、模型流式回调、Agent 事件与前端消费方式
sidebar_position: 3
displayed_sidebar: sdkSidebar
---

# 消息事件

AgentForge 把「模型说了什么」和「Agent 正在做什么」拆成两类模型：

- **消息（Message）**：进入模型上下文的数据结构，决定下一轮推理看到什么；
- **事件（Event）**：执行过程中发生的增量与状态变化，决定上层 UI 如何实时渲染。

两者共享同一套标准响应模型，因此同步与流式最终都能收敛为一致的上下文。

## 依赖（v1.0.0）

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-agent-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

消息与模型响应契约位于 `agentforge-model-api`，会由 `agentforge-agent-core` 传递引入。单独使用时：

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-model-api</artifactId>
    <version>1.0.0</version>
</dependency>
```

## 1. 消息类型

```java
public interface ChatMessage {
    ChatMessageType type();
    default String text() { ... }
}
```

| 类型 | 作用 | 关键字段 |
| --- | --- | --- |
| `SystemMessage` | 系统提示词 | `text` |
| `UserMessage` | 用户输入 | `name`、`contents` |
| `AiMessage` | 模型回复 | `text`、`thinking`、`toolExecutionRequests` |
| `ToolExecutionResultMessage` | 工具执行结果 | `id`、`toolName`、`text`、`isError` |
| `CustomMessage` | 自定义消息 | 属性集合 |

### 1.1 SystemMessage

```java
SystemMessage system = SystemMessage.from("你是一个严谨的 Java 助手。");
```

### 1.2 UserMessage

```java
UserMessage text = UserMessage.from("解释一下 AgentForge 的 ReAct 循环。");

UserMessage withContents = UserMessage.from(
        TextContent.from("第一段上下文"),
        TextContent.from("第二段上下文"));
```

纯文本场景可以直接使用 `text()`；包含多个 `Content` 时使用 `contents()` 遍历。当前版本仅提供 `TextContent`，`Content` 接口为后续多模态扩展保留。

### 1.3 AiMessage

```java
AiMessage answer = AiMessage.from("这是最终回答。");

AiMessage withThinking = AiMessage.builder()
        .text("最终回答")
        .thinking("先分析问题，再决定是否调用工具")
        .build();
```

当模型要求调用工具时：

```java
for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
    System.out.println(request.id());
    System.out.println(request.name());
    System.out.println(request.arguments()); // 原始 JSON 字符串
}
```

### 1.4 工具结果

```java
ToolExecutionResultMessage result = ToolExecutionResultMessage.builder()
        .id("call_001")
        .toolName("getWeather")
        .text("{\"weather\":\"晴\"}")
        .isError(false)
        .build();
```

`id` 必须与对应 `ToolExecutionRequest.id()` 一致，模型依赖它把结果与调用配对。

## 2. 响应模型

所有 Provider 最终都返回标准 `ChatResponse`：

```java
ChatResponse response = chatModel.chat(request);

AiMessage message = response.aiMessage();
TokenUsage usage = response.tokenUsage();
FinishReason reason = response.finishReason();
Map<String, Object> metadata = response.metadata();
```

| 字段 | 说明 |
| --- | --- |
| `aiMessage` | 归一化后的模型消息 |
| `tokenUsage` | 输入、输出、总 Token 与缓存命中 Token |
| `finishReason` | `STOP`、`LENGTH`、`TOOL_EXECUTION`、`CONTENT_FILTER`、`OTHER` |
| `metadata` | Provider 附加信息，业务不要强依赖 |

## 3. 模型流式事件

`StreamingChatResponseHandler` 定义模型层的最小事件集：

```java
streamingChatModel.chat(request, new StreamingChatResponseHandler() {
    @Override
    public void onPartialThinking(String partialThinking) {
        // 推理增量，可选
    }

    @Override
    public void onPartialResponse(String partialResponse) {
        // 可见文本增量
    }

    @Override
    public void onCompleteResponse(ChatResponse completeResponse) {
        // 聚合完成的标准响应，恰好一次
    }

    @Override
    public void onError(Throwable error) {
        // 失败，恰好一次
    }
});
```

事件顺序：

```text
onPartialThinking*  →  onPartialResponse*  →  onCompleteResponse
                                          ↘  onError
```

`onCompleteResponse` 不会与 `onError` 同时出现。

## 4. Agent 流式事件

`TokenStream` 在模型事件之上，增加了工具执行与“进入下一轮”的语义：

```java
agent.runStream(request)
        .onPartialThinking(thinking -> { })
        .onPartialResponse(token -> { })
        .onToolExecuted(execution -> { })
        .onIntermediateResponse(response -> { })
        .onCompleteResponse(response -> { })
        .onError(error -> { })
        .start();
```

| 事件 | 对应语义 | 建议 UI 行为 |
| --- | --- | --- |
| `onPartialThinking` | 模型思考增量 | 展示“思考中”折叠区 |
| `onPartialResponse` | 最终回答文本增量 | 逐字渲染 Markdown |
| `onToolExecuted` | 一个工具执行完成 | 更新工具卡片状态与结果 |
| `onIntermediateResponse` | 本轮工具完成，准备下一轮 | 保留本轮工具卡片，开始新推理 |
| `onCompleteResponse` | 整个 ReAct 循环结束 | 结束 loading，落库最终答案 |
| `onError` | 失败或取消 | 展示错误并提供重试 |

一个包含工具调用的典型事件顺序：

```text
onPartialThinking("需要查天气")
onPartialResponse("我来查询北京天气。")
onIntermediateResponse(aiMessage.toolCalls = [getWeather])
onToolExecuted(getWeather -> 晴, 26℃)
onPartialResponse("北京今天晴，26℃。")
onCompleteResponse(finalResponse)
```

### 中间件中的事件

如果需要在事件到达业务层前做统一处理，可以注册 `IStreamingIAgentMiddleware`：

```java
public class EventMirror implements IStreamingIAgentMiddleware {

    @Override
    public void onPartialResponse(
            int currentStep, String partialResponse, AgentChatContext chatContext) {
        // 转发到 WebSocket / SSE / 日志
    }

    @Override
    public void onIntermediateResponse(
            int currentStep, ChatResponse response, AgentChatContext chatContext) {
        // 记录工具调用轮次
    }
}
```

## 5. 消息与事件的协作关系

```text
User Question
     │
     ▼
UserMessage ──────────────► ChatMemory
                                │
                                ▼
                         ChatRequest
                                │
                                ▼
                    ChatModel / StreamingChatModel
                                │
              ┌─────────────────┴─────────────────┐
              │ 同步                              │ 流式
              ▼                                   ▼
        ChatResponse                    partial / complete 事件
              │                                   │
              ▼                                   ▼
         AiMessage ───────────────► ChatMemory（下一轮输入）
              │
              ▼
   ToolExecutionRequest → ToolExecutionResultMessage → ChatMemory
```

关键点：**事件负责“过程”，消息负责“上下文”。** 事件可以不落库，但最终 `AiMessage` 与 `ToolExecutionResultMessage` 必须写回记忆，否则下一轮模型会丢失上下文。

## 6. Service 层 SSE 事件

AgentForge Service 在 SDK 事件之上定义了面向前端的 SSE 协议，便于直接驱动聊天界面：

| 事件 | 含义 |
| --- | --- |
| `session` | 会话建立或恢复 |
| `think` | 思考增量 |
| `resp` | 回答文本增量 |
| `tool_call` | 工具调用与结果 |
| `resp_end` | 本轮回答结束 |
| `error` | 错误 |

SDK 与 Service 的映射关系：

```text
StreamingChatResponseHandler.onPartialThinking  →  think
StreamingChatResponseHandler.onPartialResponse  →  resp
TokenStream.onToolExecuted                      →  tool_call
TokenStream.onCompleteResponse                  →  resp_end
TokenStream.onError                             →  error
```

## 7. 设计建议

1. **事件只做增量**：不要在 `onPartialResponse` 里重复发送完整历史；
2. **最终以 `onCompleteResponse` 为准**：增量文本可能因重连或渲染丢失，落库应使用最终响应；
3. **工具事件要带 `id`**：用 `ToolExecutionRequest.id()` 关联调用与结果；
4. **思考内容与正文分开**：`thinking` 不应混入最终回答；
5. **错误只处理一次**：如果注册了 `onError`，不要再在外层重复抛出同一异常。

下一步：通过 [Middleware 设计](./middleware) 统一处理这些事件。

---
title: Messages and Events
sidebar_label: Messages & Events
description: Understand AgentForge's unified message model, model streaming callbacks, Agent events, and frontend consumption
sidebar_position: 3
displayed_sidebar: sdkSidebar
---

# Messages and Events

AgentForge separates "what the model said" from "what the Agent is doing" into two models:

- **Message**: data structures that enter the model context and determine what the next reasoning round sees;
- **Event**: incremental updates and state changes during execution that determine how a UI renders in real time.

Both share the same standard response model, so synchronous and streaming execution eventually converge on a consistent context.

## Dependency (v1.0.0)

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-agent-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

Message and response contracts live in `agentforge-model-api`, which is transitively included by `agentforge-agent-core`. To use them alone:

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-model-api</artifactId>
    <version>1.0.0</version>
</dependency>
```

## 1. Message types

```java
public interface ChatMessage {
    ChatMessageType type();
    default String text() { ... }
}
```

| Type | Purpose | Key fields |
| --- | --- | --- |
| `SystemMessage` | System prompt | `text` |
| `UserMessage` | User input | `name`, `contents` |
| `AiMessage` | Model reply | `text`, `thinking`, `toolExecutionRequests` |
| `ToolExecutionResultMessage` | Tool result | `id`, `toolName`, `text`, `isError` |
| `CustomMessage` | Custom message | Attributes |

### 1.1 SystemMessage

```java
SystemMessage system = SystemMessage.from("You are a rigorous Java assistant.");
```

### 1.2 UserMessage

```java
UserMessage text = UserMessage.from("Explain the ReAct loop in AgentForge.");

UserMessage withContents = UserMessage.from(
        TextContent.from("First context block"),
        TextContent.from("Second context block"));
```

Use `text()` for plain text and `contents()` when there are multiple `Content` items. The current version only provides `TextContent`; the `Content` interface is reserved for future multimodal support.

### 1.3 AiMessage

```java
AiMessage answer = AiMessage.from("This is the final answer.");

AiMessage withThinking = AiMessage.builder()
        .text("Final answer")
        .thinking("Analyze the question first, then decide whether to call a tool")
        .build();
```

When the model requests tools:

```java
for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
    System.out.println(request.id());
    System.out.println(request.name());
    System.out.println(request.arguments()); // raw JSON string
}
```

### 1.4 Tool results

```java
ToolExecutionResultMessage result = ToolExecutionResultMessage.builder()
        .id("call_001")
        .toolName("getWeather")
        .text("{\"weather\":\"Sunny\"}")
        .isError(false)
        .build();
```

`id` must match the corresponding `ToolExecutionRequest.id()`. The model relies on it to pair results with calls.

## 2. Response model

All Providers return a standard `ChatResponse`:

```java
ChatResponse response = chatModel.chat(request);

AiMessage message = response.aiMessage();
TokenUsage usage = response.tokenUsage();
FinishReason reason = response.finishReason();
Map<String, Object> metadata = response.metadata();
```

| Field | Description |
| --- | --- |
| `aiMessage` | Normalized model message |
| `tokenUsage` | Input, output, total, and cached tokens |
| `finishReason` | `STOP`, `LENGTH`, `TOOL_EXECUTION`, `CONTENT_FILTER`, `OTHER` |
| `metadata` | Provider extras; business code should not depend on them |

## 3. Model streaming events

`StreamingChatResponseHandler` defines the minimum event set at the model layer:

```java
streamingChatModel.chat(request, new StreamingChatResponseHandler() {
    @Override
    public void onPartialThinking(String partialThinking) {
        // Reasoning delta, optional
    }

    @Override
    public void onPartialResponse(String partialResponse) {
        // Visible text delta
    }

    @Override
    public void onCompleteResponse(ChatResponse completeResponse) {
        // Aggregated standard response, exactly once
    }

    @Override
    public void onError(Throwable error) {
        // Failure, exactly once
    }
});
```

Event order:

```text
onPartialThinking*  →  onPartialResponse*  →  onCompleteResponse
                                          ↘  onError
```

`onCompleteResponse` and `onError` are mutually exclusive.

## 4. Agent streaming events

`TokenStream` adds tool execution and "next round" semantics on top of model events:

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

| Event | Meaning | Suggested UI behavior |
| --- | --- | --- |
| `onPartialThinking` | Reasoning delta | Show a "thinking" collapsible section |
| `onPartialResponse` | Final answer text delta | Render Markdown progressively |
| `onToolExecuted` | One tool finished | Update the tool card status and result |
| `onIntermediateResponse` | This round's tools finished, next round is starting | Keep tool cards and start new reasoning |
| `onCompleteResponse` | The entire ReAct loop finished | Stop loading and persist the final answer |
| `onError` | Failure or cancellation | Show an error and offer retry |

Typical event order with tool calls:

```text
onPartialThinking("Need weather data")
onPartialResponse("Let me check the weather in Beijing.")
onIntermediateResponse(aiMessage.toolCalls = [getWeather])
onToolExecuted(getWeather -> Sunny, 26C)
onPartialResponse("Beijing is sunny today, 26C.")
onCompleteResponse(finalResponse)
```

### Events in middleware

Register `IStreamingIAgentMiddleware` to process events before they reach business code:

```java
public class EventMirror implements IStreamingIAgentMiddleware {

    @Override
    public void onPartialResponse(
            int currentStep, String partialResponse, AgentChatContext chatContext) {
        // Forward to WebSocket / SSE / logs
    }

    @Override
    public void onIntermediateResponse(
            int currentStep, ChatResponse response, AgentChatContext chatContext) {
        // Record tool-call rounds
    }
}
```

## 5. How messages and events cooperate

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
              │ Synchronous                       │ Streaming
              ▼                                   ▼
        ChatResponse                    partial / complete events
              │                                   │
              ▼                                   ▼
         AiMessage ───────────────► ChatMemory (next input)
              │
              ▼
   ToolExecutionRequest → ToolExecutionResultMessage → ChatMemory
```

Key point: **events describe the process; messages describe the context.** Events do not have to be persisted, but the final `AiMessage` and `ToolExecutionResultMessage` must be written back to memory, otherwise the next round loses context.

## 6. Service SSE events

AgentForge Service defines an SSE protocol on top of SDK events for frontend chat UIs:

| Event | Meaning |
| --- | --- |
| `session` | Session created or restored |
| `think` | Reasoning delta |
| `resp` | Answer text delta |
| `tool_call` | Tool call and result |
| `resp_end` | Current answer finished |
| `error` | Error |

Mapping between SDK and Service events:

```text
StreamingChatResponseHandler.onPartialThinking  →  think
StreamingChatResponseHandler.onPartialResponse  →  resp
TokenStream.onToolExecuted                      →  tool_call
TokenStream.onCompleteResponse                  →  resp_end
TokenStream.onError                             →  error
```

## 7. Design recommendations

1. **Events are incremental only**: do not resend the full history from `onPartialResponse`;
2. **Use `onCompleteResponse` as the source of truth**: deltas may be lost on reconnect or render failures;
3. **Include `id` in tool events**: pair calls and results with `ToolExecutionRequest.id()`;
4. **Separate reasoning from the answer**: `thinking` must not be mixed into the final response;
5. **Handle errors once**: if `onError` is registered, do not rethrow the same error outside.

Next: use [Middleware](./middleware) to process these events consistently.

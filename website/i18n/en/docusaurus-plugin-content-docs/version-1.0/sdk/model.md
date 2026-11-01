---
title: Model Protocol Layer
sidebar_label: Model
description: Use one Provider-neutral contract for synchronous and streaming calls to OpenAI, Anthropic, and OpenAI-compatible models
sidebar_position: 1
displayed_sidebar: sdkSidebar
---

# Model Protocol Layer

The Model layer is AgentForge's lowest and most stable model access abstraction. It does not bind to any vendor SDK. It defines unified requests, responses, messages, streaming callbacks, HTTP transport, and error boundaries. Upper layers depend only on `agentforge-model-api`, so switching models does not require changing the ReAct main loop.

## Dependency (v1.0.0)

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-model-openai</artifactId>
    <version>1.0.0</version>
</dependency>
```

Anthropic:

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-model-anthropic</artifactId>
    <version>1.0.0</version>
</dependency>
```

To select a Provider dynamically from configuration, add the registry:

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-model-registry</artifactId>
    <version>1.0.0</version>
</dependency>
```

You can also manage versions with the BOM:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>cloud.changlu.agentforge</groupId>
            <artifactId>agentforge-ai-bom</artifactId>
            <version>1.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

## 1. What this layer solves

If an Agent depends directly on a vendor SDK, it binds three things at once: SDK types, request/response structures, and field names. Switching to Anthropic means system becomes a top-level field, tools become `tool_use`, and responses become content block arrays. Upper-layer code would have to be rewritten.

AgentForge draws a clear boundary:

```text
Agent / Application
       │
       ▼
ChatModel / StreamingChatModel          <- unified entry
       │
       ▼
ChatRequest + Message + Parameters      <- unified input
       │
       ▼
Provider Adapter(OpenAI / Anthropic)    <- protocol mapping only
       │
       ▼
HttpTransport -> Provider HTTP API      <- vendor differences isolated
       │
       ▼
ChatResponse + TokenUsage + FinishReason <- unified output
```

In one sentence: **upper layers always see `ChatRequest` in and `ChatResponse` out; vendor protocols exist only inside Provider modules.**

## 2. Core contracts

```text
Standard ChatModel protocol
├── Entry: ChatModel / StreamingChatModel
├── Input: ChatRequest + ChatRequestParameters (+ ToolChoice)
├── Messages: System / User / Ai / ToolResult / Custom
├── Output: ChatResponse + TokenUsage + FinishReason
├── Streaming: StreamingChatResponseHandler
├── Transport: HttpTransport / StreamingHttpResponseHandler
└── Errors: ModelException
```

### 2.1 ChatModel

```java
@FunctionalInterface
public interface ChatModel {

    ChatResponse chat(ChatRequest chatRequest);

    default String chat(String userMessage);
    default ChatResponse chat(ChatMessage... messages);
    default ChatResponse chat(List<? extends ChatMessage> messages);
}
```

A Provider only implements the main entry `ChatResponse chat(ChatRequest)`. The convenience methods are all `default` and converge on the same `ChatRequest`.

### 2.2 StreamingChatModel

```java
public interface StreamingChatModel {

    void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler);

    default void chat(String userMessage, StreamingChatResponseHandler handler);
    default void chat(StreamingChatResponseHandler handler, ChatMessage... messages);
    default void chat(List<? extends ChatMessage> messages, StreamingChatResponseHandler handler);
}
```

AgentForge uses a callback-based streaming API instead of Reactor or RxJava because the protocol layer must stay lightweight and Java 8 compatible. The streaming lifecycle is fixed:

```text
onPartialThinking(partialThinking)   0..N   reasoning deltas, optional
onPartialResponse(partialText)       0..N   visible text deltas
onCompleteResponse(response)         1      aggregated standard response
onError(error)                       1      failure
```

`onCompleteResponse` still returns a standard `ChatResponse`. Tool call deltas, token usage, and finish reason are aggregated by the Provider before the stream ends.

## 3. Synchronous request

```java
import cloud.changlu.agentforge.model.chat.message.SystemMessage;
import cloud.changlu.agentforge.model.chat.message.UserMessage;
import cloud.changlu.agentforge.model.chat.request.ChatRequest;
import cloud.changlu.agentforge.model.chat.request.DefaultChatRequestParameters;
import cloud.changlu.agentforge.model.chat.response.ChatResponse;

ChatRequest request = ChatRequest.builder()
        .message(SystemMessage.from("You are a Java assistant."))
        .message(UserMessage.from("Explain CompletableFuture."))
        .parameters(DefaultChatRequestParameters.builder()
                .modelName("gpt-4o-mini")
                .temperature(0.2)
                .maxTokens(1024)
                .build())
        .build();

ChatResponse response = chatModel.chat(request);

System.out.println(response.aiMessage().text());
System.out.println(response.finishReason());
System.out.println(response.tokenUsage().totalTokens());
```

### Parameter merging

`DefaultChatRequestParameters.merge(defaults, overrides)` applies model-level defaults first, then request-level overrides:

```text
Model Builder defaults
        │
        ▼
merge(defaults, overrides)
        ▲
        │
Request overrides
```

- Non-null request values override model defaults;
- `customParameters` is merged as a map, with request keys taking precedence;
- Default methods such as `tools` and `toolChoice` allow future extensions without breaking implementations.

## 4. Streaming request

```java
import cloud.changlu.agentforge.model.chat.response.StreamingChatResponseHandler;

streamingChatModel.chat(request, new StreamingChatResponseHandler() {
    @Override
    public void onPartialThinking(String thinking) {
        System.out.print("[think] " + thinking);
    }

    @Override
    public void onPartialResponse(String token) {
        System.out.print(token);
    }

    @Override
    public void onCompleteResponse(ChatResponse response) {
        System.out.println("\n[done] " + response.aiMessage().text());
    }

    @Override
    public void onError(Throwable error) {
        error.printStackTrace();
    }
});
```

For reasoning models, `onPartialThinking` receives `reasoning_content` / `thinking` deltas first. Keep the default empty implementation if you do not need them.

## 5. Choosing a Provider

### 5.1 Direct construction (fixed model)

```java
ChatModel chatModel = OpenAiChatModel.builder()
        .baseUrl("https://api.openai.com/v1")
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o-mini")
        .temperature(0.2)
        .maxTokens(1024)
        .build();
```

Anthropic:

```java
AnthropicChatModel chatModel = AnthropicChatModel.builder()
        .apiKey(System.getenv("ANTHROPIC_API_KEY"))
        .modelName("claude-3-5-sonnet-latest")
        .maxTokens(2048)
        .build();
```

### 5.2 Dynamic construction with LlmFactory

Useful when model configuration comes from a database, admin console, or config file:

```java
import cloud.changlu.agentforge.model.registry.LlmFactory;
import cloud.changlu.agentforge.model.registry.config.LlmBasicConfig;
import cloud.changlu.agentforge.model.registry.enums.LlmEnum;

LlmBasicConfig config = LlmBasicConfig.builder()
        .provider(LlmEnum.OPENAI.getCode())   // 1 = OpenAI, 2 = Anthropic
        .url("https://api.openai.com/v1")
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o-mini")
        .prop("temperature", "0.2")
        .prop("maxTokens", "1024")
        .build();

ChatModel chatModel = LlmFactory.buildChatModel(config);
StreamingChatModel streamingChatModel = LlmFactory.buildStreamChatModel(config);
```

### 5.3 OpenAI-compatible services

`OpenAiChatModel` supports a configurable `baseUrl`, so DashScope, Ollama, Xinference, and other compatible services can reuse it directly:

```java
ChatModel model = OpenAiChatModel.builder()
        .baseUrl("http://localhost:11434/v1")
        .apiKey("ollama")
        .modelName("qwen2.5:7b")
        .build();
```

## 6. Vendor capability mapping

| Unified concept | OpenAI Chat Completions | Anthropic Messages |
| --- | --- | --- |
| `SystemMessage` | `messages[role=system]` | top-level `system` field |
| `UserMessage` | `messages[role=user]` | `text` block in `content` |
| `AiMessage` | `choices[0].message` | `content` block array |
| `ToolExecutionRequest` | `tool_calls` | `tool_use` block |
| `ToolExecutionResultMessage` | `role=tool` | `tool_result` block |
| Streaming text | SSE `delta.content` | SSE `content_block_delta` |
| Reasoning delta | `reasoning_content` | `thinking` block |
| Token usage | `usage.prompt_tokens` etc. | `usage.input_tokens` etc. |

All field differences are handled by Providers. The Agent layer never needs `if (provider == ...)`.

## 7. Errors and stability

Model call failures surface as `ModelException`:

```java
try {
    ChatResponse response = chatModel.chat(request);
} catch (ModelException e) {
    System.err.println("HTTP: " + e.statusCode());
    System.err.println("Body: " + e.responseBody());
}
```

The Agent layer retries non-streaming model calls with exponential backoff. The retry count and initial delay are controlled by `AgentSettings`:

```java
AgentSettings settings = AgentSettings.builder()
        .aiCallRetry(3)
        .aiCallRetryDelay(2000)
        .build();
```

## 8. Best practices

1. **Depend on interfaces**: inject `ChatModel` / `StreamingChatModel`; do not instantiate Providers in business code;
2. **Layer parameters**: put model-level settings in the Builder and per-call differences in `ChatRequestParameters`;
3. **Avoid vendor fields**: use `customParameters` for vendor-specific features instead of parsing vendor JSON in business code;
4. **Keep streaming callbacks light**: Providers may call back from an I/O thread; move heavy work to an application thread pool;
5. **Record usage consistently**: `ChatResponse.tokenUsage()` is normalized and can drive billing and monitoring.

Next: connect models to [Agent (Streaming & Non-streaming)](./agent).

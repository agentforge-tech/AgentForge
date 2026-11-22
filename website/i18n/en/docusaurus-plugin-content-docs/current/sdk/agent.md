---
title: "Agent: Streaming and Non-streaming"
sidebar_label: Agent (Streaming & Non-streaming)
description: Use ReActAgent to organize the think → act → memory loop and switch freely between synchronous and streaming execution
sidebar_position: 2
displayed_sidebar: sdkSidebar
---

# Agent: Streaming and Non-streaming

The AgentForge Agent runtime is organized around the ReAct loop: the model first decides whether tools are needed, executes them and writes results back to conversation memory when needed, then enters the next reasoning round until it produces a final answer or reaches the maximum step count.

The same `ReActAgent` instance, with the same configuration and the same `memoryId`, can run synchronously with `run()` or stream with `runStream()`.

## Dependency (v1.0.0)

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-agent-core</artifactId>
    <version>1.0.1-SNAPSHOT</version>
</dependency>
```

`agentforge-agent-core` transitively brings `agentforge-model-api` and `agentforge-model-core`. At runtime you also need at least one model Provider:

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-model-openai</artifactId>
    <version>1.0.1-SNAPSHOT</version>
</dependency>
```

## 1. The ReAct loop

```text
User Question
     │
     ▼
┌─────────────────────────────┐
│ step = 1                    │
│  check cancel               │
│  think  ──► ChatModel       │
│    │                        │
│    ├── no tool calls ─► STOP│
│    │                        │
│    └── tool calls           │
│          act ──► ToolService│
│           │                 │
│           └── write memory ─┘
│                  step + 1
└─────────────────────────────┘
```

- **think**: call the model with current conversation messages and all tool declarations;
- **act**: execute `ToolExecutionRequest`s and write results back to memory;
- **stop**: finish when the model no longer requests tools and return the text answer;
- **maxSteps**: return `MAX_STEPS` when the step limit is reached.

## 2. Core classes

| Class | Responsibility |
| --- | --- |
| `IAgent` | Public contract: `run` / `runStream` / `cancel` / `clearContext` |
| `BaseAgent` | Context initialization, user message insertion, cancel flags by `memoryId` |
| `Agent` | Non-streaming template method, streaming entry, parameter validation |
| `BaseReActAgent` | Single `step()`: cancel check → think → cancel check → act |
| `ReActAgent` | Assembles models, tools, memory, system prompt, and middleware |
| `ReActTokenStream` | Streaming driver: recursively advances the think → act loop via callbacks |

## 3. Build an Agent

```java
import cloud.changlu.agentforge.agent.ReActAgent;
import cloud.changlu.agentforge.agent.domain.AgentSettings;
import cloud.changlu.agentforge.agent.extend.memory.window.MessageWindowChatMemoryProvider;
import cloud.changlu.agentforge.model.tool.execution.ToolService;

ToolService toolService = new ToolService();
toolService.tools(new WeatherTools());

ReActAgent agent = ReActAgent.builder()
        .agentName("weather-agent")
        .description("ReAct Agent for weather queries")
        .systemPrompt("You are a weather assistant. Call tools when needed and never invent data.")
        .chatModel(chatModel)
        .streamingChatModel(streamingChatModel)
        .chatMemoryProvider(new MessageWindowChatMemoryProvider(50))
        .toolService(toolService)
        .agentSettings(AgentSettings.builder()
                .maxSteps(10)
                .aiCallRetry(3)
                .aiCallRetryDelay(2000)
                .enableConcurrentToolExecution(true)
                .build())
        .build();
```

### Builder parameters

| Parameter | Required | Description |
| --- | --- | --- |
| `chatModel` | For non-streaming | Synchronous model |
| `streamingChatModel` | For streaming | Streaming model |
| `chatMemoryProvider` | Yes | Provides conversation memory by `memoryId` |
| `toolService` | For non-streaming | Tool declaration and executor registry |
| `systemPrompt` | No | Injected at the start of each round |
| `agentSettings` | No | Step limit, retries, concurrent tool switch |
| `middleware(s)` | No | Executed in registration order |

> Set both `chatModel` and `streamingChatModel` so the same Agent can switch between synchronous and streaming execution.

## 4. Non-streaming execution

```java
import cloud.changlu.agentforge.agent.domain.AgentRequest;
import cloud.changlu.agentforge.agent.step.ChatResult;

ChatResult result = agent.run(AgentRequest.builder()
        .memoryId("user-1")
        .question("What is the weather in Beijing today?")
        .build());

System.out.println(result.getRes());
System.out.println(result.getRunState().getDescription());
```

`run()` blocks until the entire ReAct loop finishes and returns:

- `getRes()`: the final answer, or a cancellation / step-limit message;
- `getRunState()`: `SUCCESS`, `MODEL_CALL_ERROR`, `CANCEL`, or `MAX_STEPS`;
- `getRunStateCode()` / `getRunStateDescription()`: convenient for API responses.

## 5. Streaming execution

```java
agent.runStream(AgentRequest.builder()
                .memoryId("user-1")
                .question("What about Shanghai?")
                .build())
        .onPartialThinking(thinking ->
                System.out.print("[think] " + thinking.text()))
        .onPartialResponse(token ->
                System.out.print(token))
        .onToolExecuted(execution -> {
            System.out.println("\n[tool] " + execution.request().name());
            System.out.println("[result] " + execution.result().text());
        })
        .onIntermediateResponse(response ->
                System.out.println("[next-round] toolCalls="
                        + response.aiMessage().toolExecutionRequests().size()))
        .onCompleteResponse(response ->
                System.out.println("\n[done] " + response.aiMessage().text()))
        .onError(error ->
                System.err.println("[error] " + error.getMessage()))
        .start();
```

### TokenStream callbacks

| Callback | When it fires | Typical use |
| --- | --- | --- |
| `onPartialThinking` | Model produces reasoning deltas | Show the reasoning process |
| `onPartialResponse` | Model produces visible text deltas | Render the answer progressively |
| `onToolExecuted` | A single tool finishes | Show tool progress and results |
| `onIntermediateResponse` | This round has tool calls and tools finished | Mark the next reasoning round |
| `onCompleteResponse` | Final answer is complete | Finish rendering and write audit records |
| `onError` | Execution fails or is cancelled | Error fallback |

`runStream()` returns a handle without starting the request. Execution begins only after callbacks are registered and `start()` is called.

## 6. AgentSettings

```java
AgentSettings settings = AgentSettings.builder()
        .maxSteps(10)                          // Max think + act rounds, default 10
        .aiCallRetry(3)                        // Model retry count, default 3
        .aiCallRetryDelay(2000)                // First retry waits 2s, then exponential backoff
        .enableConcurrentToolExecution(true)   // Run multiple tools in one round concurrently
        .build();
```

- `enableConcurrentToolExecution` only takes effect when there is more than one tool;
- When enabled, a concurrent-call hint is appended to the system prompt;
- The non-streaming path uses `aiCallRetry` for exponential backoff.

## 7. Conversation memory

```java
ChatMemoryProvider memoryProvider = new MessageWindowChatMemoryProvider(50);
```

- The same `memoryId` reuses the same memory;
- The window keeps the most recent 50 messages by default;
- System messages are always retained, and evicting an AI message with tool calls also evicts its tool results;
- Replace `ChatMemoryStore` to persist to MySQL, Redis, or another store.

```java
agent.clearContext("user-1");
```

## 8. Cancellation

```java
agent.cancel("user-1");
```

Cancellation is **cooperative**:

- Non-streaming checks the flag before and after each `think`;
- Streaming checks the flag at every `chat()` entry;
- An in-flight HTTP request or running tool is not forcibly interrupted, so cancellation latency is bounded by the current model or tool call.

## 9. Custom Agents

If ReAct is not enough, extend the template classes:

```java
public class MyAgent extends Agent {

    @Override
    protected StepResult step(int currentStep, AgentChatContext chatContext) {
        return StepResult.toStop("done");
    }

    @Override
    protected TokenStream stepStream(AgentChatContext chatContext) {
        return null;
    }

    // Also implement getChatModel / getChatMemoryProvider / getToolService /
    // getAgentSettings / getMiddlewareManager
}
```

Most scenarios do not need a custom Agent. Prefer tools, middleware, and `AgentSettings`.

## 10. Attachments (images / text / file references)

`AgentRequest` supports attachments through `attachments`. The framework assembles them into a `UserMessage(List<Content>)`, writes it to conversation memory together with the user question, and uses the same request shape for synchronous and streaming execution.

### 10.1 Minimal example

```java
import cloud.changlu.agentforge.agent.domain.AgentAttachment;
import cloud.changlu.agentforge.agent.domain.AgentAttachmentType;
import cloud.changlu.agentforge.agent.domain.AttachmentPayloadType;
import cloud.changlu.agentforge.agent.domain.AgentRequest;

AgentRequest request = AgentRequest.builder()
        .memoryId("session-001")
        .question("Describe the content of this image")
        .attachments(Arrays.asList(
                AgentAttachment.builder()
                        .fileId(1024L)
                        .name("error-screenshot.png")
                        .type(AgentAttachmentType.IMAGE)
                        .fileType("png")
                        .size(183462L)
                        .url("https://example.com/upload/8f3c.png")
                        .payloadType(AttachmentPayloadType.URL)
                        .build()))
        .build();

ChatResult result = agent.run(request);
```

Streaming uses the same request:

```java
agent.runStream(request)
        .onPartialResponse(token -> System.out.print(token))
        .start();
```

### 10.2 Three attachment categories

| Category | `type` | Recommended payload | Framework behavior |
| --- | --- | --- | --- |
| Plain text | `TEXT` | `TEXT` | Uses `text` when present; otherwise generates a URL reference |
| Image | `IMAGE` | `URL` / `INLINE` / `TEXT` | External URL, Base64, or text reference |
| Archive / office document / unknown | `UNKNOWN` | `TEXT` | Generates a reference with name, type, size, and URL |

### 10.3 Text attachments

The framework does not read local files. Prepare the text in business code and pass it through `text`:

```java
AgentAttachment doc = AgentAttachment.builder()
        .fileId(2048L)
        .name("requirements.md")
        .type(AgentAttachmentType.TEXT)
        .fileType("md")
        .size(8421L)
        .url("https://example.com/upload/requirements.md")
        .payloadType(AttachmentPayloadType.TEXT)
        .text("Attachment content:\n......")
        .build();
```

If `text` is empty, the model only receives the file name, type, size, and URL. Limit attachment count and text length in business code to protect the context window.

### 10.4 Image attachments

There are three ways to pass an image:

```java
// 1. External URL: pass the URL to models that accept remote images
AgentAttachment urlImage = AgentAttachment.builder()
        .name("chart.png")
        .type(AgentAttachmentType.IMAGE)
        .fileType("png")
        .url("https://example.com/chart.png")
        .payloadType(AttachmentPayloadType.URL)
        .build();

// 2. Inline: the framework downloads the image and encodes it as Base64
AgentAttachment inlineImage = AgentAttachment.builder()
        .name("chart.png")
        .type(AgentAttachmentType.IMAGE)
        .fileType("png")
        .url("https://example.com/chart.png")
        .payloadType(AttachmentPayloadType.INLINE)
        .build();

// 3. Text reference: for non-vision models or oversized images
AgentAttachment textImage = AgentAttachment.builder()
        .name("chart.png")
        .type(AgentAttachmentType.IMAGE)
        .fileType("png")
        .url("https://example.com/chart.png")
        .payloadType(AttachmentPayloadType.TEXT)
        .build();
```

`INLINE` uses the built-in `UrlImageLoader` to download `http/https` images. If loading or Base64 conversion fails, the framework degrades to a text reference instead of failing the whole turn.

> When using the SDK directly, the framework trusts your `payloadType`; it does not check whether the model supports images. Set images to `TEXT` for non-vision models. AgentForge Service performs this routing automatically based on model capability.

### 10.5 Custom image loading

If images live on local disk, OSS, S3, or require signed access, implement `ImageLoader` and register it globally:

```java
public class OssImageLoader implements ImageLoader {

    @Override
    public ImageBinary load(String url) {
        byte[] bytes = ossClient.read(url);
        return new ImageBinary(bytes, "image/png");
    }
}

AttachmentContentAssembler.setDefaultImageLoader(new OssImageLoader());
```

Set it once at startup; all subsequent `INLINE` images use it.

### 10.6 Constraints

- `AgentRequest.Builder.build()` requires a non-blank `question`. When sending attachments only, inject a placeholder such as "I uploaded attachments; answer based on them.";
- `payloadType` defaults to `TEXT`, and `type` defaults to `UNKNOWN`;
- `URL` / `INLINE` only apply to `IMAGE`;
- Ownership checks, count limits, and text truncation belong to business code;
- If resources come from user-provided URLs instead of local uploads, pair this with [Resource Reading Middleware](./middleware/read).

## 11. Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| Loop runs until max steps | Tool descriptions are unclear, or tool results do not let the model converge; improve the system prompt and tool output |
| Tools are never executed | Tools are not registered in `ToolService`, or the model cannot see declarations |
| No reasoning output while streaming | The model does not return reasoning deltas, or the Provider has not enabled `returnThinking` |
| Conversation context is mixed up | Multiple turns ran concurrently for one `memoryId`; serialize them in business code |
| Next run is cancelled immediately | Check for reused state; `prepareForNewRun` clears residual flags from the previous run |

Next: learn about [Messages & Events](./message-event) during execution.

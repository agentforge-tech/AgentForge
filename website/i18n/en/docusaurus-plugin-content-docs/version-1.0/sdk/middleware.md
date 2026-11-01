---
title: Middleware Design
sidebar_label: Middleware
description: Attach composable cross-cutting logic to the Agent loop, model calls, tool execution, and stop stages
sidebar_position: 4
displayed_sidebar: sdkSidebar
---

# Middleware Design

Middleware extracts cross-cutting concerns such as logging, tracing, auditing, masking, rate limiting, and dynamic prompts from the Agent main loop. Inspired by LangChain's Agent Middleware, it inserts custom behavior at fixed lifecycle points through default methods.

## Dependency (v1.0.0)

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-agent-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

The Middleware contract, manager, and built-in implementations all live in `agentforge-agent-core`.

## 1. Two interfaces

### 1.1 IAgentMiddleware

Covers the non-streaming lifecycle:

```java
public interface IAgentMiddleware {

    default void onInitComplete(AgentChatContext chatContext) {}

    default void beforeLoop(int currentStep, AgentChatContext chatContext) {}
    default void afterLoop(int currentStep, StepResult stepResult, AgentChatContext chatContext) {}
    default void onLoopError(int currentStep, Throwable error, AgentChatContext chatContext) {}

    default void onAiCallRetry(
            int currentStep, ChatRequest chatRequest, AgentChatContext chatContext,
            int retryCount, int maxRetries, long delayMs, Exception lastException) {}

    default void beforeToolExecution(
            ToolExecutionRequest toolRequest, AgentChatContext chatContext) {}
    default String afterToolExecution(
            ToolExecutionRequest toolRequest, String toolResult, AgentChatContext chatContext) {
        return toolResult;
    }
    default void onToolExecutionError(
            ToolExecutionRequest toolRequest, Throwable error, AgentChatContext chatContext) {}

    default ChatRequest beforeModelCall(
            int currentStep, ChatRequest chatRequest, AgentChatContext chatContext) {
        return chatRequest;
    }
    default ChatResponse afterModelCall(
            int currentStep, ChatRequest chatRequest, ChatResponse chatResponse,
            AgentChatContext chatContext) {
        return chatResponse;
    }
    default void onModelCallError(
            int currentStep, ChatRequest chatRequest, Throwable error,
            AgentChatContext chatContext) {}

    default void onStop(
            int currentStep, StopResult stopResult, AgentChatContext chatContext) {}
    default void onStopWithError(
            int currentStep, Throwable error, AgentChatContext chatContext) {}
}
```

### 1.2 IStreamingIAgentMiddleware

Adds streaming events on top of `IAgentMiddleware`:

```java
public interface IStreamingIAgentMiddleware extends IAgentMiddleware {

    default void onPartialResponse(
            int currentStep, String partialResponse, AgentChatContext chatContext) {}

    default void onPartialThinking(
            int currentStep, PartialThinking partialThinking, AgentChatContext chatContext) {}

    default void onIntermediateResponse(
            int currentStep, ChatResponse intermediateResponse, AgentChatContext chatContext) {}

    default boolean handlesStreamingEvents() {
        return true;
    }
}
```

Middleware that only needs non-streaming events should override `handlesStreamingEvents()` to return `false`.

## 2. Lifecycle

```text
onInitComplete
    │
    ▼
┌─ beforeLoop(step)
│     │
│     ├─ beforeModelCall(step, request)
│     │       │
│     │       ├─ onAiCallRetry(...)         on model retry
│     │       ├─ afterModelCall(step, response)
│     │       └─ onModelCallError(...)      on model failure
│     │
│     ├─ beforeToolExecution(request)
│     │       ├─ afterToolExecution(request, result)   can rewrite results
│     │       └─ onToolExecutionError(...)
│     │
│     └─ afterLoop(step, stepResult)
│
├─ onLoopError(step, error)                 round-level error
│
└─ onStop(step, stopResult)                 normal / cancel / max steps
   onStopWithError(step, error)             error stop
```

The streaming path additionally fires:

```text
onPartialThinking → onPartialResponse → onIntermediateResponse → onToolExecuted (TokenStream)
```

## 3. Register middleware

```java
ReActAgent agent = ReActAgent.builder()
        .chatModel(chatModel)
        .streamingChatModel(streamingChatModel)
        .chatMemoryProvider(new MessageWindowChatMemoryProvider(50))
        .toolService(toolService)
        .middleware(new LoggingIAgentMiddleware())
        .middleware(new AuditMiddleware())
        .build();
```

Or register a batch:

```java
.middlewares(Arrays.asList(
        new LoggingIAgentMiddleware(),
        new AuditMiddleware()))
```

**Order**: middleware runs in registration order. For callbacks that can rewrite results (`beforeModelCall`, `afterModelCall`, `afterToolExecution`), the value passes through the chain.

## 4. Write a custom middleware

The following audit middleware records model call latency and masks phone numbers in tool results:

```java
import cloud.changlu.agentforge.agent.component.middleware.IAgentMiddleware;
import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.domain.StopResult;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.chat.request.ChatRequest;
import cloud.changlu.agentforge.model.chat.response.ChatResponse;

public class AuditMiddleware implements IAgentMiddleware {

    private static final String START_KEY = "audit.model.start";

    @Override
    public void beforeModelCall(
            int currentStep, ChatRequest chatRequest, AgentChatContext chatContext) {
        chatContext.putExtension(START_KEY, System.currentTimeMillis());
    }

    @Override
    public void afterModelCall(
            int currentStep, ChatRequest chatRequest, ChatResponse chatResponse,
            AgentChatContext chatContext) {
        Object start = chatContext.getExtension(START_KEY);
        if (start instanceof Long) {
            long cost = System.currentTimeMillis() - (Long) start;
            System.out.println("[audit] step=" + currentStep + " modelCost=" + cost + "ms");
        }
    }

    @Override
    public String afterToolExecution(
            ToolExecutionRequest toolRequest, String toolResult, AgentChatContext chatContext) {
        return toolResult.replaceAll("1[3-9]\\d{9}", "***********");
    }

    @Override
    public void onStop(
            int currentStep, StopResult stopResult, AgentChatContext chatContext) {
        System.out.println("[audit] stop state=" + stopResult.getStopResultState());
    }
}
```

`AgentChatContext` provides `putExtension` / `getExtension` for per-run temporary state without polluting conversation messages.

## 5. Modify requests and responses

### 5.1 Inject a dynamic system prompt

```java
@Override
public ChatRequest beforeModelCall(
        int currentStep, ChatRequest chatRequest, AgentChatContext chatContext) {
    // Return a new ChatRequest; keep the original messages and parameters
    return chatRequest;
}
```

Returning `null` aborts the current model call, and the Agent finishes with "model call interrupted by middleware".

### 5.2 Rewrite tool results

```java
@Override
public String afterToolExecution(
        ToolExecutionRequest toolRequest, String toolResult, AgentChatContext chatContext) {
    if (toolResult.length() > 4000) {
        return toolResult.substring(0, 4000) + "...[truncated]";
    }
    return toolResult;
}
```

### 5.3 Observe concurrent tool batches

```java
@Override
public void beforeConcurrentToolExecution(
        List<ToolExecutionRequest> toolRequests, AgentChatContext chatContext) {
    System.out.println("batch size = " + toolRequests.size());
}

@Override
public List<String> afterConcurrentToolExecution(
        List<ToolExecutionRequest> toolRequests,
        List<String> toolResults,
        AgentChatContext chatContext) {
    return toolResults;
}
```

The returned list must have the same length as `toolRequests`, otherwise result-to-call pairing breaks.

## 6. Built-in middleware

### LoggingIAgentMiddleware

Prints loop, model, and tool execution checkpoints, and aggregates model time, tool time, and token usage by `memoryId`. Useful for local debugging:

```java
.middleware(new LoggingIAgentMiddleware())
```

### AgentTraceMiddleware

Collects per-step latency, tool calls, and token usage for tracing canvases:

```java
.middleware(new AgentTraceMiddleware(new AgentTraceCollector()))
```

Tracing is best-effort: failures never interrupt Agent execution.

## 7. Failure isolation

`AgentMiddlewareManager` isolates exceptions for every callback:

- An exception in one middleware is logged but does not interrupt the main flow;
- `beforeModelCall` returning `null` intentionally aborts the model call;
- `afterModelCall` returning `null` falls back to the original response.

Keep middleware lightweight and idempotent. Do not block for long periods in callbacks.

## 8. Best practices

1. **Only cross-cutting logic**: do not implement core business branches in middleware;
2. **Design for registration order**: decide whether masking runs before or after logging and auditing;
3. **Store state in `extensions`**: use `AgentChatContext` for per-run state, not static variables;
4. **Keep streaming callbacks fast**: `onPartialResponse` may fire frequently; avoid synchronous I/O;
5. **Do not rethrow**: rely on manager isolation, but handle expected exceptions locally;
6. **Respect result contracts**: tool results are always `String`, and concurrent result lists must keep the same length.

Next: connect real capabilities to the Agent with [Tool Extensions](./tool).

---
title: Middleware 设计
sidebar_label: Middleware设计
description: 在 Agent Loop、模型调用、工具执行与停止阶段挂载可组合的横切逻辑
sidebar_position: 4
displayed_sidebar: sdkSidebar
---

# Middleware 设计

Middleware 用于把日志、链路追踪、审计、脱敏、限流、动态提示词等横切逻辑，从 Agent 主循环中抽离出来。它参考 LangChain 的 Agent Middleware 设计，通过一组默认方法在固定生命周期插入自定义行为。

## 依赖（v1.0.0）

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-agent-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

Middleware 契约、管理器与内置实现都在 `agentforge-agent-core` 中。

本文是 Middleware 总览，先建立生命周期、注册顺序和改写语义。具体内置实现按专题拆分，每篇都包含 SDK 接入示例、扩展方式和设计取舍：

| 专题 | 适用场景 |
| --- | --- |
| [日志 Middleware](./middleware/logging) | 本地调试、模型/工具耗时和 Token 汇总 |
| [Trace Middleware](./middleware/trace) | 输出可持久化、可绘制执行链路的快照 |
| [对话追加 Middleware](./middleware/append) | Agent Loop 运行中接收并注入新的用户消息 |

## 1. 两个接口

### 1.1 IAgentMiddleware

覆盖非流式生命周期：

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

在 `IAgentMiddleware` 基础上增加流式事件：

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

只关心非流式事件的中间件应重写 `handlesStreamingEvents()` 返回 `false`，避免无意义的流式分发。

## 2. 生命周期

```text
onInitComplete
    │
    ▼
┌─ beforeLoop(step)
│     │
│     ├─ beforeModelCall(step, request)
│     │       │
│     │       ├─ onAiCallRetry(...)         模型调用重试时
│     │       ├─ afterModelCall(step, response)
│     │       └─ onModelCallError(...)      模型调用失败时
│     │
│     ├─ beforeToolExecution(request)
│     │       ├─ afterToolExecution(request, result)   可改写工具结果
│     │       └─ onToolExecutionError(...)
│     │
│     └─ afterLoop(step, stepResult)
│
├─ onLoopError(step, error)                 单轮异常
│
└─ onStop(step, stopResult)                 正常结束 / 取消 / 最大步数
   onStopWithError(step, error)             异常结束
```

流式路径额外触发：

```text
onPartialThinking → onPartialResponse → onIntermediateResponse → onToolExecuted（TokenStream）
```

## 3. 注册中间件

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

或批量注册：

```java
.middlewares(Arrays.asList(
        new LoggingIAgentMiddleware(),
        new AuditMiddleware()))
```

**执行顺序**：按注册顺序执行。对于可改写结果的回调（`beforeModelCall`、`afterModelCall`、`afterToolExecution`），结果会沿中间件链依次传递。

## 4. 编写一个自定义中间件

下面实现一个审计中间件：记录模型调用耗时，并脱敏工具返回中的手机号。

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

`AgentChatContext` 提供 `putExtension` / `getExtension`，适合存放单次运行内的临时状态，不会污染会话消息。

## 5. 修改请求与响应

### 5.1 动态注入系统提示词

```java
@Override
public ChatRequest beforeModelCall(
        int currentStep, ChatRequest chatRequest, AgentChatContext chatContext) {
    // 返回新的 ChatRequest，注意保留原消息与参数
    return chatRequest;
}
```

返回 `null` 会中断本轮模型调用，Agent 会以「模型调用被中间件中断」收敛。

### 5.2 改写工具结果

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

### 5.3 观察并发工具批次

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

返回列表长度必须与 `toolRequests` 一致，否则会破坏结果与调用的配对关系。

## 6. 内置中间件

### LoggingIAgentMiddleware

打印 Loop、模型调用、工具执行的关键节点，并按 `memoryId` 统计模型耗时、工具耗时与 Token 消耗，适合本地调试：

```java
.middleware(new LoggingIAgentMiddleware())
```

### AgentTraceMiddleware

采集每一步的耗时、工具调用与 Token 消耗，供链路追踪画布使用：

```java
.middleware(new AgentTraceMiddleware(new AgentTraceCollector()))
```

Trace 采用 best-effort 策略：追踪失败不会中断 Agent 执行。

## 7. 失败隔离

`AgentMiddlewareManager` 对每个回调都做了异常隔离：

- 单个中间件抛异常，只记录日志，不中断主流程；
- `beforeModelCall` 返回 `null` 才会主动中断模型调用；
- `afterModelCall` 返回 `null` 时会回退到原始响应。

因此中间件应尽量保持幂等、轻量，不要在回调中做长时间阻塞操作。

## 8. 最佳实践

1. **只做横切逻辑**：不要用中间件实现核心业务分支；
2. **按注册顺序设计**：脱敏应放在日志之前，审计应放在脱敏之前或之后要明确；
3. **状态放 `extensions`**：单次运行状态放 `AgentChatContext`，不要写静态变量；
4. **流式回调要快**：`onPartialResponse` 可能高频触发，避免同步 IO；
5. **异常不外抛**：依赖管理器的隔离机制，但自身也要处理可预期异常；
6. **结果改写保持类型契约**：工具结果始终是 `String`，并发结果列表长度必须一致。

下一步：通过 [Tool 扩展设计](./tool) 把真实能力接入 Agent。

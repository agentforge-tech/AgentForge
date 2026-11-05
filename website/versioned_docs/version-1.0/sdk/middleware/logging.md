---
title: 日志 Middleware
sidebar_label: 日志 Middleware
description: 使用 LoggingIAgentMiddleware 观察 Agent Loop、模型调用和工具执行
sidebar_position: 1
displayed_sidebar: sdkSidebar
---

# 日志 Middleware

`LoggingIAgentMiddleware` 是最容易接入的内置 Middleware，适合本地调试和问题定位。它观察 Agent 生命周期，不改变模型请求、工具参数或工具结果。

## 1. SDK 用法

```java
ReActAgent agent = ReActAgent.builder()
        .agentName("support-agent")
        .chatModel(chatModel)
        .streamingChatModel(streamingChatModel)
        .chatMemoryProvider(new MessageWindowChatMemoryProvider(50))
        .toolService(toolService)
        .middleware(new LoggingIAgentMiddleware())
        .build();
```

如果使用多个 Middleware，日志通常放在请求改写之后，这样日志能够看到最终送给模型的请求；如果要记录原始请求，则把它注册在改写 Middleware 之前。

## 2. 它观察哪些阶段

| 阶段 | 记录内容 |
| --- | --- |
| `onInitComplete` | `memoryId`、用户问题、会话开始 |
| `beforeModelCall` / `afterModelCall` | 步骤、消息数量、模型耗时、Token、finish reason |
| `onAiCallRetry` | 重试次数、等待时间和异常类型 |
| `beforeToolExecution` / `afterToolExecution` | 工具名、参数、结果和耗时 |
| `onToolExecutionError` | 工具异常和会话标识 |
| `onStop` / `onStopWithError` | 结束状态和会话汇总 |

日志状态按 `memoryId` 隔离，适合一个 Agent 实例被多个会话复用的场景。

## 3. 设计思路

日志 Middleware 只实现“观察”，所有有返回值的回调都原样返回：

```java
@Override
public ChatRequest beforeModelCall(
        int currentStep,
        ChatRequest request,
        AgentChatContext context) {
    recordStart(context, currentStep);
    return request;
}

@Override
public String afterToolExecution(
        ToolExecutionRequest request,
        String result,
        AgentChatContext context) {
    recordToolCost(context, request, result);
    return result;
}
```

这样可以随时注册或移除日志，不会改变 Agent 的执行语义。会话统计保存在并发 Map 中，临时开始时间和累计耗时按 `memoryId` 及步骤隔离。

## 4. 如何扩展

### 脱敏

日志属于敏感数据边界，建议在输出前统一截断和脱敏：

```java
private String safe(String text) {
    if (text == null) return null;
    return text.replaceAll("1[3-9]\\d{9}", "***********");
}
```

不要把完整 Authorization、Cookie、用户隐私或工具密钥写入日志。

### 接入结构化日志

可以继承或重新实现观察逻辑，把 `memoryId`、`currentStep`、`toolName`、`durationMs` 组装为 JSON，再交给应用的日志系统或指标系统。不要在回调中同步写远程系统；高频工具和流式事件应使用异步队列。

### 并发工具

如果业务必须统计并发工具批次，需要额外实现：

```java
beforeConcurrentToolExecution(...)
onConcurrentToolExecutionComplete(...)
afterConcurrentToolExecution(...)
```

单工具的 `beforeToolExecution/afterToolExecution` 不覆盖并发批次，不能只依赖单工具回调计算完整耗时。

## 5. 边界与建议

- 日志内容应设置最大长度，避免工具返回撑爆日志；
- 按 `memoryId` 清理会话统计，避免长时间运行的服务内存增长；
- 事件失败不能影响 Agent 主流程；
- 日志 Middleware 只用于观测，权限、审批和结果改写应放到专门 Middleware。

下一步：阅读 [Trace Middleware](./trace)。

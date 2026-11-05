---
title: Trace Middleware
sidebar_label: Trace Middleware
description: 采集 Agent Loop 步骤、模型耗时、工具调用和 Token 的可持久化快照
sidebar_position: 2
displayed_sidebar: sdkSidebar
---

# Trace Middleware

`AgentTraceMiddleware` 把一次 ReAct 执行整理为“会话 → 步骤 → 模型调用/工具调用”的快照，并通过 `AgentTraceCollector` 输出。Core 只负责采集和发布观察数据，不绑定数据库、SSE 或具体 Trace UI。

```text
AgentTraceMiddleware
  -> AgentTraceSnapshot
  -> AgentTraceCollector
       ├── 内存调试
       ├── MySQL 持久化
       ├── SSE 推送
       └── 火焰图/流程图查询
```

## 1. SDK 用法

`AgentTraceCollector` 只有一个方法，可以先用 Lambda 观察快照：

```java
AgentTraceCollector collector = snapshot -> {
    System.out.println(
            "step=" + snapshot.getStep()
                    + ", kind=" + snapshot.getSnapshotKind()
                    + ", status=" + snapshot.getStatus()
                    + ", modelMs=" + snapshot.getModelDurationMs()
                    + ", toolMs=" + snapshot.getToolDurationMs());
};

ReActAgent agent = ReActAgent.builder()
        .agentName("trace-agent")
        .chatModel(chatModel)
        .streamingChatModel(streamingChatModel)
        .chatMemoryProvider(new MessageWindowChatMemoryProvider(50))
        .toolService(toolService)
        .middleware(new AgentTraceMiddleware(collector))
        .build();
```

在 Service 中，通常把 Collector 注入数据库或事件总线：

```java
public final class MysqlTraceCollector implements AgentTraceCollector {
    @Override
    public void onSnapshot(AgentTraceSnapshot snapshot) {
        traceRepository.save(snapshot);
    }
}
```

## 2. 快照内容

快照通常包含：

- `memoryId`、用户问题和步骤号；
- 步骤开始/结束时间和总耗时；
- 模型开始/结束时间、输入/输出/总 Token；
- 工具调用 ID、工具名、参数、结果、状态和耗时；
- `CONTINUE`、`NORMAL`、`CANCEL`、`MAX_STEPS`、`ERROR` 等状态；
- 业务扩展属性。

中间步骤快照可以驱动实时流程图，最终 `ROUND_FINAL` 快照可以作为一次会话的汇总记录。

## 3. 设计思路

Trace 是“观察型 Middleware”，不修改 `ChatRequest`、`ChatResponse` 或工具结果。它将运行态放在 `AgentChatContext.extensions` 中：

```text
onInitComplete
  -> 创建 Runtime
beforeLoop(step)
  -> 创建 Step
beforeModelCall / afterModelCall
  -> 记录模型计时和 Token
beforeTool / afterTool
  -> 记录工具计时和结果
afterLoop / onLoopError
  -> 发布步骤快照
onStop / onStopWithError
  -> 发布最终汇总并清理 Runtime
```

这种设计避免使用静态变量，也避免不同 `memoryId` 的运行状态互相覆盖。

## 4. 如何扩展

### 扩展业务字段

业务层可以继承 Middleware，或者在 `AgentChatContext` 中放入 `aid`、debug 标记、租户等运行属性，再由 Trace 扩展快照：

```java
public final class TenantTraceMiddleware extends AgentTraceMiddleware {
    public TenantTraceMiddleware(AgentTraceCollector collector) {
        super(collector);
    }

    @Override
    protected void enrichAttributes(
            AgentChatContext context,
            Map<String, Object> attributes) {
        attributes.put("tenantId", context.getExtension("tenantId"));
    }
}
```

### 异步落库

`AgentTraceCollector` 是同步回调接口。高并发场景可以在 Collector 内部写入有界队列，由后台线程批量落库：

```text
Middleware -> AsyncTraceCollector -> bounded queue -> batch writer
```

队列满时需要明确丢弃、降级或阻塞策略，不能让观测系统拖住模型调用线程。

### 幂等与重放

持久化 Collector 应使用 `memoryId + questionRound + step + snapshotKind` 等稳定键做幂等，页面重放时按步骤和状态排序，不要把 Trace 快照当作新的聊天消息写回 Memory。

## 5. 最佳实践

- Trace 失败必须 best-effort，不应中断 Agent；
- 参数和结果需要截断、脱敏；
- 不要在 Trace 中保存不可序列化的模型对象；
- 区分步骤快照和最终汇总，避免重复统计 Token；
- 流式 Token 不建议逐 token 落库，优先在模型完成或步骤完成时汇总。

下一步：阅读 [对话追加 Middleware](./append)。

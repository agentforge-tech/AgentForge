---
title: 任务计划 Todo Middleware
sidebar_label: 任务计划 Todo Middleware
description: 用 TodoPlanTool 与 TodoPlanMiddleware 让 Agent 在多步骤任务中维护可展示的任务清单
sidebar_position: 5
displayed_sidebar: sdkSidebar
---

# 任务计划 Todo Middleware

多步骤任务里，用户最关心的往往不是最终答案，而是“现在做到哪一步了”。`TodoPlanTool` 提供一个模型可调用的 `todoplan` 工具，`TodoPlanMiddleware` 在工具执行完成后读取最新计划并交给监听器，让你可以在业务侧做日志、审计、指标或自定义展示。

它不新增事件协议。任务列表直接来自 `todoplan` 工具调用，现有的 `tool_call` 事件和工具调用回调都能拿到完整入参。

## 1. 它解决什么问题

```text
用户：帮我排查这个线上问题
  │
  ├─ 1. 收集日志          已完成
  ├─ 2. 定位异常链路      进行中
  └─ 3. 给出修复建议      待处理
```

如果没有任务计划，模型只能输出一段自然语言，用户无法中途感知进度。引入 `todoplan` 后：

- 模型用结构化数据描述任务、状态和优先级；
- 每一次调用都是一份完整计划快照；
- 中间件把计划交给业务监听器；
- 前端可以直接用工具入参渲染任务列表。

## 2. 两个组件

| 组件 | 作用 |
| --- | --- |
| `TodoPlanTool` | 定义 `todoplan` 工具、全量计划校验、系统提示词 |
| `TodoPlanMiddleware` | 工具成功后识别 `todoplan`，把计划交给 `TodoPlanListener` |

中间件只观察，不改写工具结果，也不发送 SSE。因此可以随时移除，不影响 Agent 主流程。

## 3. 快速接入

### 3.1 注册工具与中间件

```java
import cloud.changlu.agentforge.agent.ReActAgent;
import cloud.changlu.agentforge.agent.extend.middlewares.todoplan.middleware.TodoPlanMiddleware;
import cloud.changlu.agentforge.agent.extend.middlewares.todoplan.model.TodoItem;
import cloud.changlu.agentforge.agent.extend.middlewares.todoplan.tool.TodoPlanTool;
import cloud.changlu.agentforge.agent.tool.local.LocalToolFactory;
import cloud.changlu.agentforge.model.tool.execution.ToolService;
import cloud.changlu.agentforge.model.tool.spec.ToolSpecification;
import cloud.changlu.agentforge.model.tool.ToolExecutor;

import java.util.Collections;
import java.util.Map;

Map<ToolSpecification, ToolExecutor> todoTools =
        LocalToolFactory.buildLocalToolsWithThought(
                Collections.<Object>singletonList(new TodoPlanTool()), true);

ToolService toolService = new ToolService();
toolService.tools(todoTools);

TodoPlanMiddleware todoMiddleware = new TodoPlanMiddleware((todos, context) -> {
    System.out.println("session=" + context.getMemoryId());
    for (TodoItem item : todos) {
        System.out.println("#" + item.getSeq() + " [" + item.getStatus() + "] " + item.getContent());
    }
});

String systemPrompt = TodoPlanTool.appendSystemPrompt(
        "你是一个严谨的排障助手，复杂任务需要先制定计划。");

ReActAgent agent = ReActAgent.builder()
        .agentName("todo-agent")
        .systemPrompt(systemPrompt)
        .chatModel(chatModel)
        .streamingChatModel(streamingChatModel)
        .chatMemoryProvider(new MessageWindowChatMemoryProvider(50))
        .toolService(toolService)
        .middleware(todoMiddleware)
        .build();
```

`buildLocalToolsWithThought(..., true)` 会在工具 Schema 中增加 `__thought` 参数，用于让模型先说明调用原因；不需要时可以改用 `LocalToolFactory.buildLocalTools(...)`。

### 3.2 不关心计划时的空监听器

框架层的 `TodoPlanMiddleware` 必须显式传入 `TodoPlanListener`。如果暂时只关心工具本身，可以使用空实现：

```java
import cloud.changlu.agentforge.agent.extend.middlewares.todoplan.listener.TodoPlanListener;

TodoPlanMiddleware middleware =
        new TodoPlanMiddleware(TodoPlanListener.noop());
```

如果你希望在服务端看到计划变化，可以传入自己的监听器；AgentForge Service 已经提供 `ServiceTodoPlanMiddleware` 与 `ServiceTodoPlanListener`，默认打印结构化的计划日志：

```text
Todo plan updated: sessionId=chat-xxx, total=3
  #1 [completed] 收集日志 (high)
  #2 [in_progress] 定位异常链路 (high)
  #3 [pending] 给出修复建议 (medium)
```

## 4. 计划数据结构

`todoplan` 的入参是一份完整计划：

```json
{
  "todos": [
    { "seq": 1, "content": "收集日志", "status": 3, "priority": 1 },
    { "seq": 2, "content": "定位异常链路", "status": 2, "priority": 1 },
    { "seq": 3, "content": "给出修复建议", "status": 1, "priority": 2 }
  ]
}
```

字段说明：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `seq` | 是 | 任务序号，从 1 开始，同一份计划内唯一 |
| `content` | 是 | 简短、可执行的任务描述，最长 300 字符 |
| `status` | 是 | `1` 待处理、`2` 进行中、`3` 已完成、`4` 已取消 |
| `priority` | 是 | `1` 高、`2` 中、`3` 低 |

校验规则由 `TodoValidator` 统一执行：

- 最多 30 项；
- `seq` 必须为正整数且唯一；
- `content` 不能为空，去空格后不超过 300 字；
- 同一时间最多一项 `in_progress`；
- 返回结果按 `seq` 升序排列。

校验失败时工具会返回错误，模型会在下一轮修正计划。中间件不会收到失败的计划，因此用户不会看到半成品。

## 5. 全量替换语义

每次调用 `todoplan` 都是一次**全量替换**：

- 需要保留的任务必须继续出现在 `todos` 中；
- 被省略的任务会被移除；
- 传入空数组 `[]` 会清空计划。

这样模型只需要维护一个数组，不需要 `add` / `update` / `delete` 多套工具，历史回放时每个 `todoplan` 事件也能独立还原当时的计划。

## 6. 监听器能做什么

`TodoPlanListener` 是一个函数式接口：

```java
@FunctionalInterface
public interface TodoPlanListener {
    void onTodoPlan(List<TodoItem> todos, AgentChatContext context);
}
```

常见扩展：

| 场景 | 做法 |
| --- | --- |
| 审计 | 把每次计划写入审计表，记录 `memoryId` 与调用时间 |
| 指标 | 统计任务数、完成率、平均推进步数 |
| 消息通知 | 计划完成时发送 MQ、Webhook 或站内信 |
| 自定义展示 | 把计划推送到自己的 WebSocket / SSE 通道 |
| 跨轮恢复 | 写入业务存储，下一次会话启动时恢复 |

监听器异常不会影响 Agent 主流程。并发工具批次中，中间件只通知批次里最后一个 `todoplan`，避免同一批计划重复上报。

## 7. 与事件协议对接

任务计划不需要专用事件。`todoplan` 的调用会正常产生 `tool_call` 事件，其中包含：

| 字段 | 用途 |
| --- | --- |
| `toolName` | 固定为 `todoplan` |
| `inputParams` | 完整入参，前端可解析 `todos` |
| `isFinish` | 区分执行中与执行完成 |
| `events` 持久化 | 历史会话可回放 |

流式执行时，可以直接在工具完成回调里读取计划：

```java
agent.runStream(request)
        .onToolExecuted(execution -> {
            if (TodoPlanTool.TOOL_NAME.equals(execution.request().name())) {
                List<TodoItem> todos = TodoPlanTool.parsePlan(execution.request().arguments());
                System.out.println("latest plan size=" + todos.size());
            }
        })
        .start();
```

前端或业务 UI 根据 `tool_call.inputParams.todos` 渲染任务列表即可，不需要新增 `todo` 事件。

## 8. 能力开关建议

如果任务计划是可选能力，建议同时控制三处：

1. 是否注册 `todoplan` 工具；
2. 是否注册 `TodoPlanMiddleware`；
3. 是否追加 `TodoPlanTool.appendSystemPrompt(...)`。

三者必须一致，否则会出现“模型看到提示词但没有工具”或“工具可用但业务侧收不到计划”的不一致状态。

## 9. 边界与建议

- 中间件不持久化跨轮状态；需要在多轮之间保留计划时，由监听器写入业务存储；
- 不强制模型必须制定计划，是否调用由系统提示词引导；
- 计划内容应保持简短，避免把大段日志或代码塞进 `content`；
- 监听器要轻量并注意线程安全，并发工具批次可能在工具线程池中回调；
- 任务计划是“过程可视化”，不要用它替代最终回答。

下一步：了解如何让模型直接阅读用户提供的图片和网页链接：[阅读资源 Middleware](./read)。

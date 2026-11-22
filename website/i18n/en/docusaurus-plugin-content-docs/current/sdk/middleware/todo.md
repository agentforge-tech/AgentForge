---
title: Todo Plan Middleware
sidebar_label: Todo Plan Middleware
description: Use TodoPlanTool and TodoPlanMiddleware to maintain a visible task list for multi-step Agent runs
sidebar_position: 5
displayed_sidebar: sdkSidebar
---

# Todo Plan Middleware

For multi-step tasks, users usually care less about the final answer and more about "where are we now?". `TodoPlanTool` exposes a model-callable `todoplan` tool, and `TodoPlanMiddleware` reads the latest plan after the tool succeeds and hands it to a listener. You can use that listener for logging, auditing, metrics, or your own UI.

It does not introduce a new event protocol. The task list comes directly from the `todoplan` tool call, so existing `tool_call` events and tool execution callbacks already carry everything you need.

## 1. What it solves

```text
User: help me troubleshoot this production issue
  │
  ├─ 1. Collect logs              completed
  ├─ 2. Locate the failing path   in progress
  └─ 3. Suggest a fix             pending
```

Without a task plan, the model can only produce prose, and users cannot see progress. With `todoplan`:

- the model describes tasks, statuses, and priorities as structured data;
- every call is a full plan snapshot;
- the middleware forwards the plan to your listener;
- the UI can render a task list directly from the tool arguments.

## 2. Two components

| Component | Responsibility |
| --- | --- |
| `TodoPlanTool` | Defines the `todoplan` tool, validates the full plan, and provides the system prompt |
| `TodoPlanMiddleware` | Detects `todoplan` after a successful tool call and forwards the plan to `TodoPlanListener` |

The middleware only observes. It does not rewrite tool results or send SSE events, so you can remove it at any time without affecting the Agent flow.

## 3. Quick start

### 3.1 Register the tool and middleware

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
        "You are a careful troubleshooting assistant. Create a plan for complex tasks first.");

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

`buildLocalToolsWithThought(..., true)` adds a `__thought` parameter to the tool schema so the model can explain why it is calling the tool. Use `LocalToolFactory.buildLocalTools(...)` if you do not need that behavior.

### 3.2 Use a no-op listener when the plan is not needed

The framework-level `TodoPlanMiddleware` requires an explicit `TodoPlanListener`. If you only care about the tool itself, use the no-op implementation:

```java
import cloud.changlu.agentforge.agent.extend.middlewares.todoplan.listener.TodoPlanListener;

TodoPlanMiddleware middleware =
        new TodoPlanMiddleware(TodoPlanListener.noop());
```

If you want server-side visibility, pass your own listener. AgentForge Service already provides `ServiceTodoPlanMiddleware` and `ServiceTodoPlanListener`, which log structured plan changes by default:

```text
Todo plan updated: sessionId=chat-xxx, total=3
  #1 [completed] Collect logs (high)
  #2 [in_progress] Locate the failing path (high)
  #3 [pending] Suggest a fix (medium)
```

## 4. Plan data structure

The `todoplan` argument is a complete plan:

```json
{
  "todos": [
    { "seq": 1, "content": "Collect logs", "status": 3, "priority": 1 },
    { "seq": 2, "content": "Locate the failing path", "status": 2, "priority": 1 },
    { "seq": 3, "content": "Suggest a fix", "status": 1, "priority": 2 }
  ]
}
```

Fields:

| Field | Required | Description |
| --- | --- | --- |
| `seq` | Yes | Task order, starting from 1, unique within the plan |
| `content` | Yes | Short, actionable task description, up to 300 characters |
| `status` | Yes | `1` pending, `2` in progress, `3` completed, `4` cancelled |
| `priority` | Yes | `1` high, `2` medium, `3` low |

`TodoValidator` enforces the rules:

- at most 30 items;
- `seq` must be a positive integer and unique;
- `content` must not be blank and must be at most 300 characters after trimming;
- at most one item may be `in_progress`;
- the result is sorted by `seq` ascending.

If validation fails, the tool returns an error and the model can correct the plan on the next turn. The middleware is not notified, so users never see a half-built plan.

## 5. Full replacement semantics

Every `todoplan` call is a **full replacement**:

- items that should remain must be included again;
- omitted items are removed;
- an empty array `[]` clears the plan.

This keeps the tool surface small: the model only maintains one array instead of `add` / `update` / `delete` tools. It also makes history replay simple, because every `todoplan` event reconstructs the plan at that moment.

## 6. What the listener can do

`TodoPlanListener` is a functional interface:

```java
@FunctionalInterface
public interface TodoPlanListener {
    void onTodoPlan(List<TodoItem> todos, AgentChatContext context);
}
```

Common extensions:

| Scenario | Approach |
| --- | --- |
| Audit | Persist every plan with `memoryId` and timestamp |
| Metrics | Track item count, completion rate, and average steps |
| Notifications | Send MQ, webhook, or in-app messages when the plan completes |
| Custom UI | Push the plan to your own WebSocket / SSE channel |
| Cross-turn recovery | Store the plan and restore it on the next session start |

Listener exceptions never affect the Agent flow. In a concurrent tool batch, the middleware notifies only the last `todoplan` call in that batch, preventing duplicate plan reports.

## 7. Integration with the event protocol

No dedicated event is needed. A `todoplan` call produces a normal `tool_call` event containing:

| Field | Purpose |
| --- | --- |
| `toolName` | Always `todoplan` |
| `inputParams` | Full arguments; the UI can parse `todos` |
| `isFinish` | Distinguishes in-progress from completed |
| Persisted `events` | Enables history replay |

In streaming mode, read the plan from the tool completion callback:

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

The UI can render a task list from `tool_call.inputParams.todos`; no `todo` event is required.

## 8. Capability switch advice

If task planning is optional, control all three parts together:

1. whether to register the `todoplan` tool;
2. whether to register `TodoPlanMiddleware`;
3. whether to append `TodoPlanTool.appendSystemPrompt(...)`.

Keep them consistent. Otherwise the model may see a prompt without a tool, or the tool may work while your business side receives no plan.

## 9. Boundaries and recommendations

- The middleware does not persist cross-turn state; use the listener if you need that;
- The model is not forced to create a plan; the system prompt guides it;
- Keep `content` short; do not put large logs or code into it;
- Keep the listener lightweight and thread-safe, because concurrent tool batches may call it from tool threads;
- Task planning is process visibility, not a replacement for the final answer.

Next: let the model read user-provided images and web links on demand with [Resource Reading Middleware](./read).

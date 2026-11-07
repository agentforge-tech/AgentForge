# Agent 支持任务计划 Todo 列表展示

> 文档定位：在 AgentForge 中用最小改动接入 Todo 计划能力。不新增专用事件协议，只新增 `todoplan` 工具和 Middleware 监控扩展点；前端直接根据 `tool_call` 事件中的 `todoplan` 工具名完成渲染。
>
> 源码参考：`dt-ai-agent/src/main/java/com/dtstack/ai/agent/react/tools/todo/`

---

## 一、背景与目标

### 1.1 背景

Agent 执行多步骤任务时，用户通常只能看到最终回答，无法中途感知：

- 任务一共拆成了几步；
- 每一步是待处理、进行中还是已完成；
- 当前正在执行哪一步；
- 计划是否被调整过。

`dt-ai-agent` 中已有 Todo 能力，但它包含 `TodoStore`、`TodoSnapshot` 和独立事件推送。当前 AgentForge 只需要：

1. 一个模型可调用的 `todoplan` 工具；
2. 一个识别该工具的 Middleware，并留出监控回调；
3. 前端根据现有 `tool_call` 事件中的工具名和入参渲染任务列表。

因此本方案**不新增 Todo 事件协议**，也不迁移 Store。

### 1.2 设计目标

1. 用户能在执行过程中看到任务列表、状态、序号和完成进度；
2. 方案不引入新的 SSE 事件类型，复用现有 `tool_call`；
3. 调试模式保持普通工具卡片展示；
4. 发布模式把 `todoplan` 工具卡片替换为任务计划列表，每次调用展示一次；
5. 历史会话通过已有 `events` 回放还原同样的展示；
6. Todo 监控能力通过 `TodoPlanListener` 留出扩展口子。

### 1.3 非目标

- TodoStore、revision、跨轮状态合并；
- 独立 `todo` SSE 事件；
- 用户手工勾选、拖动排序、编辑 Todo；
- 前端修改反向同步给模型。

---

## 二、整体方案

### 2.1 源模块取舍

| 源类 | 本方案 |
| --- | --- |
| `TodoItem` / `TodoStatus` / `TodoPriority` | 迁移，并增加 `seq` 序号 |
| `TodoValidator` | 迁移，增加序号非空、正整数、唯一性和排序校验 |
| `TodoWriteTool` | 改名为 `TodoPlanTool`，工具名 `todoplan` |
| `TodoMiddleware` | 改为 `TodoPlanMiddleware`，只识别工具并通知监听器 |
| `TodoStore` / `TodoSnapshot` | 不迁移 |
| `TodoUpdateCollector` | 改为 `TodoPlanListener` 监控扩展点 |

### 2.2 为什么不需要专用事件

`todoplan` 的入参本身就是完整计划，并且现有 `tool_call` 事件已经携带：

- `toolName`：可以识别 `todoplan`；
- `inputParams`：完整工具入参；
- `isFinish`：工具执行结束；
- `events` 持久化：历史回放可用。

所以前端不需要额外的 `todo` 事件，只要在 `tool_call` 完成时解析 `inputParams.todos` 即可。

### 2.3 整体链路

```text
模型返回 ToolExecutionRequest(name=todoplan, arguments={todos:[...]})
  ↓
LocalToolExecutor -> TodoPlanTool.plan(...)
  ├─ 解析 arguments
  ├─ TodoValidator 校验并排序
  └─ 返回状态统计
  ↓
现有 tool_call 事件携带 inputParams
  ↓
TodoPlanMiddleware.afterToolExecution(...)
  └─ 解析同一份入参 → TodoPlanListener（监控扩展点）
  ↓
前端根据 tool_call / toolName=todoplan 解析 inputParams
  ├─ 调试模式：正常工具卡片
  └─ 发布模式：替换为 TodoBoard 任务计划列表
```

### 2.4 与 AgentForge 的适配点

| 差异点 | 处理方式 |
| --- | --- |
| 不需要 memoryId | 使用普通 `@Tool` 方法即可 |
| `List<TodoItem>` 需要嵌套 Schema | 扩展 `ToolSpecifications` 自动生成 POJO `items` |
| JSON 数组需要绑定到 POJO | 扩展 `LocalToolArgumentConverter` 支持 `List<Map>` → `List<TodoItem>` |
| 工具注册 | 使用 `LocalToolFactory.buildLocalToolsWithThought(...)` |
| 没有 Todo 专用事件 | 复用 `tool_call` 事件 |
| 需要监控 Todo 变化 | 新增 `TodoPlanListener` 回调 |

---

## 三、Todo 工具与提示词

### 3.1 包结构

```text
agentforge-framework/agentforge-agent-core/src/main/java/
└── cloud/changlu/agentforge/agent/extend/middlewares/todoplan/
    ├── model/
    │   ├── TodoItem.java
    │   ├── TodoPriority.java
    │   └── TodoStatus.java
    ├── validation/
    │   └── TodoValidator.java
    ├── tool/
    │   └── TodoPlanTool.java
    ├── listener/
    │   └── TodoPlanListener.java
    └── middleware/
        └── TodoPlanMiddleware.java

agentforge-service-web/src/main/java/
└── cloud/changlu/agentforge/service/modules/agent/middleware/todoplan/
    └── ServiceTodoPlanListener.java
```

### 3.2 模型与校验

`TodoItem` 直接使用数字表示状态和优先级，枚举负责维护数字与文案映射：

```java
public enum TodoStatus {
    PENDING(1, "pending"),
    IN_PROGRESS(2, "in_progress"),
    COMPLETED(3, "completed"),
    CANCELLED(4, "cancelled");
}

public enum TodoPriority {
    HIGH(1, "high"),
    MEDIUM(2, "medium"),
    LOW(3, "low");
}
```

```java
public class TodoItem {

    @P("任务序号，从 1 开始，在同一份计划内唯一")
    private Integer seq;

    @P("任务内容，简短、可执行，最长 300 字符")
    private String content;

    @P("状态：1=待处理，2=进行中，3=已完成，4=已取消")
    private Integer status;

    @P("优先级：1=高，2=中，3=低")
    private Integer priority;
}
```

`@P` 已扩展支持字段级别，`ToolSpecifications` 会把字段上的描述写入嵌套 Schema，因此 `TodoItem` 不需要额外注解。

`TodoValidator` 负责：

- 最多 30 项；
- `seq` 必须为正整数且唯一；
- `content` 非空、去首尾空格后不超过 300 字；
- `status` 必须是 1/2/3/4；
- `priority` 必须是 1/2/3；
- 最多一个 `in_progress`；
- 按 `seq` 升序排序后返回。

### 3.3 TodoPlanTool（Local Tool）

工具名 `todoplan`，入参结构：

```json
{
  "todos": [
    {
      "seq": 1,
      "content": "梳理需求与边界",
      "status": 3,
      "priority": 1
    },
    {
      "seq": 2,
      "content": "实现 TodoPlanTool",
      "status": 2,
      "priority": 1
    }
  ]
}
```

因为不需要 memoryId，可以直接使用 AgentForge 的本地工具形式：

```java
public final class TodoPlanTool {

    public static final String TOOL_NAME = "todoplan";

    public static final String SYSTEM_PROMPT =
            "当任务需要多个步骤、需要调用多个工具，或预计无法在一次回复内完成时，先调用 todoplan 制定计划。\n"
                    + "维护计划时遵守：\n"
                    + "- 每项 seq 使用从 1 开始的递增序号，保证展示顺序；\n"
                    + "- 每项 content 保持简短、可执行；\n"
                    + "- 同一时间最多一项 in_progress；\n"
                    + "- 每次推进任务后立即调用 todoplan 全量更新状态；\n"
                    + "- 已完成、已取消或失败的任务也要保留在列表中并更新状态；\n"
                    + "- 简单问答、单步查询不要调用 todoplan。";

    @Tool(name = TOOL_NAME, value = DESCRIPTION)
    public String plan(
            @P(value = "Complete replacement list of todo objects") List<TodoItem> todos) {
        List<TodoItem> normalized = TodoValidator.normalize(todos);
        return "Plan updated: " + count(normalized, TodoStatus.COMPLETED.getCode())
                + " completed, " + count(normalized, TodoStatus.IN_PROGRESS.getCode())
                + " in progress, " + count(normalized, TodoStatus.PENDING.getCode())
                + " pending.";
    }

    public static String appendSystemPrompt(String basePrompt) {
        if (basePrompt == null || basePrompt.trim().isEmpty()) {
            return SYSTEM_PROMPT;
        }
        return basePrompt + "\n\n" + SYSTEM_PROMPT;
    }
}
```

`LocalToolFactory` 会根据方法签名生成工具，`ToolSpecifications` 自动生成：

```json
{
  "type": "array",
  "items": {
    "type": "object",
    "properties": {
      "seq": { "type": "integer", "description": "任务序号..." },
      "content": { "type": "string", "description": "任务内容..." },
      "status": { "type": "integer", "description": "状态：1=待处理..." },
      "priority": { "type": "integer", "description": "优先级：1=高..." }
    },
    "required": ["seq", "content", "status", "priority"]
  }
}
```

### 3.4 系统提示词接入

在 `ReActAgentFactory` 中，根据 `StandardAgentConfig.isPlanEnabled()` 决定是否追加：

```java
String systemPrompt = standardAgentConfig.getSystemPrompt();
if (todoEnabled) {
    systemPrompt = TodoPlanTool.appendSystemPrompt(systemPrompt);
}
```

---

## 四、Middleware 与监控扩展点

### 4.1 TodoPlanListener

Middleware 不推送 SSE，只留出一个监控回调：

```java
@FunctionalInterface
public interface TodoPlanListener {

    void onTodoPlan(List<TodoItem> todos, AgentChatContext context);

    static TodoPlanListener noop() {
        return (todos, context) -> {};
    }
}
```

Service 侧默认实现 `ServiceTodoPlanListener`，直接打印 Todo 计划日志：

```java
public final class ServiceTodoPlanListener implements TodoPlanListener {

    @Override
    public void onTodoPlan(List<TodoItem> todos, AgentChatContext context) {
        log.info("Todo plan updated: sessionId={}, total={}", sessionId, todos.size());
        for (TodoItem item : todos) {
            log.info("  #{} [{}] {} ({})",
                    item.getSeq(), status.getValue(), item.getContent(), priority.getValue());
        }
    }
}
```

输出示例：

```text
Todo plan updated: sessionId=xxx, total=3
  #1 [completed] 梳理需求与边界 (high)
  #2 [in_progress] 实现 TodoPlanTool (high)
  #3 [pending] 接入前端任务计划 (medium)
```

后续也可以替换为审计日志、计划完成率统计、消息队列或自定义业务快照。

### 4.2 TodoPlanMiddleware

```java
public final class TodoPlanMiddleware implements IAgentMiddleware {

    private final TodoPlanListener listener;

    @Override
    public String afterToolExecution(
            ToolExecutionRequest request, String result, AgentChatContext context) {
        if (isTodoPlan(request)) {
            notifyListener(request, context);
        }
        return result;
    }

    @Override
    public List<String> afterConcurrentToolExecution(
            List<ToolExecutionRequest> requests,
            List<String> results,
            AgentChatContext context) {
        if (requests == null || requests.isEmpty()) {
            return results;
        }
        for (int i = requests.size() - 1; i >= 0; i--) {
            if (isTodoPlan(requests.get(i))) {
                notifyListener(requests.get(i), context);
                break;
            }
        }
        return results;
    }

    private boolean isTodoPlan(ToolExecutionRequest request) {
        return request != null && TodoPlanTool.TOOL_NAME.equals(request.name());
    }

    private void notifyListener(ToolExecutionRequest request, AgentChatContext context) {
        try {
            listener.onTodoPlan(TodoPlanTool.parsePlan(request.arguments()), context);
        } catch (Exception e) {
            log.warn("处理 Todo 计划失败，忽略旁路异常", e);
        }
    }
}
```

关键点：

- 不依赖 Store；
- 不发送专用 SSE；
- 工具成功执行后解析同一份入参；
- 监听器异常不影响 Agent 主流程；
- 并发批次只通知一次。

### 4.3 装配

`service/modules/agent/config/TodoPlanConfiguration` 注册：

```java
@Bean
@ConditionalOnMissingBean(TodoPlanListener.class)
public TodoPlanListener todoPlanListener() {
    return new ServiceTodoPlanListener();
}

@Bean
public TodoPlanMiddleware todoPlanMiddleware(TodoPlanListener todoPlanListener) {
    return new TodoPlanMiddleware(todoPlanListener);
}

@Bean
public TodoPlanTool todoPlanTool() {
    return new TodoPlanTool();
}
```

`ReActAgentFactory` 中：

```java
boolean todoEnabled = standardAgentConfig.isPlanEnabled();

if (todoEnabled) {
    middlewares.add(todoPlanMiddleware);
}

if (todoEnabled) {
    toolService.tool(todoPlanTool.specification(), todoPlanTool);
    systemPrompt = TodoPlanTool.appendSystemPrompt(systemPrompt);
}
```

目前没有新增事件枚举、`TodoResult` 或 `AiTodoProcessor`。`tool_call` 事件已经包含 Todo 渲染所需的全部数据。

### 4.4 调试与发布差异

| 维度 | 调试模式 | 发布模式 |
| --- | --- | --- |
| 工具展示 | 正常 `ToolCard` | 不展示原始工具卡片 |
| 任务计划 | 不额外渲染 TodoBoard | 每个 `todoplan` 调用渲染一个 TodoBoard |
| 数据来源 | `tool_call.inputParams` | `tool_call.inputParams` |
| 历史回放 | 还原普通工具卡片 | 还原 TodoBoard |

---

## 五、前端交互

### 5.1 识别 todoplan 工具

新增 `src/utils/todoEventReducer.js`：

```js
export const TODO_PLAN_TOOL_NAME = 'todoplan'

const STATUS_KEYS = { 1: 'pending', 2: 'in_progress', 3: 'completed', 4: 'cancelled' }
const PRIORITY_KEYS = { 1: 'high', 2: 'medium', 3: 'low' }

export function buildTodoCardPart(toolPart) {
  if (!toolPart || toolPart.toolName !== TODO_PLAN_TOOL_NAME) return null
  const params = parseInputParams(toolPart.inputParams)
  if (!params || !Array.isArray(params.todos)) return null

  const todos = params.todos
    .filter((item) => item && item.content)
    .map((item) => ({
      ...item,
      statusKey: STATUS_KEYS[Number(item.status)] || 'pending',
      priorityKey: PRIORITY_KEYS[Number(item.priority)] || 'low'
    }))
    .sort((left, right) => Number(left.seq || 0) - Number(right.seq || 0))

  return {
    type: 'todo_card',
    visible: todos.length > 0,
    toolCallId: toolPart.toolCallId,
    todos,
    summary: {
      total: todos.length,
      pending: count(todos, 'pending'),
      inProgress: count(todos, 'in_progress'),
      completed: count(todos, 'completed'),
      cancelled: count(todos, 'cancelled')
    }
  }
}
```

### 5.2 实时渲染规则

前端在 `applyTool(...)` 中处理：

```js
part.isFinish = !!event.isFinish
if (part.isFinish) {
  part.finishedAt = Date.now()
  if (!detailed) {
    const todoPart = buildTodoCardPart(part)
    if (todoPart) {
      const index = answer.parts.indexOf(part)
      if (index >= 0) answer.parts.splice(index, 1, todoPart)
    }
  }
}
```

规则：

- 调试模式 `detailed=true`：不转换，继续展示普通工具卡片；
- 发布模式 `detailed=false`：工具完成后，把 `tool_card` 原位替换为 `todo_card`；
- 每次 `todoplan` 调用对应一个 `todo_card`，因此每次执行都会展示一次；
- 非 `todoplan` 工具保持原有展示。

### 5.3 TodoBoard

新增 `src/components/TodoBoard.vue`，只负责展示：

```text
┌──────────────────────────────────────────────┐
│ 任务计划   2/3 已完成  █████░░░░░ 67%    ⌄  │
├──────────────────────────────────────────────┤
│ ✓ 1  梳理需求与边界                  [高]     │
│ ✓ 2  实现 TodoPlanTool               [高]     │
│ ◐ 3  接入前端任务计划                 [高]     │
└──────────────────────────────────────────────┘
```

- `seq` 显示在每项任务前，按升序排列；
- `pending` / `in_progress` / `completed` / `cancelled` 使用不同图标和颜色；
- `high` / `medium` / `low` 使用高 / 中 / 低标签；
- 发布模式使用精简展示，调试模式不渲染 TodoBoard；
- 进度计算排除取消项：

```text
effectiveTotal = total - cancelled
progress = effectiveTotal === 0 ? 100% : completed / effectiveTotal
```

### 5.4 页面接入

发布页 `PublishedAgentChatPage.vue`：

- 不需要顶部 Todo 面板；
- `handleEvent` 中 `tool_call` 正常走 `applyTool`；
- `applyTool` 完成后自动把 `todoplan` 工具卡片替换为 `todo_card`；
- `ElegantMessage` 在执行过程内渲染 `TodoBoard`。

调试页 `AgentDebugPanel.vue`：

- `debug` 模式传 `detailed=true`，保持普通 `ToolCard`；
- `elegant` 预览模式传 `detailed=false`，展示 `TodoBoard`；
- 不新增顶部面板。

### 5.5 历史回放

`sessionReplay.js` 中复用同一个转换规则：

```js
function applyTool(message, event, detailed = false) {
  // 构建 tool_card ...
  if (!detailed && part.isFinish) {
    const todoPart = buildTodoCardPart(part)
    if (todoPart) {
      const index = message.parts.indexOf(part)
      if (index >= 0) message.parts.splice(index, 1, todoPart)
    }
  }
}
```

因此刷新或重新打开历史会话时：

- 调试模式仍然展示普通工具卡片；
- 发布模式展示任务计划列表；
- 每次 `todoplan` 调用恢复一个 TodoBoard。

### 5.6 边界与降级

| 场景 | 行为 |
| --- | --- |
| 模型不调用 `todoplan` | 不产生 TodoBoard |
| 入参缺少 `todos` / `seq` | 解析失败，保持普通工具卡片 |
| `todos=[]` | 不展示 TodoBoard |
| 多个 `in_progress` | 后端校验拒绝 |
| 内容过长 | 后端限制 300 字；前端单行省略 |
| SSE 断开 | 历史 `events` 仍能还原普通工具卡片 / TodoBoard |

---

## 六、配置、测试与实施

### 6.1 Agent 能力配置

`StandardAgentConfig` 增加能力集合：

```java
public class AgentCapabilityConfig {
    private Boolean planEnabled = true;

    public Boolean getPlanEnabled() {
        return planEnabled;
    }

    public void setPlanEnabled(Boolean planEnabled) {
        this.planEnabled = planEnabled;
    }
}
```

```java
public class StandardAgentConfig {
    private AgentCapabilityConfig capabilityConfig;

    public boolean isPlanEnabled() {
        return capabilityConfig == null
                || capabilityConfig.getPlanEnabled() == null
                || Boolean.TRUE.equals(capabilityConfig.getPlanEnabled());
    }
}
```

配置示例：

```json
{
  "capabilityConfig": {
    "planEnabled": true
  }
}
```

前端 Agent 配置页增加“任务计划”开关，默认开启。

### 6.2 测试

后端：

- `TodoValidator`：30/31 项、300/301 字、`seq` 非空 / 正数 / 唯一、排序；
- `TodoPlanTool.parsePlan`：合法 JSON、非法 JSON、缺少 `todos`、缺少 `seq`；
- 通过 `LocalToolFactory` 执行 `TodoPlanTool.plan`：状态统计正确；
- `TodoPlanMiddleware`：只在 `todoplan` 时通知、并发只通知一次、异常不影响主流程；
- `AgentCapabilityConfig`：默认开启、显式关闭。

前端：

- 调试模式保留普通工具卡片；
- 发布模式 `todoplan` 工具卡片替换为 TodoBoard；
- 每次 `todoplan` 调用展示一次；
- 历史回放与实时展示一致；
- `seq` 按升序展示；
- 空计划不展示。

### 6.3 实施步骤

1. 新增 Todo 模型、校验和 `TodoPlanTool`；
2. 新增 `TodoPlanListener` / `TodoPlanMiddleware`；
3. 注册 Bean，并在 `ReActAgentFactory` 中按开关装配；
4. 前端新增 `buildTodoCardPart` 和 `TodoBoard`；
5. 发布页 / 调试页 `applyTool` 接入转换逻辑；
6. 历史回放复用同一转换；
7. 补充测试和文档。

---

整理者：长路 创建时间：2026.11.6 更新时间：2026.11.6

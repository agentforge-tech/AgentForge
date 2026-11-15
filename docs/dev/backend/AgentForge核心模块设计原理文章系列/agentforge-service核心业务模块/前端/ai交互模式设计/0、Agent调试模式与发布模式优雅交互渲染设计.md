# Agent 调试模式与发布模式优雅交互渲染设计

## 一、背景

Agent 的一次回答不只包含最终文本，还可能经历多段模型思考、多次工具调用、工具结果回写以及最终答案生成。如果前端只把所有内容拼成一段文本，会同时产生两个问题：

- Agent 开发者无法定位思考分段、工具入参、返回值和执行耗时。
- 正式用户会看到过多技术细节，干扰主要回答，但完全隐藏过程又会让长时间执行缺乏反馈。

因此，AgentForge 将同一套 Agent 流式事件抽象为统一消息状态，再提供两种面向不同人群的 UI 表达：

| 模式 | 一句话定位 | 核心信息密度 | 主要使用者 |
| --- | --- | --- | --- |
| `debug` | 完整的 Agent 事件时间线 | 高，展示入参、结果、ID 与耗时 | Agent 开发者、调试人员 |
| `elegant` | 面向正式用户的轻量执行感知 | 低，强调状态、意图和最终回答 | Agent 的最终使用者 |

两种模式不是两套对话协议，而是对同一份 `message` 数据的两种渲染策略。这是整体设计的核心。

## 二、核心用途与使用场景

### 2.1 调试模式

调试模式用于 Agent 编辑页右侧的“调试专区”，帮助开发者在保存或发布前验证 Agent 行为。

典型场景：

- 检查 system prompt 是否产生了预期的思考和答复。
- 确认模型是否选择了正确工具。
- 检查工具名、`toolCallId`、调用入参和返回结果。
- 分析单个工具或整轮 Agent 回答的耗时。
- 定位工具执行失败、流式中断或事件顺序异常。
- 对比调试时的完整执行过程和发布后的最终用户体验。

调试请求携带 `isDebug: true`，使用独立的调试运行时和会话 ID，避免影响正式发布会话。

### 2.2 发布模式·优雅交互

优雅交互模式用于 `/agent/published/{aid}` 正式发布页。它不强调底层技术细节，而是让用户感知 Agent 正在有步骤地处理任务。

典型场景：

- 正式对话中给出“正在处理”的连续反馈。
- 在长任务中轻量展示深度思考和工具执行节点。
- 通过 `__thought` 告知用户“为什么调用该工具”，而不展开所有业务入参。
- 工具完成后展示简洁耗时，需要时可手动展开查看结果。
- 在不干扰最终 Markdown 回答的前提下，增强 Agent 的可信度与过程感。

正式请求携带 `isDebug: false`，并且只允许访问已发布的 Agent。

## 三、共享的事件驱动渲染内核

### 3.1 统一消息模型

两种模式共享一份 AI 消息状态：

```js
const answer = {
  role: 'assistant',
  status: 'running',
  parts: [],
  content: '',
  error: '',
  startedAt: Date.now(),
  finishedAt: null,
  elapsedMs: null
}
```

| 字段 | 作用 |
| --- | --- |
| `status` | `running` / `success` / `error`，驱动进行中、完成和失败 UI |
| `parts` | 按时间顺序保存 `think` 与 `tool_card` 过程节点 |
| `content` | 追加 `resp` 事件，作为最终 Markdown 回答 |
| `error` | 保存错误事件的可展示信息 |
| `startedAt` / `finishedAt` | 本轮对话开始与结束时间 |
| `elapsedMs` | 整轮 Agent 处理耗时 |

`parts` 中的思考节点与工具节点分别使用：

```js
// think
{ type: 'think', content, status: 'thinking', startedAt, duration }

// tool_card
{
  type: 'tool_card', toolCallId, toolName, description,
  inputParams, outputResult, duration, isFinish,
  startedAt, finishedAt
}
```

### 3.2 SSE 事件到 UI 状态的转换

| SSE 事件 | 状态处理 | UI 影响 |
| --- | --- | --- |
| `session` | 记录后端返回的 `sessionId` | 后续对话和会话历史继续复用 |
| `think` | 创建、追加或结束 `think` part | 思考内容流式增长，结束时记录耗时 |
| `tool_call` | 按 `toolCallId` 创建或更新 `tool_card` | 同一工具的开始事件和结束事件合并为一张卡片 |
| `resp` | 把增量文本追加到 `content` | `MarkdownView` 执行 `typewriter-blur` 流式渲染 |
| `resp_end` | 标记成功并收口未完成 part | 停止动画，记录整轮耗时 |
| `error` | 标记失败并收口 part | 展示统一错误框 |

当 `tool_call` 到达时，会先结束紧邻的进行中思考 part。因此如下事件序列：

```text
think → tool_call(A) → think → tool_call(B) → resp
```

会稳定转换为：

```text
思考段 1 → 工具 A → 思考段 2 → 工具 B → 最终回答
```

这保证了多轮 ReAct 过程不会被错误合并到上一段思考中。

### 3.3 实时渲染与历史重放

实时对话由 `handleEvent` 把 SSE 事件转换为消息状态。历史会话则由 `sessionReplay.js` 遍历数据库中的 `events` JSON，重放同样的 `think` / `tool_call` / `resp` 状态转换。

两条链路最终都生成相同的 `message` 形状，所以渲染组件不需要区分数据来自实时 SSE 还是历史数据。

## 四、调试模式事件 UI 渲染原理

### 4.1 渲染入口

`AgentDebugPanel` 默认使用 `debug` 模式，并将选择结果保存到 `localStorage` 的 `agentforge.render.mode` 中。`AgentMessageContent` 根据 mode 从模式注册表选出 `DebugMessage`：

```text
AgentDebugPanel
  └─ AgentMessageContent(mode="debug")
       └─ DebugMessage
            ├─ ThinkBlock
            ├─ ToolCard
            └─ MarkdownView(typewriter-blur)
```

调试专区也允许临时切换到 `elegant` 模式，便于在 Agent 编辑页直接预览发布后效果。切换只更换渲染组件，不会重新发起请求或重建事件数据。

### 4.2 执行过程容器

`DebugMessage` 将所有非文本 part 过滤为 `processParts`，收拢到带边框的“执行过程”容器中。

- 运行中显示“执行中...”，并自动展开。
- 完成后显示“执行过程”，并自动收起。
- 头部统计思考段数、工具数量和整轮耗时。
- 用户可再次展开，对整条时间线进行排查。

### 4.3 `think` 事件渲染

`ThinkBlock` 用卡片展示思考分段：

- `status=thinking` 时自动展开，思考图标呼吸动画，内容随事件追加。
- 内容更新时自动滚动到底部，保持最新思考可见。
- `status=end` 时收起，并展示该段思考耗时。
- 用户可点击标题手动展开或收起。

### 4.4 `tool_call` 事件渲染

`ToolCard` 面向调试人员展示完整工具信息：

- 开始事件创建卡片，显示 loading 图标和“执行中...”。
- 结束事件根据 `toolCallId` 更新同一张卡片，显示成功状态和耗时。
- 展开后显示 `description`、`toolCallId`、格式化入参和格式化返回值；事件携带调用时间时也可一并展示。
- JSON 字符串会尝试解析并缩进；解析失败时按原文展示，避免丢失调试信息。

调试模式的优先级是信息完整和问题可定位，因此允许展示更高密度的技术字段。

### 4.5 最终回答与异常状态

- `resp` 内容交由 `MarkdownView` 渲染，默认使用 `typewriter-blur`，支持 Markdown、代码块、表格、列表和数学公式。
- 尚未收到正文时，使用三点动画作为运行中占位。
- `error` 事件或请求异常会进入统一错误框，并强制收口未完成的思考和工具 part。

## 五、发布模式优雅交互 UI 渲染原理

### 5.1 渲染入口

正式发布页固定使用 `elegant` 模式：

```text
PublishedAgentChatPage
  └─ AgentMessageContent(mode="elegant")
       └─ ElegantMessage
            ├─ ElegantThinkItem
            ├─ ElegantToolItem
            └─ MarkdownView(typewriter-blur)
```

发布页不暴露模式切换器，避免正式用户进入高信息密度的调试界面。

### 5.2 轻量过程容器

`ElegantMessage` 仅保留 `think` 和 `tool_card` part，将它们组成无重边框的轻量事件流。

- 运行中显示“正在处理”，自动展开过程。
- 成功后显示“已完成 + 总耗时”，自动收起过程。
- 失败时显示“处理失败”，并保留已产生的过程信息。
- 状态行高度与左侧 Agent Logo 保持一致，避免生成前后的视觉跳动。

### 5.3 `think` 事件渲染

`ElegantThinkItem` 把思考节点简化为“深度思考 + 耗时”：

- 思考进行中自动展开，以轻量文字持续追加。
- 思考结束后自动收起，降低对最终回答的视觉干扰。
- 用户仍可点击展开已完成的思考分段。
- 内容区设置最大高度并自动跟随最新内容，避免长思考撑开页面。

### 5.4 `tool_call` 事件渲染

`ElegantToolItem` 将工具信息降噪为一行可感知事件：

```text
🔧 工具名  调用意图（耗时）  ⌄
```

具体规则：

- 执行中显示 loading 图标，完成后替换为轻量工具图标。
- 工具名始终显示，耗时只在执行完成且数值有效时显示。
- 从 `inputParams` 中提取框架保留字段 `__thought`，作为面向用户的工具调用意图。
- `__thought` 缺失、为空或入参无法解析时，不展示思考文字，工具仍正常渲染。
- 调用意图过长时单行省略，悬停可查看全文，保持事件流紧凑。
- 展开后仅展示工具返回结果，不直接暴露全部入参、`toolCallId` 等调试字段。

`__thought` 表达的是可展示的工具调用意图摘要，不是完整的模型内部思维链。

### 5.5 最终回答与运行中反馈

- 最终回答与调试模式复用同一个 `MarkdownView`，保证 Markdown 语义和排版一致。
- 流式正文默认使用 `typewriter-blur`，逐字显影但不显示光标。
- 尚未产生最终正文时，使用渐变扫光的“正在处理...”文字，代替技术感较强的三点跳动。
- 处理完成后保留最终 DOM 结构，避免流式态和最终态切换导致文本、列表或公式位置跳变。

## 六、两种模式的差异与共享边界

| 维度 | 调试模式 | 发布模式·优雅交互 |
| --- | --- | --- |
| 过程容器 | 边框卡片、高信息密度 | 轻量文字事件流 |
| 思考展示 | 强调完整分段和状态 | 强调进度感，完成后弱化 |
| 工具入参 | 完整 JSON | 仅取 `__thought` 作为调用意图 |
| 工具结果 | 完整格式化展示 | 默认收起，按需展开 |
| 技术元数据 | 展示 `toolCallId`、描述等 | 隐藏 |
| 加载反馈 | 三点动画 | “正在处理...”渐变扫光 |
| 最终回答 | `MarkdownView(typewriter-blur)` | `MarkdownView(typewriter-blur)` |
| 错误展示 | 统一错误框 | 统一错误框 |
| 数据源 | 共享 `message` / SSE 事件 | 共享 `message` / SSE 事件 |

两种模式共享的是事件解析、消息状态和 Markdown 内核；独立的是过程信息的展示密度和视觉表达。这样既能避免实时与历史、调试与发布之间产生语义差异，又能让各场景保持合适的 UI 复杂度。

## 七、核心组件职责

| 组件/文件 | 核心职责 |
| --- | --- |
| `modes/index.js` | 注册 `debug` / `elegant` 模式并解析模式组件 |
| `AgentMessageContent.vue` | 动态选择渲染模式，承载共享的复制和错误操作 |
| `AgentDebugPanel.vue` | 发起调试 SSE 请求，维护实时消息状态，提供模式切换 |
| `PublishedAgentChatPage.vue` | 发起正式 SSE 请求，维护会话与历史，固定使用优雅模式 |
| `DebugMessage.vue` | 调试时间线总容器 |
| `ThinkBlock.vue` | 高信息密度思考卡片 |
| `ToolCard.vue` | 完整工具调用详情 |
| `ElegantMessage.vue` | 发布页轻量执行过程容器 |
| `ElegantThinkItem.vue` | 轻量思考节点 |
| `ElegantToolItem.vue` | 轻量工具节点和 `__thought` 意图展示 |
| `MarkdownView.vue` | 两种模式共享的流式 Markdown 渲染内核 |
| `sessionReplay.js` | 将历史 `events` 重放为与实时消息相同的数据结构 |

## 八、扩展约束

1. 新增 SSE 事件时，必须同时考虑实时 `handleEvent` 和历史 `sessionReplay` 的语义一致性。
2. 新增过程 part 类型时，应先定义统一数据结构，再分别为 debug 和 elegant 提供渲染器。
3. 调试模式优先保留诊断信息；优雅模式优先保持界面安静和任务进度可感知。
4. 不应在优雅模式直接展示密钥、认证头、完整内部提示词等敏感信息。
5. 模式切换只应影响视图层，不应改变 Agent 执行、事件持久化或会话状态。

## 九、总结

AgentForge 的调试模式和发布模式优雅交互，本质上是“一套事件数据，两种信息表达”：

- 调试模式解决开发阶段的可观测、可定位问题。
- 优雅模式解决发布阶段的进度可感知、界面低干扰问题。
- 统一的 `message.parts + content` 模型保证实时事件、历史重放和模式切换之间的语义一致。
- 共享的 `MarkdownView(typewriter-blur)` 保证两种场景的最终回答具有一致的流式阅读体验。

这种分层设计使 Agent 执行逻辑、事件协议和 UI 表达彼此解耦，后续新增模式时只需增加渲染组件，无需重写 Agent 对话链路。

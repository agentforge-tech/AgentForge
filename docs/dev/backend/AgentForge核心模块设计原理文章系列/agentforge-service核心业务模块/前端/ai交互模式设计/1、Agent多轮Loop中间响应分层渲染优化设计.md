# Agent 多轮 Loop 中间响应分层渲染优化设计

## 一、背景与优化效果

### 1.1 背景

AgentForge 的 Agent 在处理一次用户请求时，可能经历多轮“模型响应 → 思考 → 工具调用”的 Loop，直到条件满足后才输出最终回答。一次请求的典型事件链如下：

```text
resp（阶段性说明）
  → think
  → tool_call
  → resp（阶段性结论）
  → think
  → tool_call
  → resp（最终回答）
  → resp_end
```

现有前端渲染逻辑源于“一次请求对应一段回答”的简单对话模型，因此将所有 `resp` 增量直接追加到 `message.content`。这个假设在单轮对话中成立，但在多轮 Agent Loop 中，`resp` 只能表示“当前产生的响应增量”，不一定是请求级的最终回答。

因此，前端需要把连续的 `resp` 增量先聚合为 `response_segment`，再根据后续事件判定它属于执行过程还是最终正文。这项优化需要同时覆盖实时流式渲染、历史重放、调试模式和发布模式，避免同一次会话在不同入口呈现出不同结果。

### 1.2 优化前后效果对比

假设 Agent 先后输出两段过程性文本，最后才给出正式答复。

**优化前：所有 `resp` 混入正文**

```text
我先查询一下订单状态……
我已经拿到订单信息，接下来核对库存……
最终结论是：订单将在明天送达。
```

用户看到的是一段被执行过程污染的正文，无法快速区分阶段性说明和最终结论；复制回答时也会一并复制过程文本。

**优化后：执行过程与最终正文分层展示**

```text
执行过程（默认收拢，可展开）
  ├─ 响应片段 1：我先查询一下订单状态……
  ├─ 深度思考
  ├─ Tool：queryOrder
  ├─ 响应片段 2：我已经拿到订单信息，接下来核对库存……
  ├─ 深度思考
  └─ Tool：queryInventory

最终回答
  订单将在明天送达。
```

| 对比项 | 优化前 | 优化后 |
| --- | --- | --- |
| 最终正文 | 包含所有中间 `resp` | 只包含最终回答 |
| 执行过程 | 响应文本与正文混合，思考和工具节点不完整 | 中间响应、思考和工具按真实顺序归档 |
| 流式展示 | 候选正文可能先出现，后续又需回收 | 当前 `response_segment` 在过程区实时展示，最终确认后再进入正文 |
| 历史重放 | 可能与实时渲染结果不一致 | 共用 reducer，保证结果一致 |
| 复制回答 | 会夹带过程文本 | 默认只复制最终正文 |

## 二、问题结论

当前 Agent 的流式协议允许一次用户问题经历多轮 Loop。模型在每一轮中都可能产生 `type=resp` 的文本，然后继续 `think`、调用 `tool`，再进入下一轮。当前前端的处理方式是把所有 `resp` 增量直接追加到 `message.content`：

```js
case 'resp':
  answer.content += event.content || ''
  break
```

这会把“中间轮次的 AI 回复”和“最终 AI 回复”拼成一段正文，导致正式回答中出现类似下面的内容：

```text
我先查询一下订单状态……
我已经拿到订单信息，接下来核对库存……
最终结论是：订单将在明天送达。
```

其中前两段实际上是 Agent Loop 的过程性输出，不应该和最终回答处于同一个正文层级。

需要明确的核心规则是：

> `resp` 表示一个 AI 响应片段，不等于最终回答。只有最后一个没有继续驱动下一轮 Loop 的响应片段，才进入 AI 正文；此前的响应片段必须保留在执行过程时间线中，并支持收拢与展开。

调试模式和发布模式必须共享这套“响应分段、过程归档、最终回答判定”逻辑。两种模式只能有信息密度和视觉样式差异，不能拥有不同的事件语义。

## 三、当前事件处理的问题

### 3.1 `resp` 语义过宽

当前协议中 `resp` 的描述是“AI 回答事件”，但它实际同时承载了两类内容：

| 类型 | 实际含义 | 当前处理方式 | 正确归属 |
| --- | --- | --- | --- |
| 中间响应 | 本轮模型在调用工具前给出的解释、阶段性结论或过渡文本 | 追加到 `message.content` | 执行过程中的 `response_segment` |
| 最终响应 | Agent 完成所有工具和思考后的最终答复 | 追加到 `message.content` | AI 最终正文 |

仅凭事件名称无法区分两者，因此不能再把 `resp` 直接等价为 `message.content`。

### 3.2 实时处理与历史重放存在同样风险

当前问题同时存在于：

- `AgentDebugPanel.vue` 的实时 `handleEvent`。
- `PublishedAgentChatPage.vue` 的实时 `handleEvent`。
- `sessionReplay.js` 的历史事件重放。

如果只修改某一个页面，实时展示、调试展示、发布展示和历史消息就会出现语义不一致。例如实时页面把中间响应收拢到过程区，但刷新历史后 `sessionReplay` 仍把所有响应拼到正文中。

### 3.3 当前 `parts` 不能表达响应节点

现在 `parts` 主要保存 `think`、`tool_card`、追加消息和上下文压缩节点，`resp` 被单独放到了 `message.content`。因此执行时间线缺少最重要的一类节点：每一轮模型实际输出的响应片段。

应将 `resp` 也纳入统一的过程节点模型，而不是继续维护“过程在 `parts`、响应在 `content`”的二元语义。

## 四、目标交互效果

一次包含多轮 Loop 的请求，统一按照真实事件顺序呈现：

```text
第 1 轮 AI 响应
  ↓
深度思考
  ↓
Tool 调用
  ↓
第 2 轮 AI 响应
  ↓
深度思考
  ↓
Tool 调用
  ↓
最终 AI 回答
```

最终 UI 分为两个层级：

1. `message.content`：只显示最终 AI 回答，使用现有的 `MarkdownView` 作为主要正文。
2. `message.parts`：显示所有中间响应、思考、工具调用等过程节点，默认收拢，用户可以展开查看完整执行过程。

其中“收拢”不是删除中间响应，而是改变默认可见性。调试模式仍需保留完整文本，发布模式则可以使用更轻量的摘要，但两者看到的节点顺序和最终回答内容必须一致。

## 五、推荐的统一消息模型

### 5.1 增加 `response_segment` 节点

`response_segment` 表示由一个或多个相邻 `resp` 增量聚合而成的连续响应片段。在流式生成期间，该片段可能成为中间响应，也可能在 `resp_end` 到达时被提升为最终回答。因此节点名称描述的是“响应分段”，不预先假定其最终归属。

在现有 `parts` 中增加响应片段：

```js
{
  type: 'response_segment',
  roundNo: 1,
  content: '我先查询一下订单状态……',
  status: 'streaming', // streaming / complete
  startedAt: 1710000000000,
  finishedAt: null,
  isFinal: false
}
```

字段说明：

| 字段 | 作用 |
| --- | --- |
| `type` | 固定为 `response_segment`，与 `think`、`tool_card` 并列 |
| `roundNo` | 可选的 Loop 轮次，主要用于展示和调试定位 |
| `content` | 当前轮响应文本，按 `resp` 增量追加 |
| `status` | 当前响应是否仍在流式生成 |
| `startedAt` / `finishedAt` | 统计当前响应片段耗时 |
| `isFinal` | 是否已被判定为最终回答 |

消息对象保持对现有组件的兼容：

```js
const answer = {
  role: 'assistant',
  status: 'running',
  parts: [],
  content: '', // 只保存最终 AI 回答
  error: '',
  startedAt: Date.now(),
  finishedAt: null,
  elapsedMs: null
}
```

`message.content` 的语义必须收窄为“最终正文”，不能再作为所有 `resp` 的累加器。

### 5.2 推荐的过程节点顺序

统一 reducer 应保留事件的实际先后顺序：

```js
parts: [
  { type: 'response_segment', roundNo: 1, content: '先查询订单……', isFinal: false },
  { type: 'think', content: '需要确认库存', status: 'end' },
  { type: 'tool_card', toolName: 'queryOrder', isFinish: true },
  { type: 'response_segment', roundNo: 2, content: '已拿到订单信息……', isFinal: false },
  { type: 'think', content: '还需要核对配送时间', status: 'end' },
  { type: 'tool_card', toolName: 'queryDelivery', isFinish: true }
],
content: '订单将在明天送达。'
```

前端不应为了视觉上的“回答 → 思考 → 工具”而擅自重排事件。后端未来提供 `roundNo` 后，可以在同一轮内进行分组，但同一轮中的事件仍应保留产生顺序。

## 六、统一事件 reducer 设计

### 6.1 不能继续在两个页面分别实现事件语义

建议抽出共享模块，例如：

```text
src/utils/aiEventReducer.js
```

由以下三条链路共同调用：

```text
AgentDebugPanel.vue       ─┐
PublishedAgentChatPage.vue ├─> reduceAiEvent(answer, event, options)
sessionReplay.js           ─┘
```

页面只负责请求、滚动、停止和模式选择；事件如何转换为 `parts` 与 `content`，只能由这一份 reducer 决定。

### 6.2 事件转换规则

| 事件 | 统一处理 | 结果 |
| --- | --- | --- |
| `resp` | 追加到当前 `response_segment`，不存在则创建 | 响应先进入过程节点，不直接进入正文 |
| `think` | 先结束当前响应片段，再创建或追加 `think` | 保留“响应 → 思考”的顺序 |
| `tool_call` | 先结束当前响应和思考，再创建或更新工具节点 | 保留“响应/思考 → 工具”的顺序 |
| `compact` | 结束当前响应，按现有逻辑写入压缩节点 | 防止响应跨越上下文边界拼接 |
| `retry` | 结束当前响应，保留重试前的过程片段 | 重试过程不污染最终正文 |
| `resp_end` | 将最后一个响应片段提升为最终正文 | 只有此时才写入 `message.content` |
| `error` / 取消 | 收口当前过程节点 | 未确认的响应保留在过程区，不伪装成最终回答 |

推荐的 reducer 伪代码如下：

```js
function reduceAiEvent(answer, event) {
  switch (event.event) {
    case 'resp':
      appendResponseSegment(answer, event)
      break
    case 'think':
      closeOpenResponseSegment(answer)
      applyThink(answer, event)
      break
    case 'tool_call':
      closeOpenResponseSegment(answer)
      closeOpenThink(answer)
      applyTool(answer, event)
      break
    case 'resp_end':
      promoteLastResponseToFinal(answer)
      answer.status = 'success'
      finishParts(answer)
      break
    case 'error':
      closeOpenResponseSegment(answer)
      answer.status = 'error'
      finishParts(answer)
      break
    default:
      applyOtherEvent(answer, event)
  }
}
```

`promoteLastResponseToFinal` 的逻辑是：

1. 找到最后一个 `response_segment`。
2. 将其文本写入 `message.content`。
3. 从过程节点中移除它，或标记为 `isFinal=true` 并由视图层渲染到正文区域。
4. 之前所有 `response_segment` 保留在 `parts` 中。

推荐采用“移除并写入 `content`”的方式，因为这样可以继续复用当前 `MarkdownView`、复制正文和最终回答样式。

### 6.3 避免最终响应出现视觉闪烁

在没有明确的最终标记时，收到第一个 `resp` 时不能立即写入 `message.content`。它必须先作为候选的 `response_segment` 保存在 `parts` 中：

- 运行中：视图层可以预览当前候选片段，但数据层的最终正文仍为空。
- 收到 `think` 或 `tool_call`：当前响应变成已完成的中间响应，并保持在过程区。
- 收到 `resp_end`：最后一个响应节点才提升为主正文。

这样可以保证最终正文的数据语义不被中间响应污染。视图层如何无感预览候选片段，由下一节单独规定。

### 6.4 候选响应晋升为 Markdown 正文时的无感切换

#### 问题点

中间响应在过程区只需要作为普通文本展示，但最终正文需要通过 `MarkdownView` 渲染标题、列表、代码块和其他 Markdown 语法。如果按照“过程区纯文本 → `resp_end` 时移入正文区并重新渲染”的方式直接实现，会出现以下问题：

1. 结束瞬间文本的字号、行高和块级结构突然变化。
2. 内容从过程容器移到正文容器，会引起 DOM 重建、位置跳动或短暂空白。
3. 代码块、表格或列表只在最后一帧突然成型，用户会明显感知到“中间响应被推入正文”的切换。

#### 优化方案

视图层将最后一个仍处于 `streaming` 状态的 `response_segment` 视为“当前候选响应”，但不改变它在数据层的归属：

```js
const activeResponseSegment = findLastStreamingResponse(message.parts)
const displayContent = message.content || activeResponseSegment?.content || ''
const contentStreaming = !message.content && !!activeResponseSegment
```

渲染时使用一个稳定的正文槽位：

```vue
<div v-if="displayContent" class="answer">
  <MarkdownView
    :content="displayContent"
    :streaming="contentStreaming"
  />
</div>
```

具体规则如下：

1. 当前候选片段在正文槽位中直接进行流式 Markdown 渲染，不再先以纯文本展示后重建。
2. 候选片段仍存储在 `message.parts`，并且不进入过程列表，避免同一内容重复渲染。
3. 若后续收到 `think`、`tool_call`、`compact` 或 `retry`，该片段被关闭并归档到过程区，在过程区中仅作为普通文本直接展示。
4. 若后续直接收到 `resp_end`，reducer 将同一片段的内容写入 `message.content` 并从 `parts` 移除。
5. Vue 在同一次响应式更新中，只会看到 `displayContent` 的数据源从候选片段切换为 `message.content`；文本、容器和 `MarkdownView` 组件均保持不变。

最终效果是：最终响应从流式生成到正式完成始终处于同一视觉位置，Markdown 样式也持续一致；`resp_end` 只完成数据语义上的晋升，用户不会感知到内容被移动或重新渲染。

还需要注意：复制操作仍然只允许复制已确认的 `message.content`，不应把仍在流式生成的候选片段当作最终回答。

### 6.5 空白增量不能作为响应与思考的语义边界

#### 问题点

某些模型或流式适配器会在阶段切换时产生 `content=" "`、`content="\n"` 或其他只包含空白字符的 `resp` / `think` 事件。这些事件对最终可见内容没有语义，但如果 reducer 只根据 `event` 类型判定边界，就会错误拆分节点。

例如某次实际事件序列：

```text
eventSeq=16~20  think("The search results are poor...")
eventSeq=21     resp(" ")
eventSeq=22~25  think("Let me do those two in parallel...")
```

如果 `resp` 分支无条件执行 `closeOpenThink()`，会得到：

```text
think A → response_segment("") → think B
```

视图层通常又会过滤空的 `response_segment`，因此用户最终看到两个相邻的“深度思考”，看起来像模型重复思考，实际上是前端把空白事件误判为了语义边界。

类似地，独立的 `think(" ")` 也会创建没有内容的思考节点。

#### 优化规则

`resp` 是否能作为边界，必须同时考虑增量内容和当前状态：

```js
function shouldAppendResponseSegment(answer, event) {
  const hasVisibleText = Boolean(String(event.content || '').trim())
  const hasOpenResponse = Boolean(findOpenResponseSegment(answer))
  return hasVisibleText || hasOpenResponse
}
```

处理规则如下：

1. 当前没有打开的 `response_segment` 时，纯空白 `resp` 直接忽略，不创建响应节点，也不关闭当前 `think`。
2. 已经存在打开的 `response_segment` 时，仍允许追加空格和换行，避免把正常文本拼成一团。
3. 当前没有打开的 `think` 时，纯空白 `think` 直接忽略，不创建空思考节点。
4. 已经存在打开的 `think` 时，空白增量可以作为思考文本内部的格式保留，但终止事件不能用空白内容覆盖已有思考文本。

实时的 `AgentDebugPanel.vue`、`PublishedAgentChatPage.vue` 与历史重放 `sessionReplay.js` 必须共用这套判定，否则可能出现“实时只有一段思考，刷新后变成两段”的不一致问题。

## 七、协议增强方案

### 7.1 当前协议的兼容处理

当前 `StreamEventResponse` 只有 `event`、`type`、`title`、`content`、`isFinish` 和 `eventSeq`，没有轮次或最终性字段。第一阶段可以由前端按照事件边界推断：

- `resp` 后面出现 `think`、`tool_call`、`compact` 或 `retry`，则前一个响应是中间响应。
- `resp_end` 到达时，最后一个响应是最终响应。
- 没有任何 `resp` 的请求，最终正文保持为空。

这个方案可以兼容已有 SSE 和历史 `events` 数据，但它依赖事件顺序，不能表达所有复杂边界。

### 7.2 推荐增加可选元数据

后续建议为 `StreamEventResponse` 增加可选字段：

```json
{
  "event": "resp",
  "type": "text",
  "content": "已拿到订单信息，接下来核对库存。",
  "isFinish": false,
  "roundNo": 2,
  "respPhase": "intermediate",
  "eventSeq": 18
}
```

字段建议：

| 字段 | 可选值 | 用途 |
| --- | --- | --- |
| `roundNo` | 正整数 | 标识 Agent Loop 轮次，便于分组、统计和调试 |
| `respPhase` | `intermediate` / `final` | 明确该响应是过程性回复还是最终回复 |

协议增强后，reducer 优先使用 `respPhase` 判定；没有该字段时再使用兼容推断。这样可以逐步升级后端，不需要一次性迁移已有会话。

### 7.3 事件缓冲与历史持久化注意事项

后端 `BufferedMessageEventSink` 会合并相邻的 `resp` / `think` 增量。新增 `roundNo`、`respPhase` 后必须同步：

- `StreamEventResponse` 增加字段及 getter/setter。
- `BufferedMessageEventSink.copyOf` 复制新增字段。
- `sameDeltaGroup` 比较新增字段，不能跨轮次或跨阶段合并文本。
- SSE 序列化、数据库 `events` JSON 和历史重放保持字段一致。

否则不同 Loop 轮次的响应可能在持久化层被错误合并，前端即使有正确 reducer 也无法恢复原始边界。

## 八、调试模式与发布模式的统一渲染

### 8.1 共享逻辑，差异只在视觉密度

两种模式都接收相同的 `message`：

```text
统一 reducer
  ├─ message.content：最终回答
  └─ message.parts：中间响应、think、tool_call……
```

两种模式不允许出现以下差异：

- 调试模式把 `resp` 放入 `parts`，发布模式继续拼到 `content`。
- 调试模式按事件重放，发布模式按字符串重建。
- 调试模式展示中间响应，发布模式丢弃中间响应。
- 实时模式和历史模式使用不同的最终回答判定规则。

### 8.2 调试模式

调试模式的过程容器按时间线展示：

```text
第 1 轮 AI 响应（可展开）
深度思考
Tool: queryOrder
第 2 轮 AI 响应（可展开）
深度思考
Tool: queryDelivery
```

建议规则：

- 运行中的当前 `response_segment` 在正文槽位中使用 `MarkdownView` 实时预览。
- 已完成的中间响应按时间线顺序直接显示纯文本，不再增加片段级标题或二次展开。
- 头部统计“响应片段数、思考段数、工具数、总耗时”。
- 最终正文单独使用 `MarkdownView`，复制按钮只复制最终正文。
- 需要完整审计时，可提供“展开全部过程”。

### 8.3 发布模式

发布模式使用相同的时间线和节点顺序，但降低视觉噪音：

- 过程总容器默认收拢，运行时自动展开。
- 当前 `response_segment` 在正文槽位中使用流式 Markdown 渲染，不额外显示“阶段性回答”等标题。
- 已完成的中间响应在过程时间线中以普通文本直接展示，不再增加片段级折叠。
- `think` 和 `tool_card` 继续使用现有的轻量组件。
- 最终正文始终位于过程容器之后，且只使用最终 `message.content`。

这里的“轻量”只能改变时间线密度和技术字段的可见性，不能改变节点顺序和最终回答判定。

### 8.4 建议新增共享组件

```text
src/components/ResponseSegmentBlock.vue
```

该组件接收统一的 `response_segment` part，负责：

- 对已确认的中间响应进行普通文本展示。
- 保留原始换行，并处理超长文本自动换行。
- 清理内容首尾空白，避免空 `response_segment` 撑开时间线间距。

当前仍在流式生成的候选片段不使用该组件，而是由 `DebugMessage` 和 `ElegantMessage` 的稳定正文槽位使用 `MarkdownView` 渲染。两种模式复用同一判定规则，不重复实现响应片段的状态转换。

## 九、历史重放与旧数据兼容

### 9.1 实时与历史必须复用同一 reducer

`sessionReplay.js` 不应继续单独实现 `resp` 的拼接逻辑，而应按事件序列调用同一个 `reduceAiEvent`。这样以下场景可以保证一致：

- 实时响应和刷新页面后的历史响应一致。
- 调试模式和发布模式切换前后数据一致。
- 事件断线重连后从 `eventSeq` 继续重放不会重复拼接。

### 9.2 已有历史记录的推断规则

旧记录没有 `roundNo` 和 `respPhase` 时，重放可以按以下规则兼容：

1. 相邻 `resp` 增量合并为一个 `response_segment`。
2. `resp` 后出现 `think`、`tool_call`、`compact` 或 `retry`，关闭当前响应并保留为中间响应。
3. `resp_end` 到达时，将最后一个响应提升为最终正文。
4. 只有在没有事件明细时，才使用 `record.contentText` 作为正文兜底。

不能在事件明细存在时无条件执行：

```js
if (!message.content && record.contentText) {
  message.content = record.contentText
}
```

否则可能再次把后端历史字段中已经拼接的中间响应放回最终正文。正确做法是优先使用事件重放结果，`contentText` 只作为缺少事件数据时的兼容兜底。

## 十、异常、取消和边界场景

| 场景 | 处理方式 |
| --- | --- |
| 只有一段 `resp`，没有工具调用 | `resp_end` 时提升为最终正文 |
| `resp → think → tool → resp → resp_end` | 第一段进入过程，第二段进入最终正文 |
| `think → tool → think → tool → resp` | 前面的过程进入 `parts`，最后响应在结束时进入正文 |
| `resp → tool`，没有 `think` | 响应直接作为过程节点，不要求必须存在思考节点 |
| `think → resp(" ") → think` | 忽略空白 `resp`，前后 `think` 合并为同一思考节点 |
| 独立的 `think(" ")` | 不创建空思考节点 |
| 工具失败后 `error` | 当前响应保留在过程区，显示错误状态，不伪装为最终正文 |
| 用户主动取消 | 未确认完成的响应留在过程区，消息状态为 `cancelled` |
| `resp_end` 没有任何 `resp` | 正文为空，按现有成功态和过程态展示 |
| 断线重连重复事件 | 继续使用 `eventSeq` 去重，避免响应片段重复追加 |

## 十一、落地步骤

### 第一阶段：前端兼容修复

1. 新增 `response_segment` part。
2. 抽取 `aiEventReducer.js`，统一实时和历史事件处理。
3. 修改调试页、发布页的 `handleEvent`，不再直接执行 `answer.content += resp`。
4. 修改 `sessionReplay.js`，采用同一 reducer。
5. 新增 `ResponseSegmentBlock.vue`，两种模式复用。
6. 使用事件边界推断最终响应，兼容现有 SSE 和历史数据。

### 第二阶段：后端协议增强

1. 为 `StreamEventResponse` 增加可选的 `roundNo`、`respPhase`。
2. 在 Agent Loop 产生响应时填充轮次和响应阶段。
3. 更新 `BufferedMessageEventSink` 的复制和分组逻辑。
4. 增加 SSE、数据库事件和历史重放的兼容测试。

### 第三阶段：交互细节优化

1. 调试模式增加响应片段数量、轮次和工具耗时统计。
2. 发布模式继续优化过程时间线密度和一键展开全部过程。
3. 对超长中间响应增加最大高度和渐进式展开，避免撑满页面。
4. 最终正文和过程响应分别支持复制，默认复制最终正文。

## 十二、验收标准

### 数据正确性

- 最终 `message.content` 不包含任何已经触发下一轮 Loop 的中间 `resp`。
- 所有中间响应均保留在 `message.parts`，没有被丢弃。
- 事件顺序在实时渲染和历史重放中完全一致。
- 调试模式与发布模式使用相同的 reducer 结果。
- 纯空白 `resp` / `think` 不会创建空节点，也不会把一段连续思考拆成多段。

### 交互正确性

- 运行中的当前候选响应在稳定的正文槽位中流式渲染，晋升为最终正文时不会重建、闪烁或跳位。
- 完成后过程默认收拢，用户可以展开查看每一轮响应、思考和工具调用。
- 最终回答单独位于过程区域之后，继续使用现有 Markdown、代码块和复制能力。
- 取消、异常、重试和断线重连不会造成中间响应重复或错误晋升。

### 测试事件序列

至少覆盖以下输入：

```text
1. resp → resp_end
2. resp → think → tool_call → resp → resp_end
3. think → tool_call → think → tool_call → resp → resp_end
4. resp → tool_call → error
5. 多个 resp 增量、跨数据库合并后的历史重放
6. 实时渲染后刷新页面的结果对比
7. debug 与 elegant 模式的 parts/content 快照对比
```

## 十三、总结

这次优化的关键不是简单给 `resp` 增加一个折叠按钮，而是重新定义响应数据的层级：

- 中间 `resp` 是 Agent Loop 的过程节点。
- 最后一个 `resp` 才是 AI 最终正文。
- 所有节点由同一个 reducer 生成。
- 调试模式和发布模式共享数据语义，只差视觉展示密度。
- 实时 SSE、历史重放和协议增强都围绕同一套分层规则演进。

这样可以解决当前多轮 Loop 中间响应污染 AI 正文的问题，同时保留 Agent 执行过程的可观察性，并让两种模式在交互逻辑上保持完全一致。

# AgentForge AI 正文 Markdown 流式渲染方案调研与优化设计

本文聚焦**流式打印过程中**的正文渲染效果：模型逐 token 推送，`resp` 事件以增量到达，前端在未闭合的 Markdown 上持续重渲染。调试面板（`DebugMessage`）与正式发布页（`ElegantMessage`）共用 `components/MarkdownView.vue`。

文档结构：第一章定位现状问题；第二章调研业界主流呈现模式、知名产品与开源方案；第三章给出 AgentForge 的**可切换多模式 Markdown 渲染器（AfMarkdown）**设计规划；第四章为实施计划；第五章为参考来源。

## 一、实现与问题定位

### 1.1 现有链路

```text
Agent 流式输出
  └─ ServiceStreamingMiddleware.onPartialResponse
        └─ StreamEventResponse(event=resp, type=text, content=片段, isFinish=false)
              └─ SSE data: {...}
                    └─ 前端 answer.content += 片段
                          └─ <MarkdownView :content="answer.content">
                                └─ computed html = md.render(全量 content)
                                      └─ <div v-html="html">
```

`MarkdownView` 的实现要点：

| 项 | 现状 |
| --- | --- |
| 解析器 | `markdown-it`（`html:false`、`linkify:true`、`breaks:true`） |
| 高亮 | `highlight.js`，在 `highlight(str, lang)` 里同步产出 `<pre class="hljs">` |
| 输出 | 每次全量 `md.render()` 后整体 `v-html` 覆盖 |
| 更新粒度 | 每个 `resp` 片段触发一次全量解析 + 全量重排 DOM |

### 1.2 实测问题（本地复现）

用当前依赖版本直接渲染"半截 Markdown"，验证流式过程中的中间态：

| 输入（流式中途） | 当前输出 | 用户观感 |
| --- | --- | --- |
| ` ```js` + `const a = 1;`（未闭合） | 已渲染为完整 `<pre><code>` | 尚可，但下一帧高亮会整体重算 |
| `\| a \| b \|` + 分隔行 + `\| 1 \|` | 已渲染成表格，单元格空 | 表格列宽在后续帧反复跳动 |
| `这是 **重点`（未闭合粗体） | 输出字面量 `**重点` | 出现裸露的 `**`，随后"跳变"成粗体 |
| `见 [文档](https://exa` | 输出 `[文档](` + 自动链接 `https://exa` | 出现残缺括号与错误链接 |
| `- 第一项` + `- 第二` | 列表正常，但每帧整段重排 | 长列表滚动位置抖动 |

核心结论：**问题不在 markdown-it 能力不足，而在于"全量重渲染 + 未闭合语法裸露 + 代码高亮每帧重算"这三件事叠加在流式场景下被放大。**

## 二、行业方案调研

### 2.1 业界主流呈现模式

"模式"这一层在业界叫 **smooth streaming / streaming text animation**。核心认知是：**网络流 ≠ 视觉流**——服务端 token 到达节奏不均匀（可能一次几十字，也可能停顿数秒），经缓冲区按固定节奏吐到屏幕，视觉才连续。

```text
服务端 token 到达（不均匀）
  └─ 进入缓冲区 buffer
        └─ 按固定节奏（5~20ms/单位）逐字/逐词吐到屏幕
              └─ 视觉上匀速、连续
```

#### 2.1.1 模式清单

| 模式 | 触发粒度 | 视觉表现 | 代表实现 | 中文适配 |
| --- | --- | --- | --- | --- |
| `instant` | 服务端 chunk | 成块蹦出，节奏不均 | 默认 SSE 直出 | 无问题，观感差 |
| `typewriter` | 单字符 | 逐字出现 + 光标 | prompt-kit `mode="typewriter"`、CSS `steps()` | 无问题（按 code point 切） |
| `fade` | 词 | 逐词淡入（透明度 0→1） | prompt-kit `mode="fade"`、FlowToken `fadeIn` | 需分词 |
| `drop-in` / `slide` | 词/字 | 位移 + 淡入 | FlowToken `dropIn` / `slideFromLeft` | 需分词 |
| `word pull-up` | 词 | 逐词上浮 | FlowToken `wordPullUp` | 需分词 |
| `flip` | 词 | 翻转入场（`rotateX`） | FlowToken `flipText` | 需分词 |
| **`typewriter-blur`** | 单字符 | **从左到右逐字 + 每字模糊渐清** | 组合实现：typewriter 逐字推进 + FlowToken `blurIn` 的 keyframes | 无问题（按 code point 切） |
| `line` | 行 | 整行出现 | AI SDK `smoothStream({chunking:'line'})` | 无问题 |
| `char` | 字符 | 最平滑，成本最高 | AI SDK + 自定义 | 无问题 |

**关于「文字从左到右、从模糊到清晰」**：这个描述叠加了**两个正交维度**，业界没有单独一个名词同时覆盖：

| 维度 | 决定什么 | 对应做法 |
| --- | --- | --- |
| 推进方向 | 文字**按什么顺序**出现 | `typewriter`：逐字从左到右 |
| 入场效果 | 每个单位**以什么状态**出现 | `filter: blur(3px) → 0`：模糊 → 清晰 |

两者组合即 **`typewriter-blur`**（业界也称 typewriter with blur，或「带模糊入场的打字机」）：每个新字先在模糊态出现，随后迅速锐化，视觉上像「笔迹逐渐显影」，既有逐字推进的节奏感，又有生成过程的质感。

渐进式模式的取舍：

| 模式 | 视觉效果 | 成本 | 风险 |
| --- | --- | --- | --- |
| `typewriter` | 逐字打字，最「对话感」 | 低 | 无结构风险，但长文显得慢 |
| `typewriter-blur` | 逐字 + 逐字渐清，最「生成感」 | 中（每字一个合成动画） | 需控制同时动画的字数，否则掉帧 |

结论：中文界面下所有「按词」模式都必须先解决分词，否则整句被当作一个词一次蹦出，等于没做动画。

#### 2.1.2 分词：中文场景的硬约束

AI SDK `smoothStream` 文档明确标注：`chunking:'word'` 对中日韩、越南语、泰语这些不以空格分词的语言效果不好，推荐改用 `Intl.Segmenter`：

```js
const segmenter = new Intl.Segmenter('zh', { granularity: 'word' })
```

| 浏览器 | 起始版本 |
| --- | --- |
| Chrome / Edge | 87+ |
| Safari（含 iOS） | 14.1 / 14.5+ |
| Firefox | 125+ |

MDN 标注为 well established（2024-04 起跨浏览器可用）。仍需降级：`typeof Intl.Segmenter === 'function' ? 分词 : 按字符切`。

#### 2.1.3 模式与 Markdown 的冲突

「逐词淡入」与「Markdown 解析」天然冲突：淡入要把文本切成独立 `<span>`，而解析器输出的是 `<p>`/`<li>`/`<td>` 等结构，把 span 塞进表格单元格或代码块会破坏结构与高亮。prompt-kit 文档直接承认 `fade` 配 Markdown 很难做、demo 属实验性。

| 策略 | 说明 | 适用 |
| --- | --- | --- |
| 块内动效 | 只对纯文本叶子节点（段落、列表项文字）做淡入，代码块/表格/标题不做 | **推荐** |
| 整块入场 | 每个已完成块整体淡入/上浮 | 折中 |
| 全量分词动画 | 所有文本切 span | 不推荐，破坏结构 |

#### 2.1.4 光标与状态指示

| 形式 | 说明 | 来源 |
| --- | --- | --- |
| 闪烁光标 caret | 尾块末尾追加竖线，`aria-hidden`，无新内容可停闪 | maud-ui Cursor、prompt-kit typewriter |
| 思考点 dots | 三点点跳动，用于"已发出但还没首个 token" | maud-ui Dots |
| 脉冲环 pulse | 环状呼吸，用于长任务/工具执行中 | maud-ui Pulse |

AgentForge 现状只有「正在处理」文字占位，缺尾块光标。

### 2.2 知名产品的呈现效果

| 产品 | 呈现特征 | 可验证依据 |
| --- | --- | --- |
| ChatGPT / Claude / Gemini | 文本随 token 到达即出，**不做逐字匀速动画**；重点是稳定不跳变、代码块/表格可用 | 通用产品观察；w3tweaks 归纳"逐词逐字出现"的观感 |
| Anthropic（Claude） | 生产环境使用 **smooth streaming**：网络 chunks 缓冲后按固定节奏吐字，节奏更自然 | Upstash 文章明确"用于 Anthropic 生产环境" |
| Claude Code | 自研 Ink 引擎（React → 终端），多区域独立刷新 + 屏幕 buffer 差量更新 + 字符 interning | Claude Code UI 渲染拆解 |
| Codex CLI | segment content hash 缓存 + 脏区重绘 + delta 增量追加；v0.145 前全量重渲染导致长会话卡顿 | v0.145 渲染管线复盘、issue #18693 |
| CodeBuddy / WorkBuddy | 终端与 Web UI 双形态共享同一事件流；工具执行独立区域实时监控 | CodeBuddy 官方 Web UI 文档 |
| GitHub Copilot Chat | 反例：Mermaid 块闭合后仍每帧重绘闪烁 | issue #319248 |

产品侧共性收敛为 7 项要求：未闭合语法容错、增量渲染、代码块增强、表格可用性、数学/Mermaid 后置渲染、安全 sanitize、长文性能。

### 2.3 开源组件横向对比

| 方案 | 定位 | 流式容错 | 生态/框架 | 适配度 |
| --- | --- | --- | --- | --- |
| `markdown-it` | 通用解析器 + 插件体系 | 无内建容错 | 框架无关 | **高**：已在使用 |
| `streamdown`（Vercel） | react-markdown 流式替代品 | 内置未闭合块解析（`remend`） | React 专用 | 低，思路可借鉴 |
| `streaming-markdown` | 流式解析器，直接 `appendChild` | 原生流式状态机 | 框架无关 | 中 |
| `markstream` | 面向 LLM 的流式渲染器 | 未闭合代码块/表格/KaTeX/HTML | 支持 Vue | 中，能力重叠 |
| `react-markdown` | unified/remark/rehype AST 渲染 | 无内建容错 | React 专用 | 低 |
| `prompt-kit` ResponseStream | typewriter / fade 双模式组件 | 无 | React | 中，模式设计可借鉴 |
| `FlowToken` | 8 种动画模式（fade/blur/drop/pull-up…） | 无 | React | 中，动画枚举可借鉴 |
| `streaming-text-animator` | 逐字符淡入 | 无 | 框架无关（含 Vue 示例） | 中 |
| `shiki` | TextMate 高亮 | 与流式无关 | 框架无关 | 中，体积大 |
| `highlight.js` | 轻量高亮 | 与流式无关 | 框架无关 | **高**：已在使用 |
| `starry-night` | GitHub 同款高亮 | 与流式无关 | 框架无关 | 低-中 |
| `KaTeX` | LaTeX 同步渲染 | 与流式无关 | 框架无关 | 中 |
| `DOMPurify` | DOM sanitize | 与流式无关 | 框架无关 | **高**：当前缺失 |

### 2.4 权威最佳实践（Chrome 官方）

Chrome for Developers《呈现流式 LLM 回答的最佳实践》（2025-01-21）：

1. **不要每帧 `innerHTML` 全量替换**：会把此前所有块 + 新块重新解析并重渲染，代码高亮开销被反复支付。
2. **优先 `append()` 增量追加**：只追加节点，不销毁已有节点。
3. **必须 sanitize**：模型输出等价用户输入；且必须对**合并后的全量内容** sanitize，不能逐块 sanitize。
4. **使用流式解析器**：单独处理每个 chunk，含义明确前保留未定状态。

### 2.5 Coding 场景的工程实践

Coding Agent 是流式渲染压力最大的场景（长代码块、diff、表格、工具输出、多轮长会话）。

#### 2.5.1 Codex CLI：分块缓存 + 脏区重绘

v0.145 之前每次更新全量重解析重渲染，长会话直接崩：50+ 轮后每帧开销线性增长（issue #30007 中英混排宽度重算）、打字延迟与偶发退出（issue #18693）、流式开头行被吞且 scrollback 不可恢复（issue #11031）。

v0.145（2026-07-21）三项改造：

| 改造 | 做法 | 对应本方案 |
| --- | --- | --- |
| Segment 身份追踪 | 每轮输出/工具输出/用户消息分配稳定 content hash，完成后缓存渲染结果 | `RenderCache` |
| 脏区检测 | 只重解析 hash 变化的 segment，已完成轮次永不复处理 | `BlockSplitter` |
| 增量追加 | 流式期间只解析「上一帧以来的 delta」 | `TailCompleter` |

配套：批量合并转义序列、跨 segment 缓存 ANSI 状态。安全上，模型输出的链接必须过 **URI 白名单**才生成超链接。

#### 2.5.2 Claude Code：声明式 + 差量更新

自研 Ink 引擎（React → 终端），理由是界面属**多区域独立刷新**而非静态文本流；声明式只需描述状态，Reconciler 自动增量更新。可类比到 Web 的手段：

| 手段 | Web 端类比 |
| --- | --- |
| screen buffer 差量更新 | 稳定块不重建 |
| 字符 interning（整数比对） | 块级 hash 比对 |
| `Int32Array` 紧凑存储 | 避免每帧重建大对象树 |
| 输出序列合并 | 合并同类 DOM 更新 |
| 帧率控制 | `requestAnimationFrame` 合帧 |
| 分层 Context | 拆分「消息数据」与「动画状态」 |
| 行宽缓存 | 表格列宽缓存 |

#### 2.5.3 CodeBuddy / WorkBuddy

CodeBuddy Code 通过 `--serve --port 7890` 提供 Web UI，与终端共享同一事件流；Chat 区实时监控工具执行；支持 Standard / PTC / Minimal / Create 模式切换。WorkBuddy 以桌面工作站形态为主，渲染细节未公开。

#### 2.5.4 Copilot Chat 反例

issue #319248：` ```mermaid ` 块闭合后，后续 token 仍让图表每帧重绘闪烁到整条消息结束。说明**「块已闭合」≠「消息已稳定」**，必须块级隔离。markstream 的解法是 `contentStableDelayMs: 500` + `renderDebounceMs: 180` + Web Worker。

#### 2.5.5 共性结论

| # | 规则 | 依据 |
| --- | --- | --- |
| 1 | 块级 hash 缓存 + 脏区重绘，已完成块永不复处理 | Codex v0.145 |
| 2 | 流式期间只解析 delta | Codex 增量追加、Chrome 官方 |
| 3 | 长会话瓶颈来自少数超长历史，需分页/裁剪 | Codex #18693 |
| 4 | 输出区不能吞块，滚动缓冲完整 | Codex #11031 |
| 5 | 重渲染内容延迟到稳定后 + 防抖 | Copilot #319248、markstream |
| 6 | 链接过 URI 白名单 | Codex 安全边界 |
| 7 | 渲染层与事件协议解耦，可驱动终端/Web 双形态 | CodeBuddy、Claude Code |

## 三、AgentForge 设计：可切换多模式的 Markdown 渲染器

### 3.1 目标定位

对外只提供一个 **`AfMarkdown` 流式 Markdown 渲染器组件**。组件内核负责内容正确性、安全和性能；呈现策略只决定「文字如何出现」，不影响「Markdown 如何解析」。

| 原则 | 落地要求 |
| --- | --- |
| 不换解析器 | 继续用 `markdown-it`，避免引入 React 生态依赖，减少回归面 |
| 按块渲染 | 把正文拆成"已完成块 + 正在生成的尾块"，已完成块缓存 HTML，只重算尾块 |
| 尾块补全 | 对未闭合 fence、粗体、表格、链接做**闭合补全**，消除裸露符号 |
| 高亮解耦 | 高亮只在代码块**闭合后**执行一次，流式期间用纯文本 + 轻量样式 |
| 安全兜底 | `html:false` 保持；输出前经 `DOMPurify`（或等价白名单）过滤 |
| 性能分级 | 短回答直接渲染；超过阈值（如 8KB）启用分块缓存与 `requestAnimationFrame` 合帧 |

组件核心能力：

| 能力 | 保证 |
| --- | --- |
| Markdown 基础语法 | 标题、段落、强调、引用、链接、分隔线、有序/无序/任务列表 |
| 结构化内容 | 表格、行内代码、围栏代码块，未闭合语法不破坏页面 |
| 数学与图表 | 行内/块级公式闭合后交给 KaTeX；Mermaid 在块稳定后渲染 |
| 流式呈现 | 仅提供 `instant` / `typewriter` / `typewriter-blur` 三种模式 |
| 布局稳定 | 已完成块不重绘；流式态与完成态的排版、间距和最终 DOM 语义一致 |
| 安全 | `html:false` + DOMPurify + URL 协议白名单 |
| 可观测 | 渲染耗时、解析次数、缓存命中和丢帧指标 |

### 3.2 分层架构

```text
MarkdownView（对外不变）
  ├─ MdStreamRenderer   流式分块与补全（新增）
  │     ├─ BlockSplitter     已完成块 / 尾块切分
  │     ├─ TailCompleter     未闭合语法补全
  │     └─ RenderCache       已完成块 HTML 缓存
  ├─ MarkdownIt 实例（单例，插件统一注册）
  └─ Sanitizer               DOMPurify 白名单
```

对外 Props 保持兼容，新增可选参数：

| Prop | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `content` | String | '' | 全量正文（不变） |
| `streaming` | Boolean | false | 是否处于流式过程中；true 时启用尾块补全与合帧 |
| `plainCode` | Boolean | true | 流式期间代码块不做高亮，闭合后再高亮 |

### 3.3 分块算法（正确性内核）

以"稳定块边界"切分：空行分隔的顶层块视为已完成；最后一个块为尾块。

```text
输入: content（全量）
1. 按 /\n{2,}/ 切分顶层块，保留分隔符位置
2. 判定尾块是否稳定：
   - 尾块包含未闭合 ``` → 不稳定
   - 尾块是半行表格（上一行是分隔行）→ 不稳定
   - 尾块以 - / * / 1. 开头且未见换行结束 → 不稳定
   - 其余视为稳定
3. 稳定块 → 计算 HTML 并写入缓存（按块内容 hash 做 key）
4. 尾块 → 走 TailCompleter 补全后渲染，不写缓存
输出: 稳定块 HTML 数组 + 尾块 HTML
```

渲染层用 `v-for` 输出稳定块（`v-html` 只设置一次，内容不变则 Vue 不触碰该节点），尾块单独一个容器，仅它随帧更新。

### 3.4 未闭合语法补全规则

| 场景 | 检测 | 补全 |
| --- | --- | --- |
| 代码围栏 | 尾块内 ` ``` ` 计数为奇数 | 追加 ` ``` `（保持已输入语言标记） |
| 行内代码 | 尾块内反引号计数为奇数 | 追加一个反引号 |
| 粗体/斜体 | `**` / `*` / `__` / `_` 计数为奇数 | 追加对应的闭合标记 |
| 删除线 | `~~` 计数为奇数 | 追加 `~~` |
| 链接/图片 | `[` `]` `(` `)` 配对不完整 | 尾块末尾直接丢弃未成对的 `[` 之后片段（不补全，避免生成错误 href） |
| 表格 | 尾块仅含表头与分隔行 | 追加一个空数据行，保证列宽稳定 |
| 数学 | `$` / `$$` 定界符未成对 | 未闭合时保留 LaTeX 原文；定界符闭合且块稳定后再渲染 KaTeX |

补全只在**渲染副本**上做，绝不回写 `content`，避免污染后续真实 token。

### 3.5 代码块渲染增强

代码块是 Agent 回答里视觉权重最高、也最容易在流式中跳变的部分：

| 增强项 | 做法 |
| --- | --- |
| 语言标签 | 读取 fence info，右上角显示，未知语言显示 `text` |
| 复制按钮 | 点击复制原始代码（从 token 的 `content` 取，不用高亮后的 HTML） |
| 流式不闪烁 | `streaming=true` 且块未闭合时使用 `<pre class="code-plain">`，闭合后替换为高亮版本并加过渡 |
| 自动换行 | 长行折行，避免横向滚动条在回答中段出现 |
| 行号 | 可选（`showLineNumbers`），按 `\n` 计数生成 gutter |
| 折叠 | 超过 N 行（如 40）默认折叠，展示"展开全部" |

高亮调用时机：只在块进入"稳定"状态时执行一次 `hljs.highlight()`，并把结果缓存到 `RenderCache`。

### 3.6 表格渲染增强

| 问题 | 方案 |
| --- | --- |
| 列宽抖动 | 流式期间给表格外层固定 `table-layout: fixed` + 最小列宽，闭合后再恢复自适应 |
| 横向溢出 | 外层 `overflow-x: auto` + 细滚动条，避免撑破气泡 |
| 单元格内 Markdown | 目前 markdown-it 默认不解析单元格内块级语法，保持现状；行内语法保留 |
| 表头对齐 | 解析 `:---:` 对齐标记，输出 `text-align` |

### 3.7 排版与视觉规范

| 元素 | 规范 |
| --- | --- |
| 正文字号/行高 | 14px / 1.72（保持），段落间距 8px |
| 标题 | h1 20px / h2 18px / h3 16px，h1/h2 带下边框，与正文间距 16px/8px |
| 列表 | `padding-left: 22px`，嵌套列表缩进 16px，任务列表复选图标对齐首行 |
| 引用块 | 左侧 4px 主色边框 + 浅底，内部段落不留额外外边距 |
| 行内代码 | 浅灰底 + 4px 圆角，避免与正文粘连（前后各 2px） |
| 分割线 | 1px 浅灰，上下 14px |
| 图片 | `max-width: 100%`，圆角 8px，带 1px 边框；加载失败占位 |
| 链接 | 主色 + 下划线仅 hover 显示，`target=_blank` + `rel=noopener noreferrer` |
| 流式光标 | 尾块末尾追加闪烁光标（`::after` 实现，不污染文本节点） |

#### 3.7.1 流式态与完成态布局一致性

`BlockSplitter` 会把消息渲染成相邻的 `stable` 和 `tail` 容器，而 `resp_end` 后会把全文合并为一个最终容器。这两种 DOM 形态必须具有相同的可见布局。

**已复现的问题**：流式时，尾块内的段落是该 `.md` 容器的 `:first-child`，通用样式 `.md > *:first-child { margin-top: 0 }` 会清掉它的上间距；流式结束后，该段落合并进全文 DOM，不再是首元素，于是恢复 `margin-top: 8px`，用户会看到段落在结束瞬间跳位。`typewriter` 和 `typewriter-blur` 共用尾块渲染路径，因此都会出现，与 blur 动画无关。

```text
流式中： stable.md + tail.md（尾段落被误当成容器首元素）
完成后： final.md（尾段落是全文中的普通元素）
```

实现必须遵守：

1. 当 `stable` 非空时，必须为 `tail` 的首个块级元素恢复它在单容器中应有的上间距；段落/列表、标题、引用/代码/表格、公式块分别按自身规范处理。
2. 已完成块和尾块必须复用同一套 typography token（字号、行高、margin、padding、border），不允许为动画模式单独改块尺寸。
3. 页面预留纵向滚动条空间（`scrollbar-gutter: stable`），流式期间关闭输出区滚动锚定（`overflow-anchor: none`），避免容器变宽或视口自动补偿。
4. `finish()` 的全量渲染只做临时动画/光标清理和最终语法收口，不应产生可见位置变化。

验收方法：在 `resp_end` 前后分别记录尾块首元素的 `getBoundingClientRect()`，`top` / `left` 偏差应小于 1px，页面不得出现肉眼可见的跳动。

### 3.8 性能与安全

| 项 | 措施 |
| --- | --- |
| 合帧 | 用 `requestAnimationFrame` 合并同一帧内的多个 `resp` 片段，最多每帧渲染一次 |
| 缓存 | 稳定块 HTML 按内容 hash 缓存，历史重放（`events` 回放）同样命中 |
| 长文 | 超过 8KB 或 200 块启用分页渲染（每帧只新增 1 块） |
| 安全 | 保留 `html:false`；输出统一经 `DOMPurify.sanitize`，白名单允许 `table/pre/code/a/img` 等；链接协议仅 `http/https/mailto` |
| 注入防护 | 对 `javascript:`、`data:` 协议链接直接丢弃；图片 `onerror` 类属性天然被 `html:false` + sanitize 拦截 |
| 内存 | 单条消息渲染完成后释放尾块补全中间态，只保留最终 HTML |

#### 3.8.1 正文链接新标签页打开的落地机制

需求：AI 正文中的链接（含 `[文本](url)` 与 `linkify` 自动识别的裸 URL）在**发布页**与**Agent 调试页**点击后，均以新标签页打开，不打断当前对话。

这两处页面共用同一条渲染链路：

```text
PublishedAgentChatPage / AgentDebugPanel
  → AgentMessageContent
  → 对应 render mode 组件
  → MarkdownView
```

因此只需在 `MarkdownView` 内统一处理，即可覆盖两种场景，无需各页面单独改造。

**第一层：解析阶段注入属性。** markdown-it 的 `link_open` 规则同时服务于显式链接与 `linkify` 自动链接，在此统一写入 `target` 与 `rel`：

```js
const defaultLinkOpen = md.renderer.rules.link_open
  || ((tokens, idx, options, env, self) => self.renderToken(tokens, idx, options))

md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  tokens[idx].attrSet('target', '_blank')
  tokens[idx].attrSet('rel', 'noopener noreferrer')
  return defaultLinkOpen(tokens, idx, options, env, self)
}
```

`rel="noopener noreferrer"` 用于切断新页面对 `window.opener` 的访问，避免反向控制与 Referer 泄露。

**第二层：sanitize 阶段保留属性（易踩坑）。** DOMPurify 的 `DEFAULT_ALLOWED_ATTR` 包含 `href`、`rel`，但**不包含 `target`**。若净化配置未显式放行，`link_open` 刚写入的 `target` 会在 `DOMPurify.sanitize` 后被静默移除，表现为"代码里设了 `_blank`，实际仍在当前页打开"。因此必须通过 `ADD_ATTR` 放行：

```js
const sanitize = (html) => DOMPurify.sanitize(html, {
  USE_PROFILES: { html: true, svg: true, mathMl: true },
  ADD_ATTR: ['target']
})
```

**链路顺序**为：解析注入 `target`/`rel` → 合并稳定块与尾块 → 全量 sanitize（放行 `target`）→ 写入 DOM。流式过程中的尾块与最终完成态复用同一套解析器与净化配置，因此动画结束前后链接行为一致。

**验收**：

- 发布页与调试页回答中的显式链接、裸 URL 均在新标签页打开；
- 新标签页 `window.opener` 为 `null`；
- `[x](javascript:alert(1))` 等注入样例仍被拦截，净化后节点不含可执行协议；
- 历史消息重放（`events` 回放）与实时输出链接行为一致。

### 3.9 与后端事件的配合

当前 `resp` 事件只带文本增量，前端无法区分"块边界"。两种可选增强（不影响本次前端改造）：

| 方案 | 说明 | 优先级 |
| --- | --- | --- |
| 纯前端推断 | 本文设计，完全基于 `content` 增量推断块边界 | P0（先做） |
| 后端补 `resp` 元信息 | 在 `StreamEventResponse.content` 增加 `contentType`（`text`/`code`/`table`）与 `blockId`，前端定向更新 | P2（后续） |

`resp_end` 表示本轮结束：前端在收到后做一次"最终渲染"（全量解析 + 高亮 + sanitize），确保与历史重放结果一致。


### 3.10 多模式渲染器设计（AfMarkdown）

#### 3.10.1 能力矩阵（只保留适合 Agent Chat 对话的模式）

Agent Chat 的正文特点是：**长文为主、连续阅读、夹杂代码块与表格、单条消息可能上万字**。据此按三条硬标准筛选：① 不破坏 Markdown 结构；② 长文连续阅读不疲劳；③ 性能可控。

| 模式 | 一句话定位 | 视觉 | 作用层级 | 适配 Agent Chat 的理由 | 阶段 |
| --- | --- | --- | --- | --- | --- |
| `instant` | 基线：token 到达即渲染 | 无动画 | 内核直出 | 调试面板需要真实到达节奏；所有模式的降级兜底 | P0 |
| `typewriter` | **对话感**：逐字匀速 + 光标 | 逐字打出 | 尾块字符级 | 最贴近「AI 正在回答」心智；零分词依赖，中文一致 | P1 |
| `typewriter-blur` | **生成感**：逐字 + 每字模糊渐清 | 从左到右逐字显影 | 尾块字符级 + 单字入场动画 | 即「从左到右、由模糊到清晰」；观感最好，成本可控 | P2 |

对外能力到此收口：只暴露上述三个模式；内部保留 `StreamStrategy` 扩展点，但本期不再设计或实现其他动画模式。

#### 3.10.1.1 模式设计详解

每个模式按「触发单元 → 时序 → 动画 → 参数 → 适用/不适用」描述，便于直接实现。

##### `instant` 基线模式

| 项 | 内容 |
| --- | --- |
| 触发单元 | 服务端 chunk（收到即渲染） |
| 时序 | 无缓冲，`content` 变化 → 立即重渲染尾块 |
| 动画 | 无 |
| 参数 | 无 |
| 适用 | 调试面板（需要观察模型真实到达节奏）、排障、自动化测试基线 |
| 不适用 | 正式对话页（成块蹦出、节奏不均，观感最差） |
| 实现要点 | 仍走 `BlockSplitter` + `RenderCache`，只是策略层直通；不可退化成"整条消息 `v-html` 覆盖" |

##### `typewriter` 打字机模式（默认）

| 项 | 内容 |
| --- | --- |
| 触发单元 | 单个字符（按 code point） |
| 时序 | `buffer` 累积 token → `requestAnimationFrame` 每帧按 `charDelay` 推进 `visibleLen` → 只渲染 `content.slice(0, visibleLen)` |
| 动画 | 尾块末尾 `.af-caret`（竖线，`aria-hidden`，2s 无新内容时停止闪烁） |
| 参数 | `charDelay`（默认 **40ms**）、`caret`（默认 true）、`maxLag`（积压超过 N 字时加速，默认 **60**） |
| 适用 | 正式对话页默认；中英文混排；要求"AI 正在回答"心智的场景 |
| 不适用 | 超长回答（>5000 字）——逐字推进会让用户等待过久，应切 `instant` |
| 实现要点 | 按 `Array.from()` 切分避免切坏 emoji；`maxLag` 触发时按比例提速，保证不落后于网络 |

##### `typewriter-blur` 逐字显影模式

即「从左到右、由模糊到清晰」。

| 项 | 内容 |
| --- | --- |
| 触发单元 | 单个字符（在 `typewriter` 基础上叠加单字入场动画） |
| 时序 | 字符推进同 `typewriter`；**每帧只给最新 1~2 个字符**加入场动画，其余字符为终态 |
| 动画 | `@keyframes af-char-blur`：`filter: blur(0.9px) → blur(0)`、`opacity: 0.68 → 1` |
| 光标 | **不显示**；逐字模糊渐清已承担流式状态反馈，避免叠加 caret 干扰视觉 |
| 适用 | 需要「生成过程感」的产品形态；正文以短段落为主 |
| 不适用 | 低端设备、超长连续文本（降级为 `typewriter`） |

**参数最优解（Demo 实测调优后）**

| 参数 | 默认值 | 取值理由 |
| --- | --- | --- |
| `charDelay` | **40ms**（25 字/秒） | 接近中文舒适阅读节奏；12ms（83 字/秒）远超阅读速度，会「刷屏」 |
| 模糊半径 | **0.9px** | 仅做「柔化」，不是「糊」；3px 在 14px 字号上等于 21% 字号，视觉过重 |
| 起始透明度 | **0.68** | 字基本可读，避免「看不清 + 快速移动」的晕眩 |
| 模糊时长 | **70ms** | 与 `charDelay` 的比例 ≈ 1.75，最多 2 字同时模糊 |
| 单帧新增动画字符 | **≤ 2** | 硬约束，杜绝「整段一起糊」 |
| 位移 | **无** | `translateY` 会加重「画面在动」的不稳感 |
| 加速阈值 `maxLag` | **60 字** | 积压过多才加速，避免跳字 |

**必须遵守的两条约束（否则会出现残影/晕眩）**

1. **`blurDuration ≤ charDelay × 2`**：模糊收敛必须与出字节奏匹配。若 `duration` 远大于 `charDelay`（如 180ms vs 12ms），会有 8 个字同时处于模糊态，形成拖尾残影。
2. **每帧只对「最新 1~2 字」加动画，不要对「本帧新增的全部字符」加**：段落变长时若整段新增都加动画，会一次性糊一大片。

**实测对比（348 字样例）**

| 配置 | 同时处于模糊态的字符数 | 观感 |
| --- | --- | --- |
| `charDelay=12ms` / `blur=3px` / `duration=180ms` / 尾部 12 字 | 平均 4.4，峰值 13 | 一片模糊、拖影、晕眩 |
| `charDelay=40ms` / `blur=0.9px` / `duration=70ms` / 每帧 ≤2 字 | **平均 1.3，峰值 2** | 轻微显影，无残影 |

**实现要点**

- 仅对**尾块**字符加动画，稳定块直接终态，避免历史内容反复闪；
- 稳定块必须缓存（`BlockSplitter` + `RenderCache`），否则每帧整段重建会让「整行一起重绘」，即使动画参数正确也会显得画面在抖；
- 降级路径：设备掉帧或用户手动把 `charDelay` 调到 0 时，直接退化为 `typewriter`。

#### 3.10.2 策略接口

```js
/** 呈现策略：只负责「文本如何出现」，不参与解析与安全 */
export class StreamStrategy {
  name = 'instant'
  /** 是否需要视觉缓冲 */
  get buffered() { return false }
  /**
   * 把已渲染的块交给策略包装
   * @param {HTMLElement} host     块容器
   * @param {'stable'|'tail'} kind 块类型
   * @param {boolean} plainText    是否纯文本叶子（允许切词）
   */
  apply(host, kind, plainText) {}
  /** 新内容到达时推进动画 */
  tick(deltaText) {}
  /** 结束：清理动画与光标 */
  finish() {}
}
```

内置策略实现要点：

| 策略 | `buffered` | 实现要点 |
| --- | --- | --- |
| `InstantStrategy` | false | 直接渲染，无动画 |
| `TypewriterStrategy` | true | 维护 `visibleLen`，`requestAnimationFrame` 按 `charDelay` 递进；按 code point（`Array.from`）推进，避免切坏 emoji；尾块追加 `.af-caret` |
| `TypewriterBlurStrategy` | true | 在 `TypewriterStrategy` 的逐字推进基础上，为最新 1~2 个字符加 `@keyframes af-char-blur`：`filter: blur(0.9px) → blur(0)` + `opacity: 0.68 → 1`，时长约 70ms；不创建 `.af-caret` |

#### 3.10.3 与内核的边界

```text
MarkdownView（对外唯一入口）
  ├─ AfMarkdownCore              ← 内核，与模式无关
  │     ├─ BlockSplitter            已完成块 / 尾块
  │     ├─ TailCompleter            未闭合语法补全
  │     ├─ MarkdownIt 单例          解析与高亮
  │     ├─ RenderCache              块 HTML 缓存
  │     └─ Sanitizer                DOMPurify 白名单
  ├─ StreamStrategy              ← 策略层，可插拔
  │     └─ Instant / Typewriter / TypewriterBlur
  └─ Theme                       ← 主题层（排版、配色、代码块样式）
```

硬性边界：

- 策略层**只能**在渲染完成的 DOM 上做动画包装，**不能**修改 Markdown 解析结果；
- 策略层**不得**引入新的 HTML 字符串拼接（避免绕过 sanitize）；
- 内核**不感知**当前模式；模式切换只重建策略实例，块结构与缓存保持不变（切模式不重新解析）；
- 所有动画只用 `transform` / `opacity` / `filter`，避免触发重排。

#### 3.10.4 组件 API 规划

| Prop | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `content` | String | '' | 正文（兼容现状） |
| `streaming` | Boolean | false | 是否流式中；false 走一次性完整渲染 |
| `mode` | String | `'instant'` | `instant` / `typewriter` / `typewriter-blur` |
| `charDelay` | Number | **40** | typewriter 每字符间隔（ms）；12 过快会刷屏，40 接近舒适阅读 |
| `charBlurDuration` | Number | **70** | `typewriter-blur` 单字模糊收敛时长（ms），应 ≤ `charDelay × 2` |
| `charBlurRadius` | Number | **0.9** | `typewriter-blur` 起始模糊半径（px） |
| `charBlurTail` | Number | **2** | `typewriter-blur` 每帧最多加动画的字符数 |
| `caret` | Boolean | true | 尾块是否显示光标，仅 `typewriter` 模式生效 |
| `plainCodeWhileStreaming` | Boolean | true | 流式期间代码块不做高亮 |
| `sanitize` | Boolean | true | 是否经 DOMPurify（仅测试可关） |
| `onMetrics` | Function | null | 回调渲染指标（见 3.10.6） |

事件：`render-complete`（本轮最终渲染完成）、`block-appended`（新增稳定块，便于上层滚动跟随）。

#### 3.10.5 降级矩阵

| 能力缺失 | 降级行为 |
| --- | --- |
| `requestAnimationFrame` 不可用（SSR/测试） | 直接输出终态，不做动画 |
| Worker 不可用 | Mermaid/KaTeX 主线程渲染 + 防抖 |
| `DOMPurify` 未加载 | 拒绝渲染富内容（仅输出纯文本），绝不放行 |
| `typewriter-blur` 下单个字符动画堆积 | 通过 `charBlurTail` 限制同时动画的字符数；仍掉帧则降级为纯 `typewriter` |

#### 3.10.6 可观测指标

| 指标 | 用途 |
| --- | --- |
| `lastRenderMs` | 单帧渲染耗时，超过 16ms 需合帧 |
| `blocksTotal` / `blockCacheHits` | 缓存有效性 |
| `parseCount` | 解析次数，验证「已完成块不重解析」 |
| `droppedFrames` | 合帧丢弃次数 |

#### 3.10.7 演进路线

| 阶段 | 目标 | 判定标准 |
| --- | --- | --- |
| V1 | 内核可用：分块 + 补全 + 缓存 + sanitize，`instant` | 未闭合语法不再裸露，长文不重排 |
| V2 | 模式可用：`typewriter` + `typewriter-blur` + caret | 观感平滑，中英文一致，结束无残留 |
| V3 | 重型内容：代码块/表格/Mermaid/KaTeX 的块级处理 | 图表不闪烁 |
| V4 | 规模化：长文分页、虚拟化、指标面板 | 100+ 轮会话不卡顿 |

V1/V2 是必须交付的最小闭环；V3 起按观感收益与维护成本评估是否继续。

### 3.11 最终渲染完成事件与操作区时序

#### 3.11.1 问题背景

后端的 `resp_end` 只表示 AI 正文传输结束，不等于用户已经看到最终 Markdown DOM。在 `typewriter` / `typewriter-blur` 模式下，网络流结束后可能仍有缓冲字符需要通过 `requestAnimationFrame` 排空，随后还要执行最终 Markdown 解析、代码高亮、sanitize 和 DOM 更新。

如果回答底部的复制、重试等操作按钮仅依赖 `message.status !== 'running'`，就会在正文仍在显影时提前出现，造成“回答已完成，文字却还在追赶”的时序割裂。

#### 3.11.2 事件定义

`MarkdownView` 在以下条件全部满足后发出 `render-complete`：

1. `streaming` 已变为 `false`；
2. 打字机缓冲已排空，`visibleLength` 与最终正文长度一致；
3. 已使用完成态解析器生成最终 HTML（包含代码高亮）；
4. `stableHtml` 已更新，`tailHtml` 已清空；
5. Vue `$nextTick` 完成，最终 DOM 已提交。

事件携带当次完成渲染的内容快照：

```js
this.$emit('render-complete', { content: renderedContent })
```

上层必须比较 `event.content === message.content`，只接受与当前正文一致的完成事件。这可以避免在快速切换会话、重试或内容继续追加时，旧的 `$nextTick` 回调误将新内容标记为已渲染。

#### 3.11.3 调试与发布模式的统一透传

两种展示模式共用同一条事件链：

```text
MarkdownView
  │ render-complete({ content })
  ▼
ElegantMessage（发布模式）/ DebugMessage（调试模式）
  │ 原样透传
  ▼
AgentMessageContent
  │ 校验内容快照
  ▼
显示回答底部操作区
```

`AgentMessageContent` 的显示规则为：

- `message.status === 'running'` 时始终隐藏操作区；
- 有正文时，仅在收到与当前 `message.content` 匹配的 `render-complete` 后显示；
- 无正文的错误消息没有 Markdown 可渲染，错误状态与操作区直接显示，避免永久等待不会发生的事件；
- 消息重新进入 `running` 时清空已渲染快照，防止重试时沿用上一轮状态。

#### 3.11.4 时序约束

```text
SSE resp_end
  → message.status = success/error
  → MarkdownView 进入 draining
  → 排空未显示字符
  → 完成态 Markdown 解析 + sanitize
  → Vue DOM commit（$nextTick）
  → render-complete
  → 回答底部按钮出现
```

这个设计将“传输完成”与“可视渲染完成”明确分离：前者由会话协议层负责，后者由 Markdown 渲染器负责，上层 UI 只消费稳定的完成事件。

## 四、实施计划

与 3.10.7 的 V1–V4 对齐，分两条线推进：**正确性线**（渲染得对）与**观感线**（看得舒服）。

| 阶段 | 线 | 内容 | 产出 |
| --- | --- | --- | --- |
| P0 | 正确性 | `AfMarkdownCore`：`BlockSplitter` + `TailCompleter` + `RenderCache`，`MarkdownView` 增加 `streaming` / `mode` 参数 | 未闭合语法不再裸露，长文不再整段重排 |
| P1 | 正确性 | 代码块增强（语言标签/复制/折叠/流式不高亮）；表格列宽稳定 | 代码块与表格稳定 |
| P2 | 观感 | `TypewriterStrategy`（字符级匀速 + caret）+ `TypewriterBlurStrategy`（逐字渐清，参数见 3.10.1.1） | 出场节奏平滑，中英文一致，支持从左到右显影且无残影 |
| P3 | 正确性 | `DOMPurify` 接入 + 链接协议白名单；Mermaid/KaTeX 稳定后渲染 | 安全兜底、重块不闪烁 |
| P4 | 性能 | 长文分块渲染、合帧调优、指标面板 | 超长回答流畅 |

对齐 Coding 场景的 7 条规则（见 2.5.5）：

| Coding 场景规则（2.5.5） | 对应阶段 |
| --- | --- |
| 块级 hash 缓存 + 脏区重绘 | P0 |
| 只解析 delta，不重解析整条消息 | P0 |
| 长会话需分页/裁剪 | P4 |
| 输出区不吞块、滚动缓冲完整 | P0 |
| 重渲染内容延迟到稳定后 + 防抖 | P4 |
| 链接过 URI 白名单 | P4 |
| 渲染层与事件协议解耦 | P0（`MarkdownView` 只吃 `content` / `streaming` / `mode`） |

验收标准：

- 流式中途不出现裸露的 `**`、` ``` `、`[`、竖线表格残片；
- 长回答（>8KB）滚动不抖动，代码块高亮只在闭合后计算一次；
- 历史消息重放（`events` 中连续的 `resp`）与实时渲染最终结果完全一致；
- 注入样例（`<img src=x onerror=alert(1)>`、`[x](javascript:alert(1))`）不产生任何执行或畸形节点；
- 正文链接（含 `linkify` 裸 URL）在发布页与调试页均以新标签页打开，且 `rel="noopener noreferrer"` 生效；
- `typewriter` 模式下文字出现节奏均匀，不出现「一次蹦出几十字再停顿」；
- 消息结束后光标消失，无残留动画；
- `typewriter-blur` 下同一时刻处于模糊态的字符数 ≤ 2，无拖影/晕眩；
- `typewriter` 出字节奏 ≥ 25ms/字（不快于 40 字/秒）；
- 切换 `mode` 不触发重新解析（`parseCount` 不变），块结构与缓存保持；
- 同一段内容在 `instant` / `typewriter` / `typewriter-blur` 下最终渲染结果完全一致。

## 五、参考来源

### 5.1 官方最佳实践与规范

| 来源 | 链接 | 引用点 |
| --- | --- | --- |
| Chrome for Developers《呈现流式 LLM 回答的最佳实践》 | https://developer.chrome.com/docs/ai/render-llm-responses | innerHTML 全量替换的性能/安全问题、`append()` 增量、全量 sanitize、流式解析器 |
| CommonMark 规范 | https://spec.commonmark.org/ | 块级/行内语法边界定义 |
| GitHub Flavored Markdown 规范 | https://github.github.com/gfm/ | 表格、任务列表、删除线扩展语义 |

### 5.2 解析与渲染组件

| 来源 | 链接 | 引用点 |
| --- | --- | --- |
| markdown-it（22k stars，MIT） | https://github.com/markdown-it/markdown-it | 当前使用的解析器；插件体系与 `highlight` 钩子 |
| markdown-it 语法插件文档 | https://markdown-it.github.io/markdown-it/documents/Syntax_plugins.html | 表格/删除线内建，脚注/emoji/上下标需插件 |
| mdit-plugins 插件集 | https://mdit-plugins.github.io/ | `tasklist`/`katex`/`footnote`/`container`/`anchor` 等可选增强 |
| Vercel streamdown（5.7k stars） | https://github.com/vercel/streamdown | 流式场景的未闭合块解析（`remend`）、GFM、KaTeX、Mermaid、Shiki、`rehype-harden` 安全模型 |
| streaming-markdown | https://github.com/thetarnav/streaming-markdown | 原生流式状态机，`appendChild` 增量输出 |
| markstream | https://markstream.simonhe.me/use-cases/incomplete-markdown-renderer | 面向 LLM 的未闭合代码块/表格/KaTeX 渲染，官方 Vue 集成 |
| react-markdown（15.9k stars，MIT） | https://github.com/remarkjs/react-markdown | unified/remark/rehype 流水线与 AST 渲染思路（`rehype-sanitize` 安全模型） |

### 5.3 高亮与主题

| 来源 | 链接 | 引用点 |
| --- | --- | --- |
| highlight.js（25k stars，BSD-3） | https://github.com/highlightjs/highlight.js | 当前高亮实现；`highlight` / `highlightAuto` / Web Worker 用法 |
| Shiki（13.9k stars，MIT） | https://github.com/shikijs/shiki | TextMate 语法高亮，质量最高但体积大 |
| starry-night（1.8k stars，MIT） | https://github.com/wooorm/starry-night | GitHub 同款高亮的开源实现，输出 class + 官方主题 CSS |
| github-markdown-css（8.9k stars，MIT） | https://github.com/sindresorhus/github-markdown-css | GitHub Markdown 视觉基线，可作为排版对照 |

### 5.4 数学与图表

| 来源 | 链接 | 引用点 |
| --- | --- | --- |
| KaTeX（20.5k stars，MIT） | https://github.com/KaTeX/KaTeX | 同步渲染、无重排、可 SSR，适合流式后置渲染 |

### 5.5 安全

| 来源 | 链接 | 引用点 |
| --- | --- | --- |
| DOMPurify | https://github.com/cure53/DOMPurify | Chrome 官方推荐的两大 sanitizer 之一 |
| sanitize-html | https://github.com/apostrophecms/sanitize-html | 服务端/构建期 sanitize 备选 |

### 5.6 流式呈现模式与动画

| 来源 | 链接 | 引用点 |
| --- | --- | --- |
| Upstash《Smooth Text Streaming in AI SDK v5》 | https://upstash.com/blog/smooth-streaming | 网络流与视觉流解耦；缓冲 chunks + 按固定节奏吐字；typewriter 速度经验值 5ms/字 |
| AI SDK `smoothStream` 官方文档 | https://ai-sdk.dev/docs/reference/ai-sdk-core/smooth-stream | `delayInMs`（默认 10ms）、`chunking: word/line/RegExp/回调`；**明确说明 word 分词对中日韩/越南语/泰语效果差**，推荐 `Intl.Segmenter` |
| prompt-kit `ResponseStream` | https://www.prompt-kit.com/docs/response-stream | `mode: typewriter / fade`、`speed`、`fadeDuration`、`segmentDelay`、`characterChunkSize`；文档明确 `fade` 与 Markdown 结合困难、偏实验性 |
| FlowToken（Ephibbs/flowtoken） | https://github.com/Ephibbs/flowtoken | 动画枚举：fadeIn / blurIn / dropIn / slideFromLeft / typewriter / wordPullUp / flipText / gradualSpacing；`AnimatedMarkdown` + `sep: word/char` |
| streaming-text-animator（Kutius） | https://github.com/Kutius/streaming-text-animator | `charDelay`、`animationDuration`、逐字符 fade-in；自带 Vue 3 playground |
| We.Inc 流式文本组件 | https://we.inc/components/ai-chat/streaming-text-response-with-typewriter-and-word-fade-modes | typewriter（带 caret）与 word-fade 双模式可切换 + 速度滑块的产品化示例 |
| maud-ui `streaming_cursor` | https://maudui.herman.engineer/streaming_cursor | 三种状态指示：Cursor（闪烁光标）/ Dots（思考点）/ Pulse（脉冲环）；`aria-hidden` 无障碍处理 |
| w3tweaks《ChatGPT-Style Streaming Text Effect》 | https://www.w3tweaks.com/javascript/how-to-build-chatgpt-streaming-text-effect-javascript/ | 前端渲染路径与光标实现；强调效果与后端无关、纯前端技术 |
| streamui Streaming Text | https://streamui.dev/docs/components/streaming-text | 流式文本 + 动画光标的基础用法 |
| SmoothUI Typewriter Text | https://smoothui.dev/docs/components/typewriter-text | 逐字符揭示的打字机实现（含循环/速度参数） |
| cssscript 打字机动画库汇总 | https://www.cssscript.com/best-text-animation-libraries/ | 10 个文本动画库清单；纯 CSS `steps()`、JS 逐字、ReadableStream、EventSource 四条实现路径对比 |

### 5.7 中文分词与高成本块

| 来源 | 链接 | 引用点 |
| --- | --- | --- |
| MDN `Intl.Segmenter` | https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Intl/Segmenter | 按 grapheme/word/sentence 做 locale 敏感切分；自 2024-04 起跨浏览器可用 |
| caniuse `Intl.Segmenter` | https://caniuse.com/mdn-javascript_builtins_intl_segmenter | 实测版本：Chrome/Edge 87+、Safari 14.1+（iOS 14.5+）、Firefox 125+ |
| MDN 博客《Locale-sensitive text segmentation》 | https://developer.mozilla.org/en-US/blog/javascript-intl-segmenter-i18n/ | 中日韩等无空格语言的分词实践 |
| markstream《Streaming Mermaid and KaTeX》 | https://markstream.simonhe.me/use-cases/streaming-mermaid-katex | 渐进式渲染 Mermaid/KaTeX；`renderDebounceMs: 180`、`contentStableDelayMs: 500`；放进 Web Worker |
| VS Code issue #319248 | https://github.com/microsoft/vscode/issues/319248 | Copilot Chat 中 Mermaid 块闭合后仍每帧重绘闪烁的真实缺陷，印证"整条消息重渲染"的代价 |

### 5.8 Coding 场景流式渲染

| 来源 | 链接 | 引用点 |
| --- | --- | --- |
| Codex CLI v0.145 终端渲染管线复盘 | https://codex.danielvaughan.com/2026/07/26/codex-cli-v0145-terminal-rendering-pipeline-incremental-markdown-inline-visualization-performance/ | segment 内容 hash 缓存、脏区检测、增量 append；batch 转义序列与 ANSI 状态缓存；可视化链接 URI 白名单 |
| OpenAI Codex 官方仓库 | https://github.com/openai/codex | TUI 与 CLI 实现主体（`pulldown-cmark` 解析 GFM → 终端输出） |
| Codex issue #18693 | https://github.com/openai/codex/issues/18693 | 长会话性能崩塌的真实案例：瓶颈来自"少数超长历史"，表现为打字延迟、滚动卡顿、偶发退出 |
| Codex issue #11031 | https://github.com/openai/codex/issues/11031 | 流式输出开头行被吞、scrollback 不可恢复（渲染正确性缺陷） |
| Codex issue #44956 | https://github.com/openai/codex/issues/44956 | 无换行的连续 token 流导致 TUI "Working" 卡住不渲染 |
| Claude Code UI 渲染系统拆解 | https://yufji.github.io/claude-code-design-guide/en/architecture/12-UI%E6%B8%B2%E6%9F%93/ui-rendering.html | 自研 Ink 引擎、React Fiber Reconciler、screen buffer 差量更新、字符 interning、`Int32Array` 紧凑存储、帧率控制、行宽缓存 |
| Claude 流式 UI 工作原理 | https://liuyaowen.cn/en/posts/web-typescript/20260317 | "模型持续输出宿主可消费的 UI 协议"而非一段 HTML；流式顺序约束与禁用会破坏流式体验的视觉效果 |
| CodeBuddy Code 官方文档 · Web UI | https://www.codebuddy.ai/docs/cli/web-ui | `--serve --port 7890` 提供浏览器 UI；同一 Agent 能力对终端与 Web 双形态；Chat 实时监控工具执行 |
| CodeBuddy CLI 产品页 | https://www.workbuddy.ai/cli/ | CodeBuddy Code 定位"深度编码、终端速度"，终端内集成模型能力 |
| WorkBuddy 官方 FAQ | https://www.workbuddy.ai/docs/workbuddy/From-Beginner-to-Expert-Guide/FAQ | 桌面工作站形态、自主规划与执行任务（渲染细节未公开，仅作场景参考） |
| Copilot Chat issue #319248 | https://github.com/microsoft/vscode/issues/319248 | Mermaid 块闭合后仍每帧重绘闪烁，证明"块闭合≠消息稳定"，必须块级隔离 |
| markstream 流式 Mermaid/KaTeX | https://markstream.simonhe.me/use-cases/streaming-mermaid-katex | `renderDebounceMs: 180`、`contentStableDelayMs: 500`、Web Worker 承载重渲染 |

> 说明：以上链接均在调研时实际访问确认（通过 OpenReach `search` / `read`），组件能力描述取自各项目 README、官方文档或 issue 原文。`streamdown`、`react-markdown`、`flowtoken`、`prompt-kit`、`streaming-text-animator` 均为 React 生态，本项目为 Vue 2，**仅借鉴其模式划分、节奏控制与安全设计，不直接引入依赖**；`Intl.Segmenter`、`Web Worker`、`requestAnimationFrame` 为浏览器原生能力，可直接使用。

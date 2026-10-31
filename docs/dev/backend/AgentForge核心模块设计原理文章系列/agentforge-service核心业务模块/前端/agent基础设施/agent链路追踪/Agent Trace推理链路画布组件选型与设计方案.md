# Agent Trace 推理链路自研画布设计方案

## 1. 文档结论

AgentForge Agent Trace 流程图最终采用自研画布，不引入 X6、Vue Flow、Cytoscape、Dagre 等图组件或布局组件。

自研画布基于 Vue 2、DOM、SVG、CSS Transform 和 Pointer Events 实现：

1. DOM 负责渲染节点卡片和 Loop 分组框。
2. SVG Path 负责渲染贝塞尔连线和方向箭头。
3. CSS Transform 负责整个画布的缩放和平移。
4. Pointer Events 负责拖拽画布。
5. Wheel Event 负责纵向浏览以及组合键缩放。
6. 确定性布局算法负责按 Trace 业务语义计算坐标。

该方案不追求通用流程编排能力，只服务于 Agent Trace 的只读展示，因此可以用较小的代码和构建开销获得更贴合业务的视觉效果。

## 2. 设计背景

一次完整 Agent Trace 可能包含几十轮 ReAct Loop。每轮 Loop 包含：

1. 一次 Model 调用。
2. 零个或多个 Tool；每个 Tool 节点同时包含输入和输出。
3. 多个 Tool 汇聚后进入下一轮 Model。

普通纵向 DOM 列表无法清晰表达分支和汇聚关系；通用图组件虽然功能完整，但会引入较大的依赖和默认交互体系，并增加视觉深度定制成本。Agent Trace 的拓扑高度稳定，更适合确定性自研布局。

## 3. 目标结构

每个 Loop 使用独立虚线圆角框：

~~~text
用户请求
   |
┌────────────── Loop 1 ──────────────┐
│                Model               │
│          /       |       \         │
│      Tool A   Tool B   Tool C      │
│      入 / 出   入 / 出   入 / 出    │
└─────────┬────────┬────────┬────────┘
          \        |       /
┌────────────── Loop 2 ──────────────┐
│                Model               │
│                  |                 │
│                Tool D              │
│                  |                 │
│                入 / 出              │
└──────────────────┬─────────────────┘
                   |
                Round 完成
~~~

核心语义：

1. Model 居中展示。
2. 多个 Tool 横向等距排列。
3. 每个 Tool 卡片同时展示输入摘要和输出摘要。
4. Model 分别连接多个 Tool。
5. 多个 Tool 分别汇聚到下一轮 Model。
6. Loop 标签悬浮在虚线框上，使用“第一轮 Loop”形式展示中文轮次，同时展示耗时和工具数量。

## 4. 为什么选择自研

### 4.1 当前拓扑是确定的

Agent Trace 不是任意 DAG。节点顺序固定为：

~~~text
Round -> Model -> Tool -> 下一轮 Model -> Round Result
~~~

不需要通用自动布局、节点编辑、连线编辑、框选、撤销重做、剪贴板和流程编排能力。

### 4.2 视觉可完全控制

自研 DOM 节点可以直接使用项目现有 SCSS：

1. Model 使用紫色卡片。
2. Tool 使用蓝色卡片，卡片内以“入/出”区分参数与结果。
3. 异常工具使用红色状态。
5. 节点包含图标、标题、内容摘要、耗时标签、类型标签和状态点。
6. Loop 使用淡紫背景、虚线边框和悬浮胶囊标题。

### 4.3 引入开销最低

自研方案没有新增第三方运行时依赖。生产构建实测：

| 构建产物 | 原始大小 | gzip 大小 |
| --- | ---: | ---: |
| `AgentTracePage-*.js` 路由 chunk | 25.05 KB | 8.40 KB |
| 新增图组件 vendor | 0 KB | 0 KB |

此前评估的图组件及其运行时依赖已经从 package.json 和 package-lock.json 中移除。

## 5. 组件结构

~~~text
AgentTracePage.vue
  |
  +-- AgentTraceFlowView.vue
        |
        +-- AgentTraceGraphCanvas.vue  自研画布、节点、边、手势
        +-- utils/traceGraphAdapter.js Trace DTO 转换
        +-- utils/traceGraphLayout.js  确定性坐标计算
        +-- utils/traceFormat.js       耗时与 Token 紧凑格式化
~~~

### 5.1 AgentTraceFlowView

负责：

1. 画布工具栏。
2. 放大、缩小、恢复 100%、适配视图和全屏。
3. 节点详情面板。
4. 图例和操作提示。

### 5.2 AgentTraceGraphCanvas

负责：

1. 渲染 Loop 分组框。
2. 渲染 Model 和 Tool 卡片。
3. 渲染 SVG 贝塞尔连线与箭头。
4. 管理 `scale`、`tx` 和 `ty`。
5. 管理拖拽、滚轮、缩放、居中和适配视图。
6. 向上层抛出节点点击事件。

### 5.3 traceGraphAdapter

将后端 Round 和 Snapshot 转换成稳定图模型：

~~~javascript
{
  nodes: [],
  edges: [],
  groups: []
}
~~~

节点 ID 必须由 `questionRound + step + toolCallId` 稳定生成，不能使用随机 ID。

### 5.4 traceGraphLayout

负责计算：

1. 画布总宽高。
2. 主轴中心点。
3. 每个 Loop 的位置和尺寸。
4. Model 居中坐标。
5. Tool 横向坐标。
6. 下一 Loop 的纵向坐标。

## 6. 自研画布坐标模型

画布只保存三个视口变量：

~~~javascript
{
  scale: 1,
  tx: 0,
  ty: 0
}
~~~

画布统一应用：

~~~css
transform: translate(tx, ty) scale(scale);
transform-origin: 0 0;
~~~

节点保存模型坐标，不因缩放和平移而改变。SVG 和 DOM 节点放在同一个 transform 容器中，确保连线始终与节点同步。

## 7. 连线设计

每条边由起点节点底部中心连接到目标节点顶部中心：

~~~text
source = (source.x + source.width / 2, source.y + source.height)
target = (target.x + target.width / 2, target.y)
~~~

使用三次贝塞尔曲线：

~~~javascript
M sx sy
C sx (sy + dy), tx (ty - dy), tx ty
~~~

样式规则：

| 边类型 | 样式 |
| --- | --- |
| Model → Tool | 灰蓝色虚线曲线 |
| Tool → 下一 Model | 灰蓝色虚线汇聚曲线 |
| 异常边 | 红色虚线 |
| 普通主轴 | 灰蓝色实线 |

所有边在目标端显示方向箭头。

## 8. 交互设计

| 操作 | 行为 |
| --- | --- |
| 拖拽画布空白区域 | 平移整个画布 |
| 普通滚轮 | 上下或左右移动画布 |
| Ctrl/Cmd + 滚轮 | 以鼠标位置为中心缩放 |
| 放大/缩小按钮 | 以视口中心缩放 |
| 点击百分比 | 恢复 100% 并居中 |
| 适配视图 | 将完整链路缩放到可视区域 |
| 双击节点 | 将节点移动到视口中心 |
| 点击节点 | 打开详情面板 |

缩放范围为 40%～180%。

桌面端流程图与火焰图默认高度使用 `clamp(560px, 70vh, 920px)`，约占浏览器可视高度的 70%；移动端使用 65vh。进入全屏后画布占满扣除外边距后的可视区域。

## 9. 节点详情

节点卡片只展示摘要，完整内容在详情面板展示：

1. 节点类型。
2. 执行状态。
3. 开始时间与执行耗时。
4. Token 数量。
5. 工具调用参数。
6. 工具执行结果。
7. 异常信息。

每个工具快照只对应一个 Tool 节点。节点卡片同时展示输入和输出摘要，点击后在详情面板分别查看完整参数、结果和异常。

## 10. 长链路性能

几十轮 Loop 使用以下策略：

1. 不在节点内展示完整 JSON。
2. 参数和结果只截取一行摘要。
3. 完整内容仅在点击后由详情面板渲染。
4. 节点使用稳定 ID，后续可以做差量更新。
5. transform 只作用于画布根节点，不逐个修改节点坐标。
6. 拖拽过程中只更新 tx、ty。
7. 节点数量超过 300 时关闭阴影和 hover 位移动画。
8. 超过 1000 个节点时再引入分段加载，不引入通用图框架。

## 11. 文件落地

| 文件 | 职责 |
| --- | --- |
| `AgentTraceFlowView.vue` | 流程图工作区、工具栏、详情 |
| `AgentTraceGraphCanvas.vue` | 自研画布和节点视觉 |
| `utils/traceGraphAdapter.js` | 数据转换和前后关系 |
| `utils/traceGraphLayout.js` | Loop、Model、Tool 坐标 |
| `utils/traceFormat.js` | 耗时与 Token 紧凑格式化 |

不再存在 `traceGraphShapes.js`，因为节点样式直接由自研 DOM 与 SCSS 管理。

## 12. 验收标准

1. 每轮 Loop 有独立分组框。
2. Loop 内 Model 在上、多个 Tool 在下。
3. 每个工具只使用一个节点，并同时展示输入和输出。
4. 多个工具调用清晰横向排列。
5. 分支和汇聚连线不穿过节点卡片。
6. 支持几十轮 Loop 的上下浏览。
7. 支持拖拽、缩放、适配视图和全屏。
8. 点击节点可以查看参数、结果和异常。
9. 不新增第三方图组件依赖。
10. 前端生产构建通过。

## 13. 后续增强

1. 支持折叠单个 Loop。
2. 支持定位当前运行 Loop。
3. 支持定位第一个异常节点。
4. 实时刷新时保留用户视口位置。
5. 节点超过阈值时进行视口裁剪。
6. 后端提供 Sub Agent Trace 后，再增加嵌套链路面板。

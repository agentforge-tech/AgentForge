# AgentForge FIM Demo 前后端实现说明

## 1. 项目定位

`agentforge-fim-demo` 是一个可直接运行的 Spring Boot + 原生前端演示工程，用于验证 AgentForge FIM（Fill In the Middle）代码补全能力。

它包含以下能力：

- 通过 AgentForge `LlmFactory` 对接 OpenAI-compatible `/v1/completions`。
- 将光标之前的代码作为 `prefix`，之后的代码作为 `suffix`。
- 使用 CodeQwen 无竖线 FIM Token 模板。
- 通过 SSE 实时推送补全增量。
- 在浏览器中模拟 IDE Ghost Text，支持 Tab 接受、Escape 放弃、停顿自动补全。
- 支持 FIM Token 形态探测、TTFT、总耗时和 usage 展示。

## 2. 目录结构

```text
agentforge-fim-demo/
├── pom.xml
├── start.sh
├── README.md
├── IMPLEMENTATION_GUIDE.md
└── src/
    ├── main/java/cloud/changlu/demo/fim/
    │   ├── FimDemoApplication.java
    │   ├── config/FimProperties.java
    │   ├── config/FimModelConfiguration.java
    │   ├── dto/CompleteRequest.java
    │   ├── service/FimRequestAssembler.java
    │   ├── service/TokenProbe.java
    │   └── web/FimController.java
    ├── main/resources/
    │   ├── application.yml
    │   └── static/index.html, app.js, style.css
    └── test/java/cloud/changlu/demo/fim/
        ├── FimModelAssemblyTest.java
        └── FimLiveVerificationTest.java
```

## 3. 整体调用链

```text
用户输入或按 Tab
  → app.js 按光标切分 prefix/suffix
  → GET /api/fim/stream
  → FimController + SseEmitter
  → FimRequestAssembler
  → StreamingFimModel
  → TemplateFimModel 组装 FIM prompt
  → POST {baseUrl}/completions
  → CodeQwen1.5-7B
  → AgentForge 解析为 FimChunk
  → SSE chunk/done/error
  → 前端 Ghost Text 逐块渲染
```

## 4. 后端实现

### 4.1 启动与配置

`FimDemoApplication` 是 Spring Boot 入口，使用 `@EnableConfigurationProperties(FimProperties.class)` 绑定 `fim.*` 配置。

默认渠道与模型：

```yaml
fim:
  channel:
    provider-code: 1
    base-url: http://127.0.0.1:9997/v1
    api-key: EMPTY
  model-name: CodeQwen1.5-7B
  template: qwen-no-pipe
  temperature: 0.0
  max-tokens: 64
  timeout-seconds: 30
  streaming: true
```

`provider-code=1` 表示 OpenAI-compatible 渠道，FIM 会请求 `{base-url}/completions`。可通过 `SERVER_PORT`、`FIM_PROVIDER_CODE`、`FIM_BASE_URL`、`FIM_MODEL_NAME`、`FIM_API_KEY`、`FIM_TEMPLATE` 覆盖默认值。

### 4.2 AgentForge 模型装配

`FimModelConfiguration` 将 `FimProperties` 转换为 `LlmBasicConfig`，并创建：

- `FimModel`：同步非流式补全。
- `StreamingFimModel`：流式补全。

Demo 不直接实例化 `OpenAiFimModel`，而是依赖 AgentForge 统一契约和 `LlmFactory`。这样更换 Provider 时不需要改 Controller。

### 4.3 FIM Prompt

前端只传递语义化的 `prefix` 和 `suffix`。`qwen-no-pipe` 模板在 AgentForge 内部转换为：

```text
<fim_prefix>{prefix}<fim_suffix>{suffix}<fim_middle>
```

这使业务代码与模型 Token 形态解耦。

### 4.4 stop 策略

默认 stop 包含：

- `<|endoftext|>`
- `<fim_prefix>`、`<fim_suffix>`、`<fim_middle>`、`<fim_pad>`
- `<file_sep>`：防止 coder 模型续写训练语料中的下一个文件。
- Java 方法结束 `\n    }\n`：防止在 `add` 后继续生成 `mul/div`。

AgentForge 用未转义的 `|` 分隔 stop。由于 `<|endoftext|>` 自身包含竖线，配置中写为 `<\|endoftext\|>`。`OpenAiModel` 的解析器支持 `\|` 转义，发送时会还原。

### 4.5 上下文截断

`FimRequestAssembler` 统一处理流式和非流式请求。prefix 最多保留靠近光标的 3000 个字符，超过时在头部加 `...`。

保留尾部是因为行内补全最依赖光标附近的局部上下文，同时可以降低本地 7B 模型的推理延迟。

### 4.6 HTTP API

#### `POST /api/fim/complete`

非流式补全，请求体：

```json
{
  "prefix": "public static int add(int a, int b) {\n    ",
  "suffix": "\n}\n",
  "language": "java"
}
```

返回 `completion`、`finishReason`、`latencyMs`、`model` 和 `usage`。

#### `GET /api/fim/stream`

返回 `text/event-stream`，事件类型为：

- `chunk`：`{"delta":"return"}`
- `done`：完整补全、`finishReason`、`ttftMs`、`wallMs` 和 usage。
- `error`：根因错误消息。

`SseEmitter` 在 daemon 线程池中执行，避免长时间占用 Tomcat 工作线程。`AtomicBoolean closed` 防止客户端断开后继续写 SSE。

#### `GET /api/fim/info`

返回当前生效的渠道、地址、模型、模板、温度、maxTokens、超时和 stop。

#### `GET /api/fim/probe`

对多种 FIM Token 形态发送 `max_tokens=1` 的小请求。三个裸标记合计为 3 prompt tokens 时，说明它们是 tokenizer 注册的特殊 Token。

## 5. 前端实现

### 5.1 页面组成

前端不依赖 npm 或 CDN：

- `index.html`：案例栏、编辑区、状态栏、配置、补全结果和日志。
- `style.css`：IDE 风格、双层编辑器、Ghost Text 和响应式布局。
- `app.js`：案例、光标切分、SSE、Ghost Text、防抖、取消和 Tab 接受。

### 5.2 案例与光标

案例使用 `{{CURSOR}}` 标记缺口。加载时删除标记，再用 `setSelectionRange` 将光标放在原位置。请求时：

```javascript
prefix = value.substring(0, selectionStart);
suffix = value.substring(selectionStart);
```

因此可在文件任意中间位置模拟 FIM。

### 5.3 Ghost Text 双层编辑器

`textarea` 不能只将某一段文字显示为灰色，所以编辑区由两层组成：

1. `textarea`：处理真实文本、光标、选区、键盘和滚动。
2. `ghost-layer`：用完全相同的字体、行高、padding 和 `white-space: pre` 渲染虚拟文档。

有补全时，textarea 的字形变为透明，但 `caret-color` 仍可见。Ghost 层统一排版：

```text
可见前文 + 灰色补全 + 可见后文
```

前文、补全和后文在同一文本流中，因此流式补全增长时，后文会真实向下移，而不是重叠。

### 5.4 滚动与空白修复

Ghost 层按 textarea `scrollTop/scrollLeft` 反向平移。`.editor-wrap` 负责视口裁剪，Ghost 层必须是 `overflow: visible`。否则首屏之外的虚拟内容会在平移前被裁掉，向下滚动时只剩空白。

多行 Ghost Text 需要增加 textarea 的可滚动范围，但不能简单累加 `补全行数 × 行高`。当原文档短于一屏时，textarea `scrollHeight` 本来就等于整个视口，继续累加会多出一整屏空白。

当前计算为：

```text
virtualHeight = 虚拟文档总行数 × 21px + 上下 padding
missingHeight = max(0, virtualHeight - textarea.scrollHeight)
```

只补充 `missingHeight`，从而避免滚动超出文档末尾。流式完成时会重新渲染 Ghost 层和行号，校正最终滚动范围。

### 5.5 流式展示清理

coder 模型有时会在到达 `max_tokens` 前输出 `<file_sep>` 或大量尾部空行。前端 `normalizeGhostText()` 作为最后一道防线：

- 在 `<file_sep>` 或 FIM 边界标记前截断。
- 连续多个空白行最多保留一个。
- 删除流式尾部空格和换行，防止无意义地推走后文。

前端清理不能代替后端 stop。最佳情况是模型端直接停止，避免浪费 Token。

### 5.6 防抖和取消

真实文本输入停顿 500ms 后自动请求。以下操作只取消补全，不发起新请求：

- 鼠标点击切换光标。
- 方向键、Home、End 移动光标。
- 拖选文本或 Shift+方向键改变选区。
- 继续输入新文本。

`invalidateCompletion()` 会清除 debounce timer、关闭当前 `EventSource`，并递增 `lastRequestId`。每个 SSE 回调都校验 request ID，即使旧 chunk 已在浏览器队列中，也不会闪回新光标位置。

### 5.7 Tab 接受

Ghost Text 只是预览，不在 textarea `value` 中。按 Tab 时：

```text
newValue = value[0:cursor] + ghostText + value[cursor:]
newCursor = cursor + ghostText.length
```

随后清空 Ghost Text，恢复 textarea 颜色，并将光标移到新内容末尾。

## 6. 启动和测试

### 6.1 启动

```bash
cd agentforge-examples/agentforge-fim-demo
./start.sh
```

`start.sh` 先从当前源码构建并安装 `agentforge-model-registry` 及其依赖，再运行 Spring Boot。这可以避免本地 SNAPSHOT JAR 新旧混装导致 `NoSuchMethodError`。

访问 <http://localhost:8080>。

### 6.2 离线测试

```bash
mvn test
```

默认不访问真实模型。

### 6.3 真实端点验证

```bash
mvn -Dfim.live=true \
  -Dfim.baseUrl=http://127.0.0.1:9997/v1 \
  -Dfim.modelName=CodeQwen1.5-7B \
  -Dfim.template=qwen-no-pipe \
  -Dtest=FimLiveVerificationTest test
```

## 7. 生产化建议

- Demo 用 `EventSource + GET` 传递 prefix/suffix，长文本可能受 URL 长度限制。生产环境建议先 `POST` 创建任务，再用 SSE 读取。
- `newCachedThreadPool` 适合 demo，生产应使用有界线程池、并发限流和拒绝策略。
- textarea + Ghost 层是零依赖演示实现。复杂 Web IDE 建议使用 Monaco/CodeMirror inline completion API。
- `TokenProbe` 会发送多个小请求，只应用于接入和诊断，不应每次补全调用。
- stop 与模型、语言有关。生产环境建议按语言维护 stop 策略。

## 8. 快速排查

### HTTP 200 但补全为空

访问 `/api/fim/probe`，检查 `fim.template` 是否与 tokenizer 实际注册形态一致。

### 出现 `<file_sep> /src/...`

说明模型在续写仓库训练语料。确认 stop 包含 `<file_sep>`，并确认前端 `normalizeGhostText()` 已生效。

### Ghost Text 和后文重叠

确认 `#editor.ghost-active` 的字形为透明，并且 Ghost 层同时渲染 before、completion 和 after。

### 向下滚动后空白

检查 Ghost 层是否被 `overflow: hidden` 提前裁剪，额外滚动高度是否按 `virtualHeight - scrollHeight` 计算，以及模型是否输出大量尾部空行。

### 鼠标切换位置后仍不断请求

确认 click/select/navigation 事件调用 `invalidateCompletion()`，并同时清理 debounce timer、EventSource 和过期 request ID。

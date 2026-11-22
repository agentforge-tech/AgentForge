# Agent 快速装配：无状态 Read Tool + Middleware 附件装配技术方案

> 适用版本：release_1.x
> 适用模块：`agentforge-framework/agentforge-agent-core`
> 包前缀：`cloud.changlu.agentforge.agent`
> 维护者：长路
> 文档定位：给 Agent 快速装配「读取能力」（首期识图 + 读网页链接，后续读文件）的框架层技术方案。

---

## 一、背景与目标

当前附件链路已经具备：

- 业务层上传文件、落库、返回可访问地址；
- 框架层 `AgentAttachment`、`ImageContent`、`MultiModal UserMessage`；
- OpenAI / Anthropic 双协议图片序列化；
- `BeforeModelCall` 中间件扩展点；
- `BaseAgent.buildUserMessage` 中现成的附件装配逻辑。

但仍然存在一个装配成本：**每新增一种读取能力（识图、读链接、读 PDF），都要改 Agent、改业务层、重新接一遍附件组装。**

本方案的目标是提供一种可复用的快速装配方式：

> **无状态 Read Tool 只负责“读取并返回标准资源描述”，Middleware 负责“收集读取结果并在下一次模型调用前转换成 UserMessage 附件”。**

首期同时支持**识图**与**读网页链接**；读 PDF/Word/Excel/zip 作为后续能力接入，不改变整体架构。

<br/>

## 二、结论

你的思路是成立的，建议采用：

```text
Read Tool 执行
   │ 返回标准 ReadResult（url + 基本信息）
   ▼
Middleware.afterToolExecution / afterConcurrentToolExecution
   │ 拦截读取类 tool 的结果，仅收集，不阻塞、不改写
   ▼
Middleware.beforeModelCall
   │ 统一 drain 收集结果
   │ ReadResult -> AgentAttachment
   │ 复用附件装配器 -> UserMessage(List<Content>)
   ▼
写回 ChatMemory + 重建 ChatRequest -> 下一次模型调用
```

关键点：

1. **Tool 只提取元数据**：返回 `url + 名称 + MIME + 大小 + 类型`，不读取正文、不转 Base64、不构建 Message；
2. **Tool 无状态**：不依赖会话、不写 store、不直接改 Memory；
3. **Middleware 负责真实内容转换**：`afterToolExecution` 只收集元数据，`beforeModelCall` 才按载荷形态加载真实内容并装配 `UserMessage`；
4. **装配逻辑下沉 core-agent**：把 `BaseAgent` 的附件拼装抽成公共组件；
5. **首期链接能力**：网页链接以 TEXT 引用形式接入，Middleware 只装配标题、类型与 URL，不抓取正文；
6. **后续扩展**：如需正文抽取，再增加无状态内容解析器；压缩包、PDF/Word/Excel 同样先提取元数据。

<br/>

## 三、总体架构

```text
┌────────────────────────────────────────────────────────────────────┐
│ ReAct Loop                                                         │
│                                                                    │
│  UserMessage(question + attachments)                               │
│        │                                                           │
│        ▼                                                           │
│  Model ── tool_call: read_resource(paths) ─┐                       │
│                                           ▼                        │
│                              StatelessReadTool                     │
│                              · paths 逐项校验 http/https URL       │
│                              · SSRF / 协议 / 端口校验              │
│                              · 只探测元数据：mime / size / 类型    │
│                              · 返回 ReadBatchResult(元数据 JSON)   │
│                                           │                        │
│                                           ▼                        │
│                     ReadArtifactMiddleware                         │
│                     afterToolExecution: 收集 ReadResult            │
│                                           │                        │
│                                           ▼                        │
│                     ReadArtifactMiddleware                         │
│                     beforeModelCall: 真实内容转换 + 装配 UserMessage│
│                     · 决定 URL / INLINE / TEXT                     │
│                     · INLINE 时才读取真实字节并转 Base64           │
│                                           │                        │
│                                           ▼                        │
│                     AttachmentContentAssembler                     │
│                     AgentAttachment -> Content                     │
│                                           │                        │
│                                           ▼                        │
│                     ChatMemory.add(UserMessage)                    │
│                     ChatRequest.rebuild(memory.messages())         │
│                                           │                        │
│                                           ▼                        │
│                                        Model                       │
└────────────────────────────────────────────────────────────────────┘
```

职责边界：

| 层 | 职责 | 不做什么 |
|---|---|---|
| Read Tool | 解析 URL 列表、校验安全边界、只提取元数据、返回标准 `ReadBatchResult` | 不读正文、不转 Base64、不读 Memory、不生成 `UserMessage` |
| Read Middleware | 收集元数据、决定载荷形态、加载真实内容、装配 `UserMessage` | 不解析业务语义、不做协议序列化 |
| Attachment Assembler | `AgentAttachment` -> `TextContent` / `ImageContent` | 不关心 tool 从哪来 |
| Model Adapter | `Content` -> OpenAI / Anthropic 报文 | 不做业务降级决策 |

一句话区分：

> **Tool 回答“这个资源是什么”，Middleware 回答“这个资源怎么变成模型能消费的内容”。**

<br/>

## 四、无状态 Read Tool

### 4.1 Tool 定位

`ReadResourceTool` 是一个**只做元数据提取**的无状态工具：

```text
输入：paths（必填，List<String>，1..5 个）、hint（可选）
输出：ReadBatchResult 元数据 JSON
状态：无
副作用：仅发起受控 HEAD / Range 探测
```

**入参默认就是列表**，即使只有一个 URL 也传 `paths`，保持调用形态稳定。

首期明确只支持**无状态 URL**：

| 输入 | 首期支持 | 说明 |
|---|---|---|
| `https://example.com/a.png` | 是 | 图片 URL，校验后探测元数据 |
| `https://example.com/article` | 是 | 网页链接，返回 LINK 元数据 |
| `http://...` | 是 | 同上 |
| 本地路径 / `file://` / 业务 fileId | 否 | 无状态 Agent 只接收远程 URL |

它**不读取完整正文、不转 Base64、不拼 Content、不写 Memory**。真正的内容加载与 Message 转换发生在 Middleware 阶段。

批量语义：

| 场景 | 行为 |
|---|---|
| 1 个 URL | 返回包含 1 个元素的 `results` |
| 多个 URL | 逐个校验与探测，单个失败不影响其他结果 |
| 超过 5 个 URL | 返回 `TOO_MANY_PATHS`，建议模型分批调用 |
| 重复 URL | Tool 可保留，Middleware 按 `dedupeKey` 去重 |

无状态的含义：

- 不按 `memoryId` 缓存结果；
- 不向 `AgentChatContext` 写业务数据；
- 不直接向 ChatMemory 追加消息；
- 相同 URL、相同参数，重复调用结果幂等（允许外部资源本身变化）。

这样 Tool 可以被任意 Agent 装配，不需要为每个 Agent 维护读取状态。

### 4.2 标准返回结构

批量结果：

```java
public final class ReadBatchResult {
    /** 稳定契约版本，新增字段不改变已有语义 */
    private String schema = "agentforge.read.v1";
    private boolean ok;
    private List<ReadResult> results;
    private String errorCode;
    private String message;
}
```

单项元数据：

```java
public final class ReadResult {
    /** 单项是否成功 */
    private boolean ok;
    /** 失败码；成功时为 null */
    private String errorCode;
    /** 失败说明；成功时为 null */
    private String message;
    /** IMAGE / TEXT / LINK / FILE / UNKNOWN，使用枚举表达，便于扩展与分支判断 */
    private ReadResourceKind kind;
    /** 模型可访问的完整地址 */
    private String url;
    /** 原始文件名或资源标题 */
    private String name;
    /** MIME，例如 image/png、text/html */
    private String mimeType;
    /** 扩展名，例如 png、html、zip */
    private String fileType;
    /** 字节数；未知时为 null */
    private Long size;
    /** 来源，例如 user、tool、web */
    private String source;
    /** 读取到的正文；图片为空，LINK 由 Middleware 抽取后填充 */
    private String text;
    /** 去重键，默认 url */
    private String dedupeKey;
    /** 附加信息：宽高、最后修改时间、抓取时间等 */
    private Map<String, Object> extra;
}
```

`kind` 使用 `ReadResourceKind` 枚举（`IMAGE / TEXT / LINK / FILE / UNKNOWN`），JSON 契约中仍输出枚举名，未知值在解析时统一降级为 `UNKNOWN`。新增类型只需扩展枚举，消费方使用 `kind == ReadResourceKind.IMAGE` / `kind.isImage()` 判断，不再散落字符串比较。

成功示例（两个 URL）：

```json
{
  "ok": true,
  "results": [
    {
      "ok": true,
      "kind": "IMAGE",
      "url": "https://example.com/a.png",
      "name": "a.png",
      "mimeType": "image/png",
      "fileType": "png",
      "size": 183462,
      "source": "web",
      "text": null,
      "dedupeKey": "https://example.com/a.png",
      "extra": { "width": 1280, "height": 720 }
    },
    {
      "ok": false,
      "errorCode": "UNREACHABLE",
      "message": "url is not reachable",
      "url": "https://example.com/b.png"
    }
  ]
}
```

单条失败不会让整批失败；Middleware 只收集 `ok=true` 且 `kind` 可映射的项。

Tool 的返回值保持 JSON 字符串，既可以作为 `ToolExecutionResultMessage` 交给模型，也可以被 Middleware 解析。

### 4.3 首期支持 URL 识图与读网页链接

首期 `ReadResourceTool` 处理**无状态 URL**，按 `Content-Type` 分流：

| Content-Type | kind | Tool 行为 |
|---|---|---|
| `image/*` | `IMAGE` | 只提取图片元数据 |
| `text/html` | `LINK` | 只提取网页元数据，不抓正文 |
| `text/plain` | `TEXT` | 只提取文本元数据 |
| 其他 | `UNKNOWN` | 返回元数据，由 Middleware 保守降级 |

处理步骤：

1. 逐项校验 `paths`：
   - 必须是 `http/https`；
   - 拒绝内网、环回、非标准端口（防 SSRF）；
   - 拒绝本地路径、`file://`、相对路径；
2. 发起 `HEAD` 或带 Range 的 `GET` 探测；
3. 按 `Content-Type` 判定 `kind`；
4. 限制大小与超时（建议 10MB / 5s）；
5. 每个 URL 返回一个 `ReadResult`，整体包在 `ReadBatchResult` 中；
6. **只带元数据，不读取图片 Base64，也不抓取网页正文**。

真实内容转换由 Middleware 完成：

- `IMAGE`：`URL` 形态直接外链，`INLINE` 时由无状态 `ImageLoader` 读取远程 URL 字节；
- `LINK`：组装为 TEXT 引用，包含标题、类型与 URL，不抓取正文；
- `TEXT`：按引用处理；
- 其他：生成带 `url` 的文字引用。

### 4.4 Tool 代码骨架

```java
public final class ReadResourceTool {

    public static final String TOOL_NAME = "read_resource";

    private final ReadResourceFetcher fetcher;

    public ReadResourceTool(ReadResourceFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Tool(
            name = TOOL_NAME,
            value =
                    "Read metadata for one or more image or web page URLs. "
                            + "Use this when the user provides http/https image links or article links that need understanding. "
                            + "Always pass paths as a list, even for a single resource. "
                            + "Only http/https URLs are supported. "
                            + "For images this tool returns metadata and an accessible URL; image bytes are loaded by the middleware. "
                            + "For web pages this tool returns metadata; page text is extracted by the middleware.")
    public String read(
            @P(
                            name = "paths",
                            description =
                                    "List of http/https image URLs, 1 to 5 items, for example [\"https://example.com/a.png\"]")
                    List<String> paths,
            @P(name = "hint", description = "why these images are needed", required = false)
                    String hint) {
        ReadBatchResult batch = fetcher.fetch(paths, hint);
        return ReadResultJson.toJson(batch);
    }
}
```

`ReadResourceFetcher` 是无状态的 URL 适配器：

- `HttpReadResourceFetcher`：校验 http/https、SSRF、Content-Type、大小与超时；
- 无状态 Agent 只处理远程 URL，不接收本地路径，也不依赖上传目录映射。

### 4.5 用户直接给图片地址时的主动调用

这是该 Tool 的核心价值之一：

```text
用户：这张图是什么？https://example.com/a.png
模型：识别到用户给了图片地址 -> 主动调用 read_resource(
        paths=["https://example.com/a.png"]
      )
Tool：校验 URL -> 探测 image/png -> 返回 ReadBatchResult
Middleware：afterToolExecution 收集 -> beforeModelCall 转成 ImageContent
模型：看到图片并回答
```

多个图片地址：

```text
用户：对比下这两张图 https://example.com/a.png 和 https://example.com/b.png
模型：主动调用 read_resource(
        paths=["https://example.com/a.png", "https://example.com/b.png"]
      )
Tool：逐个探测 -> 返回 results 列表
Middleware：一次 beforeModelCall 合并成一条 UserMessage，包含两个 ImageContent
```

网页链接：

```text
用户：这个链接讲了什么 https://example.com/article
模型：主动调用 read_resource(paths=["https://example.com/article"])
Tool：探测 text/html -> 返回 LINK 元数据
Middleware：组装为 TEXT 引用（标题 + URL）
模型：基于链接引用回答，或提示用户需要正文
```

和“用户直接上传附件”的区别：

| 场景 | 入口 | 是否经过 Read Tool |
|---|---|---|
| 用户上传文件 | 前端 `files` -> `AgentRequest.attachments` | 否，直接走 BaseAgent 附件装配 |
| 用户粘贴图片 URL | 文本中出现 http/https URL | 是，模型主动调用 Read Tool |
| Agent 在工具结果里发现 URL | Tool 输出参数 | 是，模型可继续调用 Read Tool |

也就是说，Read Tool 补齐了“附件链路之外的按需读取能力”：**附件是用户显式携带，Read Tool 是 Agent 按需读取。**

<br/>

## 五、ReadArtifactMiddleware

### 5.1 两段式设计

```text
afterToolExecution / afterConcurrentToolExecution
    · 只采集，不改变工具结果
    · 按 memoryId + executionId 缓冲

beforeModelCall
    · 一次性 drain
    · ReadResult -> AgentAttachment
    · 合并成 UserMessage
    · 写 Memory + 重建 ChatRequest
```

为什么不直接在 Tool 里写 `UserMessage`：

- Tool 不应该依赖 ChatMemory；
- Tool 可能在并发批次中执行，直接写 Memory 会打乱消息顺序；
- Tool 不应该知道当前模型是否支持图片，也不知道该走 URL 还是 Base64；
- Middleware 是模型调用边界，天然适合做“统一装配”。

为什么不在 `beforeModelCall` 里扫描 Memory 找 tool 结果：

- 需要反解析 `ToolExecutionResultMessage`，耦合消息结构；
- 无法可靠区分本轮新增结果与历史结果；
- 并发工具结果的顺序与归属不直观；
- 直接用 `afterToolExecution` 收集更明确、可控。

### 5.2 缓冲键与并发

```java
private final Map<String, Deque<ReadResult>> pending = new ConcurrentHashMap<>();

private String key(AgentChatContext context) {
    return String.valueOf(context.getMemoryId()) + ":" + context.getExecutionId();
}
```

- `memoryId` 隔离会话；
- `executionId` 隔离同一会话中的并发运行；
- 同一个 key 使用线程安全队列；
- 每个结果带 `toolCallId` 或 `dedupeKey`，避免重复收集。

### 5.3 单工具采集

```java
@Override
public String afterToolExecution(
        ToolExecutionRequest toolRequest, String toolResult, AgentChatContext context) {
    collect(toolRequest, toolResult, context);
    return toolResult;
}
```

采集逻辑：

1. `toolRequest.name()` 是否在读取类工具白名单中；
2. `toolResult` 是否是合法 `ReadBatchResult` JSON；
3. 遍历 `batch.results`，逐项判断 `ok`；
4. `ok=false` 的项只记录日志，不进入附件队列；
5. 按 `dedupeKey` 去重；
6. 放入当前 `memoryId + executionId` 队列。

### 5.4 并发工具采集

```java
@Override
public List<String> afterConcurrentToolExecution(
        List<ToolExecutionRequest> requests,
        List<String> results,
        AgentChatContext context) {
    for (int i = 0; i < requests.size(); i++) {
        collect(requests.get(i), results.get(i), context);
    }
    return results;
}
```

并发批次只在全部完成之后收集，保证顺序与 `requests` 一致，不破坏工具结果。

### 5.5 beforeModelCall 统一装配

```java
@Override
public ChatRequest beforeModelCall(
        int currentStep, ChatRequest request, AgentChatContext context) {
    List<ReadResult> artifacts = drain(context);
    if (artifacts.isEmpty()) {
        return request;
    }

    List<AgentAttachment> attachments = converter.convert(artifacts, context);
    if (attachments.isEmpty()) {
        return request;
    }

    UserMessage message =
            AttachmentContentAssembler.buildUserMessage(
                    "以下是本轮读取到的资源，请结合附件内容继续回答。",
                    attachments,
                    imageLoader);
    context.getChatMemory().add(message);

    return ChatRequest.builder()
            .messages(context.getChatMemory().messages())
            .parameters(request.parameters())
            .build();
}
```

要点：

1. **Tool 只给元数据，Middleware 才做真实内容转换**：
   - `URL`：直接基于元数据里的 `url` 构建外链图片，不下载；
   - `INLINE`：由 `ImageLoader.load(url)` 读取真实字节并转 Base64；
   - `TEXT`：基于元数据生成文字引用，不读取图片字节；
2. **一次性 drain**：同一批读取结果合并为一条 `UserMessage`，避免每条 tool 结果都插一条用户消息；
3. **复用装配器**：`AttachmentContentAssembler` 与 `BaseAgent` 使用同一套逻辑；
4. **重建请求**：Memory 变化后必须用最新 `messages` 重建 `ChatRequest`；
5. **幂等**：drain 后清空队列，下一轮不会重复插入同一附件。

### 5.6 消息序列

最终模型看到的消息顺序：

```text
user:      帮我看下这张图
assistant: tool_call -> read_resource(paths=["https://..."])
tool:      ReadBatchResult(JSON)
user:      以下是本轮读取到的资源... + ImageContent
assistant: 图片内容描述...
```

说明：

- OpenAI 允许 `tool -> user` 的转换；
- Anthropic 会把相邻同角色消息合并；
- 额外插入一条 UserMessage 是跨协议可移植的做法，不尝试把图片塞进 tool 结果（两者协议都不支持 tool message 带图片）。

### 5.7 生命周期清理

| 时机 | 动作 |
|---|---|
| `onInitComplete` | 初始化当前 run 的队列 |
| `onStop` | 清理队列，避免泄漏 |
| `onStopWithError` | 清理队列 |
| `beforeModelCall` | drain 后清理已消费结果 |
| `afterToolExecution` | 非读取工具直接返回，不产生队列 |

<br/>

## 六、把附件装配下沉到 core-agent

### 6.1 当前 BaseAgent 逻辑

当前 `BaseAgent.buildUserMessage` 已经包含完整的附件装配：

```java
List<AgentAttachment> attachments = chatContext.getAttachments();
if (attachments != null && !attachments.isEmpty()) {
    int index = 1;
    for (AgentAttachment attachment : attachments) {
        if (attachment == null) {
            continue;
        }
        appendAttachmentContents(contents, attachment, index++);
    }
}
return UserMessage.from(contents);
```

这段逻辑不应该只属于 `BaseAgent`，因为：

- Read Middleware 需要完全相同的装配能力；
- 未来其他中间件（文件读取、OCR、网页摘要）也需要；
- 统一后可以保证“用户直接上传附件”和“Agent 读取附件”在模型侧表现一致。

### 6.2 抽取 `AttachmentContentAssembler`

装配过程本身无状态，直接抽成静态工具类，避免每次构建用户消息都创建实例：

```java
package cloud.changlu.agentforge.agent.support;

public final class AttachmentContentAssembler {

    /** 框架内置的无状态 URL 图片加载器。 */
    private static final ImageLoader URL_IMAGE_LOADER = new UrlImageLoader();

    /** 全局默认图片加载器，业务层可在启动时覆盖为本地优先等实现。 */
    private static volatile ImageLoader defaultImageLoader = URL_IMAGE_LOADER;

    private AttachmentContentAssembler() {}

    public static void setDefaultImageLoader(ImageLoader imageLoader) {
        defaultImageLoader = imageLoader == null ? URL_IMAGE_LOADER : imageLoader;
    }

    public static UserMessage buildUserMessage(String text, List<AgentAttachment> attachments) {
        return buildUserMessage(text, attachments, defaultImageLoader);
    }

    public static UserMessage buildUserMessage(
            String text, List<AgentAttachment> attachments, ImageLoader imageLoader) {
        List<Content> contents = new ArrayList<>();
        contents.add(TextContent.from(
                text == null || text.trim().isEmpty()
                        ? "我上传了附件，请结合附件内容回答。"
                        : text.trim()));
        appendAttachments(contents, attachments, imageLoader);
        return UserMessage.from(contents);
    }

    public static void appendAttachments(
            List<Content> contents, List<AgentAttachment> attachments) {
        appendAttachments(contents, attachments, defaultImageLoader);
    }

    public static void appendAttachments(
            List<Content> contents, List<AgentAttachment> attachments, ImageLoader imageLoader) {
        if (attachments == null || attachments.isEmpty()) {
            return;
        }
        int index = 1;
        for (AgentAttachment attachment : attachments) {
            if (attachment == null) {
                continue;
            }
            appendAttachment(contents, attachment, index++, imageLoader);
        }
    }

    private static void appendAttachment(
            List<Content> contents, AgentAttachment attachment, int index, ImageLoader imageLoader) {
        // 原 BaseAgent.appendAttachmentContents 整体迁移到这里
    }
}
```

设计要点：

| 能力 | 说明 |
|---|---|
| 静态调用 | 无需实例化，`BaseAgent`、`ReadArtifactMiddleware` 直接调用 |
| 内置默认加载器 | 默认使用 `UrlImageLoader`，开箱即用 |
| 全局覆盖 | 业务层通过 `setDefaultImageLoader(...)` 在启动时注册一次本地优先实现 |
| 显式覆盖 | 需要临时指定加载器时，使用带 `ImageLoader` 参数的重载方法 |

### 6.3 BaseAgent 改造

```java
protected UserMessage buildUserMessage(AgentChatContext chatContext) {
    return AttachmentContentAssembler.buildUserMessage(
            chatContext.getQuestion(), chatContext.getAttachments());
}
```

`BaseAgent` 不再持有 `ImageLoader` 字段，也不再提供 `setImageLoader`；图片加载实现统一收敛到装配器的全局默认值。

改造收益：

| 收益 | 说明 |
|---|---|
| 单一事实来源 | 用户上传与 Tool 读取共用同一套附件规则 |
| Middleware 可复用 | `ReadArtifactMiddleware` 不需要复制图片/文本/降级逻辑 |
| 易测试 | 装配逻辑可以脱离 Agent 单独测试 |
| 后续扩展 | 音频、视频、文件原生输入只改装配器与协议层 |

### 6.4 ImageLoader 注入（已收敛到装配器）

Agent 与中间件不再各自注入 `ImageLoader`：

- `AttachmentContentAssembler` 内置默认 `UrlImageLoader`，无状态 URL 图片可直接使用；
- 业务层如需本地磁盘优先、OSS 直读等实现，只需在启动阶段注册一次：

```java
AttachmentContentAssembler.setDefaultImageLoader(localFirstImageLoader);
```

- `BaseAgent` 不再持有 `setImageLoader`；
- `ReActAgent.Builder` 不再提供 `imageLoader(...)`；
- `ReadArtifactMiddleware.Builder.imageLoader(...)` 仅作为可选的临时覆盖（例如单元测试注入内存加载器），生产装配无需传入。

这样「用户直接上传附件」与「Agent 主动读取 URL」都自动复用同一个全局默认加载器，避免在工厂、Agent、Middleware 三处重复传递。

<br/>

## 七、ReadResult 到 AgentAttachment 的映射

| `ReadResult.kind` | `AgentAttachmentType` | `payloadType` | 说明 |
|---|---|---|---|
| `IMAGE` | `IMAGE` | `URL` / `INLINE` / `TEXT` | 首期识图，按模型与 URL 可达性路由 |
| `TEXT` | `TEXT` | `TEXT` | `text` 直接进入文本块 |
| `LINK` | `TEXT` | `TEXT` | 首期只给标题、URL；后续抽取正文 |
| `FILE` | `UNKNOWN` | `TEXT` | 只给文件名、类型、大小、URL |
| `UNKNOWN` | `UNKNOWN` | `TEXT` | 保守引用 |

映射时保留：

- `fileId`：可选，Tool 读取的资源可能没有业务 fileId；
- `name`：优先 `originFileName` / 标题 / URL 最后一段；
- `fileType`：扩展名小写；
- `size`：探测值；
- `url`：模型可访问地址；
- `text`：TEXT 载荷正文。

<br/>

## 八、模型与协议映射

### 8.1 首期自动映射

Middleware 不直接依赖 service 层的 `DefaultAgentAttachmentRoutingService`，而是通过一个可插拔的 `ReadAttachmentPolicy`：

```java
public interface ReadAttachmentPolicy {
    AttachmentPayloadType decide(ReadResult result, AgentChatContext context);
}
```

默认实现按以下顺序判断：

```text
图片？
  ├─ 模型不支持图片 -> TEXT
  ├─ Anthropic -> INLINE
  ├─ URL 公网可达 -> URL
  ├─ 文件 <= 4MB -> INLINE
  └─ 其他 -> TEXT
```

模型能力来源可以先通过 `AgentChatContext` 扩展字段传入：

```java
context.putExtension("attachment.supportsImage", true);
context.putExtension("attachment.providerCode", providerCode);
```

后续再把 `LlmModelConfig` 或能力快照直接放进 `AgentChatContext`，Middleware 不再依赖字符串键。

### 8.2 复用现有协议转换

图片最终仍落到 `ImageContent`：

| 载荷 | `ImageContent` | OpenAI | Anthropic |
|---|---|---|---|
| `URL` | `fromUrl` | `image_url.url` | 不支持，路由层应改写为 INLINE |
| `INLINE` | `fromBase64` | `data:` URI | `source.base64` |
| `TEXT` | 无图片块 | 文本引用 | 文本引用 |

协议适配器不需要感知 Read Tool 或 Middleware，仍然只处理 `Content`。

### 8.3 后续动态能力

演进方向：

1. `LlmModelConfig` 增加 `inputModalities`（TEXT / IMAGE / AUDIO / VIDEO / FILE）；
2. 模型配置表增加能力字段与 `payloadPriority`；
3. `ReadAttachmentPolicy` 改为读取模型能力集合；
4. 注册表支持运行时注册新模型；
5. 新增 ReadResult kind 时只扩展映射表，不改 Middleware 骨架。

<br/>

## 九、首期识图完整流程

```text
1. 用户：帮我看下这张图 https://example.com/a.png
2. 模型：tool_call read_resource(paths=[url])
3. Tool：
   - 校验 https
   - 拒绝 127.x / 10.x / 172.16-31.x / 192.168.x
   - HEAD 探测 image/png，大小 183KB
   - 只返回元数据 ReadResult，不读取图片字节
4. Middleware.afterToolExecution：收集 ReadResult 元数据
5. Middleware.beforeModelCall：
   - ReadResult -> AgentAttachment(type=IMAGE, url=..., name=a.png)
   - Policy：公网 URL + 视觉模型 -> URL
   - 真实内容转换：URL 形态直接 ImageContent.fromUrl；INLINE 形态才调用 ImageLoader 读取字节
   - Assembler：TextContent + ImageContent
   - Memory.add(UserMessage)
   - rebuild ChatRequest
6. 模型：看到图片并回答
```

如果模型不支持图片：

```text
Policy -> TEXT
Assembler -> TextContent("附件：a.png，类型 png，大小 183462 bytes，访问地址 ...")
模型：收到文字引用，仍可提示用户图片地址
```

<br/>

## 十、网页链接首期接入与后续读文件

### 10.1 网页链接（首期：引用）

Tool 返回 LINK 元数据：

```json
{
  "ok": true,
  "kind": "LINK",
  "url": "https://example.com/article",
  "name": "example.com/article",
  "mimeType": "text/html",
  "fileType": "html",
  "size": 54000,
  "text": null,
  "dedupeKey": "https://example.com/article"
}
```

首期 Middleware 映射为 `AgentAttachmentType.TEXT`，只装配标题、类型与 URL，不抓取正文。模型基于链接引用回答，或明确告知用户需要正文内容。

后续如果确实需要“读正文”，再增加无状态 `LinkContentExtractor` 作为独立扩展，不改变 Tool 与 Middleware 的契约：

```text
LinkContentExtractor.extract(url) -> text
  · 限制 2MB
  · 不执行 JS
  · 提取标题 + 正文
  · 截断 12000 字符并附 URL
```

### 10.2 读文件（后续）

| 文件 | 首期 | 后续 |
|---|---|---|
| pdf | 只返回 URL | 抽取正文 |
| docx | 只返回 URL | 抽取正文 |
| xlsx | 只返回 URL | 结构化为文本 |
| zip | 只返回 URL | 受控解压后递归读取 |

> 有状态的读取场景（例如需要 token 的私有仓库、需要分页的数据库记录）单独实现 `StatefulReadTool`，复用 `agentforge.read.v1` 契约即可。

### 10.3 与现有附件链路的关系

- 用户直接上传：仍走业务层 `files` -> `AgentAttachment` -> `BaseAgent`；
- Agent 主动读取：走 Read Tool -> Middleware -> `AgentAttachment` -> Assembler；
- 两条链路最终都汇入同一个 `UserMessage(List<Content>)` 与同一套协议转换。

<br/>

## 十一、快速装配示例

### 11.1 最小装配（推荐）

图片加载器已内置在装配器中，最小装配无需再向 Agent 或 Middleware 传入 `ImageLoader`：

```java
ReadResourceTool readTool = ReadResourceTool.builder().build();

ToolService toolService = new ToolService();
toolService.tools(Arrays.asList(readTool));

ReadArtifactMiddleware readMiddleware =
        ReadArtifactMiddleware.builder().build();

ReActAgent agent =
        ReActAgent.builder()
                .agentName("read-demo")
                .chatModel(chatModel)
                .chatMemoryProvider(memoryProvider)
                .toolService(toolService)
                .middleware(readMiddleware)
                .build();
```

如需把默认的 `UrlImageLoader` 换成业务实现（例如本地磁盘优先），启动时注册一次即可：

```java
AttachmentContentAssembler.setDefaultImageLoader(
        new LocalFirstImageLoader(uploadProperties));
```

服务端拿到当前模型能力后，推荐这样配置 Middleware（`ReActAgentFactory` 中的实际写法）：

```java
boolean requiresInlineImage =
        modelConfig.getProvider() != null
                && modelConfig.getProvider() == LlmProviderEnum.ANTHROPIC.getCode();

ReadArtifactMiddleware readArtifactMiddleware =
        ReadArtifactMiddleware.builder()
                .supportsImage(modelAdvancedConfig.isSupportsImage())
                .requiresInlineImage(requiresInlineImage)
                .build();
```

| 参数 | 取值来源 | 作用 |
|---|---|---|
| `supportsImage` | 模型配置 `modelAdvancedConfig.isSupportsImage()` | 为 false 时图片统一降级为 TEXT 引用，避免非视觉模型报错 |
| `requiresInlineImage` | `provider == ANTHROPIC` | 为 true 时图片强制内联为 Base64，规避 Anthropic 不支持外链图片 |

这段代码已经具备：

- Read Tool 的 URL 校验、SSRF 防护、元数据探测；
- Tool 的关键步骤日志；
- Middleware 的元数据收集、附件转换、UserMessage 装配；
- URL / INLINE / TEXT 自动路由；
- INLINE 失败时的文字引用降级。

### 11.2 自定义日志与策略

需要统一日志、限制数量或追加读取工具时：

```java
Logger readLog = Logger.getLogger("agent.read");

ReadToolOptions options =
        ReadToolOptions.builder()
                .maxPaths(5)
                .maxBytes(10 * 1024 * 1024)
                .timeoutMillis(5000)
                .build();

ReadResourceTool readTool =
        ReadResourceTool.builder()
                .options(options)
                .log(readLog)
                .build();

ReadArtifactMiddleware readMiddleware =
        ReadArtifactMiddleware.builder()
                .readToolNames("read_resource", "read_image")
                .maxInlineImageBytes(4L * 1024 * 1024)
                .dedupeEnabled(true)
                .log(readLog)
                .build();
```

### 11.3 装配检查表

| 检查项 | 要求 |
|---|---|
| Tool 注册 | `read_resource` 已注册进 `ToolService` |
| Middleware 注册 | `ReadArtifactMiddleware` 已加入 Agent 的中间件列表 |
| ImageLoader | 默认内置 `UrlImageLoader`；如需本地优先，启动时用 `setDefaultImageLoader` 注册一次 |
| 模型能力 | 非视觉模型可用，但图片会降级为 TEXT |
| 工具描述 | 明确告知模型“paths 为 URL 列表，首期仅支持 http/https” |
| 日志 | Tool 与 Middleware 使用同一 Logger，便于串联排查 |
| 中间件顺序 | 按注册顺序执行，建议与其他消息改写类中间件保持清晰先后 |

### 11.4 和现有 Agent 的关系

- 不影响现有 `AgentRequest.attachments`：用户上传仍然直接进入 `BaseAgent`；
- Read Tool 是增量能力：只有模型主动调用时才产生读取结果；
- Middleware 只在检测到读取类 Tool 结果时才改写请求；
- 未注册 Read Tool 的 Agent 完全不受影响。

业务层不需要为每个 Agent 重复实现附件拼装。

<br/>

## 十二、异常、幂等与安全

### 12.1 异常

| 场景 | 处理 |
|---|---|
| URL 非法 / 非 http(s) | Tool 返回 `ok=false`，Middleware 不入队 |
| SSRF 命中私网 | Tool 拒绝，返回 `SSRF_BLOCKED` |
| 内容不是图片 | Tool 返回 `UNSUPPORTED_CONTENT_TYPE` |
| 文件超限 | Tool 返回 `TOO_LARGE` |
| 下载超时 | Tool 返回 `TIMEOUT` |
| ReadBatchResult JSON 解析失败 | Middleware 忽略，保留原始 tool 结果给模型 |
| 图片转 Base64 失败 | Assembler 降级为文字引用，不阻断整轮对话 |

### 12.2 幂等

- `dedupeKey` 默认使用 URL；
- 同一 `beforeModelCall` 内重复读取只保留一份；
- drain 后清空队列；
- 失败结果不进入附件队列，避免重复失败。

### 12.3 安全

- 仅允许 `http/https`；
- 拒绝 `localhost`、`127.x`、`10.x`、`172.16-31.x`、`192.168.x`；
- 限制重定向次数；
- 限制响应体大小与读取超时；
- 校验 `Content-Type`，不信任扩展名；
- 不执行压缩包内容；
- 不把 Tool 原始响应直接拼入系统提示词。

<br/>

## 十三、测试清单

| 场景 | 预期 |
|---|---|
| 用户在文本中粘贴图片 URL | Agent 主动调用 `read_resource(paths=[url])`，Middleware 转成图片附件 |
| 用户在文本中粘贴多个图片 URL | Tool 返回 `results` 列表，Middleware 一次合并为一条 UserMessage |
| Tool 返回后检查 | `results` 只包含元数据，不包含 Base64 / Content |
| 视觉模型 + 公网图片 URL | ReadResult -> URL -> `ImageContent`，模型可识图 |
| 视觉模型 + 私网图片 URL | Policy -> INLINE，Middleware 读取字节并 Base64 内联 |
| Anthropic + 图片 | 强制 INLINE，协议报文为 `source.base64` |
| 非视觉模型 + 图片 | Policy -> TEXT，模型收到文字引用 |
| 并发读取 3 张图片 | 一次 beforeModel 合并为一条 UserMessage，顺序稳定 |
| 同一 URL 重复读取 | dedupe 后只保留一份 |
| Tool 返回失败 | 不入队，不影响其他结果 |
| ReadBatchResult 非法 JSON | 忽略，不抛异常 |
| 多轮 ReAct | 只消费一次，不重复插消息 |
| run 停止 / 异常 | 缓冲清理，不泄漏 |
| 读网页链接（首期） | LINK 映射为 TEXT 引用，不抓取正文 |

<br/>

## 十四、演进路线与本地运行时

### 14.1 演进路线

| 阶段 | 能力 |
|---|---|
| 一期 | 识图 + 网页链接引用：Read Tool + Middleware + URL/INLINE/TEXT 路由 |
| 二期 | 网页正文抽取：LinkContentExtractor 作为可选扩展 |
| 三期 | 读 PDF/Word/Excel：AttachmentContentExtractor |
| 四期 | 读 zip：受控解压 + 递归读取 |
| 五期 | 模态能力配置化：LlmModelConfig 增加音频/视频/文件能力 |
| 六期 | 动态模型注册：管理后台补配置即可生效 |

### 14.2 未来：无状态 Tool + 本地运行时

“无状态 Tool”不等于“永远只能远程 URL”。更合适的未来形态是：

> **Tool 契约保持无状态，本地运行时负责提供受控的环境能力。**

```text
StatelessReadTool
   │ 只声明：read(paths / request)
   ▼
LocalReadRuntime（本地运行时）
   │ · 沙箱工作区
   │ · 本地文件读取
   │ · 浏览器渲染 / OCR / 解压
   │ · 鉴权、限流、缓存
   ▼
ReadBatchResult(agentforge.read.v1)
   ▼
ReadArtifactMiddleware（完全不用改）
```

关键设计：

| 能力 | 归属 | 说明 |
|---|---|---|
| URL 校验、元数据提取 | Tool | 保持纯函数式 |
| 本地文件访问 | Runtime | 只能访问沙箱根目录 |
| 浏览器渲染 | Runtime | 处理 SPA/动态页面，后续能力 |
| OCR / PDF 解析 / 解压 | Runtime | 通过能力接口暴露 |
| 鉴权、token、代理 | Runtime | Tool 不持有密钥 |
| 临时文件与清理 | Runtime | 单次执行结束即回收 |
| 返回值契约 | Tool | 始终返回 `ReadBatchResult` |

建议接口：

```java
public interface LocalReadRuntime {
    RuntimeFile resolve(String path);
    byte[] read(RuntimeFile file, ReadRuntimeOptions options);
    String renderText(String url, ReadRuntimeOptions options);
    ExtractedContent extract(RuntimeFile file, ExtractOptions options);
}
```

```java
ReadResourceTool tool =
        ReadResourceTool.builder()
                .runtime(localReadRuntime)   // 无状态 Tool + 本地运行时
                .build();
```

这样：

- 无状态 Agent 仍可只使用远程 URL；
- 本地运行时模式可以安全读取沙箱文件、渲染页面、OCR、解压；
- 有状态需求通过 Runtime 承载，而不是把状态塞进 Tool；
- Middleware 只消费标准契约，不感知运行环境。

最终形态：

```text
Read Tool 只描述“读到了什么”
Local Runtime 提供“在哪里、用什么能力读”
Middleware 只决定“怎么装配给模型”
Assembler 只负责“Attachment -> Content”
Model Adapter 只负责“Content -> 协议报文”
```

四层职责稳定后，新增读取能力只需要增加 Tool / Runtime 能力与 ReadResult 映射，不需要再改 ReAct 内核与协议适配器。

---

## 十五、总结

1. 无状态 Read Tool 只负责**URL 元数据提取**与返回标准 `ReadBatchResult`，不读取正文、不转 Base64、不写 Memory；
2. Read Middleware 用 `afterToolExecution` 收集元数据、`beforeModelCall` 完成**真实内容加载与 UserMessage 装配**，正好落在模型调用边界；
3. 把 `BaseAgent` 的附件装配下沉为静态工具类 `AttachmentContentAssembler`，用户上传与 Agent 读取共用一套规则，无需重复实例化；
4. 首期支持识图与网页链接引用，URL/INLINE/TEXT 路由由 Policy 决定；
5. `ReadResult.kind` 使用 `ReadResourceKind` 枚举，新增读取类型只扩展枚举与消费分支；
6. Tool 与 Middleware 都提供 Builder 与内置默认组件，图片加载器默认内置，接入时无需再向 Agent/Middleware 注入 `ImageLoader`；
7. Tool 与 Middleware 默认打印关键步骤日志，便于定位 URL 探测、载荷路由、INLINE 加载与降级问题；
8. 未来通过 `StatefulReadTool` 与本地运行时扩展能力，Middleware 仍只依赖 `agentforge.read.v1` 契约。

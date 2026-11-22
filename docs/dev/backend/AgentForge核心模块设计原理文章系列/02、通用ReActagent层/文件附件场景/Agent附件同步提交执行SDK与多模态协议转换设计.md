# AgentForge ReAct 层附件场景：附件同步提交执行 SDK 与多模态协议转换设计

> 适用版本：release_1.x
> 适用模块：`agentforge-framework/agentforge-agent-core`、`agentforge-model/agentforge-model-api`
> 包前缀：`cloud.changlu.agentforge.agent`、`cloud.changlu.agentforge.model.chat.message`
> 维护者：长路

系列定位：本文属于「AgentForge 核心模块设计原理文章系列 / 02、通用 ReAct Agent 层 / 文件附件场景」，聚焦**框架层**如何接收附件、如何把附件转换成模型可消费的内容块，以及当前文本、压缩包、图片三类附件的支持边界。

一句话结论：

> **业务层负责把文件解析成 `AgentAttachment`；框架层负责把 `AgentAttachment` 转换成 `UserMessage(List<Content>)`；模型协议层负责把 `Content` 转换成 OpenAI / Anthropic 各自的报文结构。**

---

## 文档导航

| 文档 | 说明 |
|---|---|
| 当前文章 | 框架层附件 SDK、核心执行链路、协议转换、文本/压缩包/图片支持矩阵、多模态模型匹配与演进 |
| [Agent 会话文件上传与图片识图消息设计实现.md](../../agentforge-service核心业务模块/后端/agent核心设计实现/Agent页面会话对话交互/文件上传/Agent会话文件上传与图片识图消息设计实现.md) | 业务层上传、附件归属校验、会话快照与前端交互 |

<br/>

## 目录

- [一、设计范围：框架层只做三件事](#一设计范围框架层只做三件事)
- [二、核心对象与分层职责](#二核心对象与分层职责)
- [三、同步提交执行 SDK 使用方式](#三同步提交执行-sdk-使用方式)
- [四、核心执行链路](#四核心执行链路)
- [五、附件类型与当前支持情况](#五附件类型与当前支持情况)
- [六、文本、压缩包、图片的场景处理](#六文本压缩包图片的场景处理)
- [七、内部内容模型与协议转换设计](#七内部内容模型与协议转换设计)
- [八、多模态模型匹配与映射维护](#八多模态模型匹配与映射维护)
- [九、扩展点与后续演进](#九扩展点与后续演进)
- [十、验证清单与已知边界](#十验证清单与已知边界)

---

## 一、设计范围：框架层只做三件事

附件能力天然横跨三层：

```text
业务层（agentforge-service）
  上传文件、落库、归属校验、读取文本正文、决定载荷形态
        │
        ▼
框架层（agentforge-agent-core）
  接收 AgentAttachment，组装 UserMessage(List<Content>)
        │
        ▼
模型协议层（agentforge-model-*）
  把 Content 序列化为 OpenAI / Anthropic 协议报文
```

框架层的职责边界非常明确：

1. **不负责上传**：文件从哪来、存哪、URL 怎么生成，由业务层决定；
2. **不负责正文解析**：txt/md 读取、12000 字符截断、zip 不解压，都在业务层完成；
3. **只负责内容装配与降级**：把文本、图片、文件引用装进 `UserMessage`，并在单个附件异常时降级而不阻断整轮对话。

因此，框架层可以独立于存储实现使用：只要调用方能够提供 `AgentAttachment`，就能让任意 ReAct Agent 具备附件输入能力。

<br/>

## 二、核心对象与分层职责

### 2.1 领域对象

| 对象 | 位置 | 职责 |
|---|---|---|
| `AgentRequest` | `agent/domain/AgentRequest.java` | 单次运行请求，新增 `attachments` 字段 |
| `AgentChatContext` | `agent/domain/AgentChatContext.java` | 运行期上下文，`getAttachments()` 直接读取请求附件 |
| `AgentAttachment` | `agent/domain/AgentAttachment.java` | 框架层附件模型：文件信息 + 载荷形态 + 可选正文 |
| `AgentAttachmentType` | `agent/domain/AgentAttachmentType.java` | 业务分类：`IMAGE`、`TEXT`、`UNKNOWN` |
| `AttachmentPayloadType` | `agent/domain/AttachmentPayloadType.java` | 下发形态：`URL`、`INLINE`、`TEXT` |
| `ImageLoader` | `agent/domain/ImageLoader.java` | 图片二进制加载抽象，隔离本地磁盘/HTTP/OSS |
| `ImageContent` | `model/chat/message/ImageContent.java` | 模型层图片内容块，支持 URL 与 Base64 |
| `TextContent` | `model/chat/message/TextContent.java` | 模型层文本内容块 |
| `UserMessage` | `model/chat/message/UserMessage.java` | 多内容用户消息，内部持有 `List<Content>` |

### 2.2 `AgentAttachment` 字段

```java
public final class AgentAttachment {
    private final Long fileId;                 // 业务文件主键，仅用于追踪
    private final String name;                 // 展示名
    private final AgentAttachmentType type;    // IMAGE / TEXT / UNKNOWN
    private final String fileType;             // png、txt、zip...
    private final Long size;                   // 字节数
    private final String url;                  // 可直接访问地址
    private final AttachmentPayloadType payloadType; // URL / INLINE / TEXT
    private final String text;                 // TEXT 载荷时由业务层准备好的正文或元信息
}
```

关键约定：

- `payloadType` 为空时默认 `TEXT`；
- `type` 为空时默认 `UNKNOWN`；
- `TEXT` 载荷优先使用 `text`；`text` 为空时框架层会用 `url` 生成引用文本；
- `URL` / `INLINE` 只对 `IMAGE` 生效；
- 框架层不修改 `AgentAttachment`，只读取。

<br/>

## 三、同步提交执行 SDK 使用方式

### 3.1 最小调用：文本 + 图片

```java
AgentRequest request = AgentRequest.builder()
        .memoryId("session-001")
        .executionId("exec-001")
        .question("请描述这张图片的内容")
        .attachments(Arrays.asList(
                AgentAttachment.builder()
                        .fileId(1024L)
                        .name("报错截图.png")
                        .type(AgentAttachmentType.IMAGE)
                        .fileType("png")
                        .size(183462L)
                        .url("http://127.0.0.1:8080/upload/1/8f3c.png")
                        .payloadType(AttachmentPayloadType.INLINE)
                        .build()
        ))
        .build();

ChatResult result = agent.run(request);
```

`agent.run()` 是同步执行入口；流式执行使用同一个 `AgentRequest`：

```java
TokenStream stream = agent.runStream(request);
```

### 3.2 文本附件：由业务层准备 `text`

```java
AgentAttachment textAttachment = AgentAttachment.builder()
        .fileId(2048L)
        .name("需求说明.md")
        .type(AgentAttachmentType.TEXT)
        .fileType("md")
        .size(8421L)
        .url("http://127.0.0.1:8080/upload/1/需求说明.md")
        .payloadType(AttachmentPayloadType.TEXT)
        .text("以下是附件正文内容：\n...")
        .build();
```

框架层不会再读文件；如果业务层没有给出 `text`，框架层只把文件名、类型、大小和 `url` 作为引用文本交给模型。

### 3.3 压缩包：只提交引用

```java
AgentAttachment zipAttachment = AgentAttachment.builder()
        .fileId(4096L)
        .name("日志包.zip")
        .type(AgentAttachmentType.UNKNOWN)
        .fileType("zip")
        .size(1048576L)
        .url("http://127.0.0.1:8080/upload/1/日志包.zip")
        .payloadType(AttachmentPayloadType.TEXT)
        .text("文件：日志包.zip（类型 zip，大小 1048576 bytes），访问地址 http://127.0.0.1:8080/upload/1/日志包.zip")
        .build();
```

### 3.4 只发附件、不发文字

`AgentRequest.Builder.build()` 要求 `question` 非空。只发附件时，调用方需要注入占位问题：

```java
String question = hasText(userText) ? userText : "我上传了附件，请结合附件内容回答。";
```

业务层 `StandardReActAgentManager` 已经做了这层兜底；直接使用框架 SDK 时需要调用方自己保证。

### 3.5 业务层请求体示例

业务层面向前端时使用 `files` 集合，服务端再转换为 `AgentAttachment`：

```json
{
  "aid": "4v7qri4t4vo0",
  "sessionId": "chat-xxx",
  "message": "帮我看下这个日志包",
  "files": [
    {
      "fileId": 4096,
      "originFileName": "日志包.zip",
      "fileType": "zip",
      "visitUrl": "http://127.0.0.1:8080/upload/1/日志包.zip"
    }
  ]
}
```

服务端只信任 `fileId` 做归属校验，`originFileName` / `fileType` / `visitUrl` 用于消息快照与回放。

<br/>

## 四、核心执行链路

```text
IAgent.run(AgentRequest)
   │
   ▼
BaseAgent.init(request)
   │  创建 AgentChatContext
   │  绑定 ChatMemory / ChatModel
   ▼
Agent.run()
   │
   ▼
BaseAgent.initMessages()
   │
   ▼
BaseAgent.buildUserMessage(chatContext)
   │
   ├─ 1. 写入 question 文本块（为空则占位指令）
   ├─ 2. 遍历 attachments
   │     ├─ text 非空          -> TextContent("附件i：name\n" + text)
   │     ├─ url 为空           -> TextContent(引用信息)
   │     ├─ IMAGE + INLINE     -> ImageLoader.load(url) -> Base64 -> ImageContent
   │     ├─ IMAGE + URL        -> ImageContent.fromUrl(url)
   │     └─ 其余               -> TextContent(引用信息，含 url)
   │
   ▼
UserMessage(List<Content>) 写入 ChatMemory
   │
   ▼
ChatModel.chat(...)
   │
   ▼
OpenAiMessages / AnthropicProtocol 按协议序列化
```

### 4.1 单附件失败隔离

框架层对图片内联采用“失败降级”：

- `ImageLoader.load(url)` 抛异常；
- 记录 WARN 日志；
- 该附件降级为文字引用；
- 其他附件和整轮对话继续执行。

这样单个附件损坏不会让整个会话失败。

<br/>

## 五、附件类型与当前支持情况

### 5.1 支持矩阵

| 类型 | 具体格式 | 类型判定 | 业务层处理 | 框架载荷 | 模型侧形态 | 当前支持情况 |
|---|---|---|---|---|---|---|
| 纯文本 | `txt`、`md`、`markdown`、`text/*` | `TEXT` | 读取正文，单附件最多 12000 字符，超长截断并补 `visitUrl` | `TEXT`，`text` 非空 | 文本块 | 已支持，正文可被模型理解 |
| 图片 | `png`、`jpg`、`jpeg`、`gif`、`webp`、`bmp`、`image/*` | `IMAGE` | 生成访问地址，决定 `URL` / `INLINE` / `TEXT` | `URL` / `INLINE` / `TEXT` | `image_url` / `image(base64)` / 文本引用 | 已支持视觉模型；非视觉模型降级文字引用 |
| 压缩包 | `zip` 及其他未解析格式 | `UNKNOWN` | 不解压，只生成带 `visitUrl` 的元信息 | `TEXT` | 文本块 | 已支持“引用”，模型可提示用户下载，不解析包内容 |
| 办公文档 | `pdf`、`doc`、`docx`、`xls`、`xlsx` | `UNKNOWN` | 不解析正文，只生成元信息 | `TEXT` | 文本块 | 仅引用，正文解析待扩展 |
| 未知类型 | 其他扩展名 | `UNKNOWN` | 只生成元信息 | `TEXT` | 文本块 | 仅引用 |

### 5.2 关键限制

| 限制 | 当前值 | 说明 |
|---|---:|---|
| 单条消息附件数 | 5 | 业务层校验，框架层不重复限制 |
| 单文本附件正文 | 12000 字符 | 超出截断，并在截断标记中附带 `visitUrl` |
| 单图 INLINE 上限 | 4 MB | 超出降级为文字引用 |
| 图片 URL 可达性判断 | 私网/环回地址不可达 | `127.x`、`10.x`、`172.16-31.x`、`192.168.x`、`localhost` 走 INLINE |
| Anthropic 图片 | 强制 Base64 | 协议层不接受外链图片 |
| zip 解压 | 不支持 | 只引用访问地址，不读取包内文件 |
| PDF/Word 正文 | 不支持 | 只引用访问地址 |

<br/>

## 六、文本、压缩包、图片的场景处理

### 6.1 文本：正文优先，超长补 URL

业务层读取正文并截断，框架层直接消费 `text`：

```text
附件3：需求说明.md
以下是附件正文内容：
......正文......
...[附件内容过长，已截断，完整内容请访问：http://...]
```

适用场景：

- 需求文档、代码片段、日志文本；
- 模型需要直接阅读内容，而不是让用户再打开文件；
- 超长时保留头部内容，同时给模型一个可追溯的完整地址。

### 6.2 压缩包：不解压，只把访问地址内联

zip 被判定为 `UNKNOWN`，业务层生成如下 `text`：

```text
文件：日志包.zip（类型 zip，大小 1048576 bytes），访问地址 http://127.0.0.1:8080/upload/1/日志包.zip
```

模型收到的是文本引用，可以：

- 告知用户附件已上传；
- 建议用户下载或解压后重新上传关键文件；
- 在具备外部工具时，把该 URL 交给下载/解压工具处理。

当前框架层**不自动解压**，原因是：

1. 解压需要处理路径穿越、压缩炸弹、编码等安全问题；
2. 包内文件类型不确定，可能需要递归路由；
3. 解压产物需要临时存储与清理策略。

后续可通过 `AttachmentContentExtractor` 扩展点接入受控解压能力。

### 6.3 图片：按模型能力与 URL 可达性路由

```text
模型支持图片？
  ├─ 否 -> TEXT（文字引用）
  └─ 是
      ├─ 非图片 -> TEXT
      └─ 图片
          ├─ Anthropic -> INLINE
          ├─ URL 公网可达 -> URL
          ├─ 文件 <= 4MB -> INLINE
          └─ 其他 -> TEXT
```

三种最终形态：

| 形态 | 框架行为 | 模型报文 |
|---|---|---|
| `URL` | `ImageContent.fromUrl(url)` | OpenAI：`image_url.url` 为外链 |
| `INLINE` | `ImageLoader.load(url)` -> Base64 | OpenAI：`data:image/png;base64,...`；Anthropic：`source.base64` |
| `TEXT` | `TextContent(引用信息)` | 普通文本块 |

<br/>

## 七、内部内容模型与协议转换设计

### 7.1 内部内容模型

框架层统一使用 `UserMessage(List<Content>)`，不直接拼协议 JSON：

```text
UserMessage
 ├── TextContent      文本块
 └── ImageContent     图片块
      ├── url          外链形态
      ├── base64Data   内联形态
      ├── mimeType     内联必需
      └── detailLevel  AUTO / LOW / HIGH
```

`ContentType` 与实现类一一对应：

| `ContentType` | 实现类 | 说明 |
|---|---|---|
| `TEXT` | `TextContent` | 普通文本 |
| `IMAGE` | `ImageContent` | 图片，URL 或 Base64 二选一 |

### 7.2 OpenAI 协议转换

`OpenAiMessages.multimodalUserMessage`：

```json
{
  "role": "user",
  "content": [
    { "type": "text", "text": "请描述这张图片" },
    {
      "type": "image_url",
      "image_url": {
        "url": "data:image/png;base64,....",
        "detail": "high"
      }
    }
  ]
}
```

规则：

- `TextContent` -> `{"type":"text","text":...}`；
- `ImageContent` -> `{"type":"image_url","image_url":{"url":...}}`；
- URL 形态直接使用 `url`，Base64 形态拼成 `data:` URI；
- 未知 `Content` 类型**显式抛异常**，避免图片被静默丢弃。

### 7.3 Anthropic 协议转换

`AnthropicProtocol.anthropicImageBlock`：

```json
{
  "type": "image",
  "source": {
    "type": "base64",
    "media_type": "image/png",
    "data": "...."
  }
}
```

规则：

- Anthropic 只接受 Base64；
- 外链图片必须由路由层提前转成 `INLINE`；
- 协议层遇到 URL 形态会显式抛异常，形成双重兜底。

### 7.4 会话记忆编解码

`ChatMessageCodec` 支持 `IMAGE` 编解码：

| 字段 | 说明 |
|---|---|
| `contentType` | `TEXT` / `IMAGE` |
| `text` | 文本块内容 |
| `url` | 图片外链 |
| `base64` | 图片内联数据 |
| `mimeType` | 内联图片 MIME |
| `detailLevel` | 图片细节档位 |

编码时按实际形态保存 URL 或 Base64；解码时还原为 `ImageContent`。这样图片消息进入会话记忆后，刷新与恢复不会丢失。

### 7.5 Token 统计

图片内容不按 Base64 字符串长度计 token，而是按固定开销折算（当前实现约 1000 token/张），避免压缩阈值被 Base64 长度误触发。

<br/>

## 八、多模态模型匹配与映射维护

### 8.1 当前实现：能力注册表 + 自动路由

初始阶段采用“内部框架层自动判断”的思路：

```text
Agent 配置的模型
   │
   ▼
AgentModelCapabilityResolver
   │  读取 ModelConfigPojo -> LlmModelConfigRegistry.getConfig(baseUrl, modelName)
   ▼
LlmModelConfig
   │  supportsImage / maxInputTokens / maxOutputTokens
   ▼
DefaultAgentAttachmentRoutingService
   │  attachmentType + visitUrl + fileSize + modelConfig + providerCode
   ▼
AttachmentPayloadType
```

`LlmModelConfigRegistry.getConfig` 的匹配顺序：

1. `baseUrl + modelName` 精确匹配；
2. `modelName` 全局匹配；
3. 未命中使用默认配置（保守按不支持图片处理）。

### 8.2 当前能力字段

| 字段 | 作用 |
|---|---|
| `supportsText` | 是否支持文本输入 |
| `supportsImage` | 是否支持图片输入 |
| `isSupportsMultimodal()` | 当前等价于 `supportsImage` |
| `maxInputKTokens` | 上下文上限，供压缩与预算使用 |
| `maxOutputKTokens` | 输出上限 |

### 8.3 自动映射决策表

| 条件 | 结果 | 原因 |
|---|---|---|
| 模型未注册 / `supportsImage=false` | `TEXT` | 保守降级，保证请求不报错 |
| 附件不是图片 | `TEXT` | 文本、zip、办公文档统一走文本引用 |
| Anthropic + 图片 | `INLINE` | 协议不支持外链图片 |
| 公网可达 URL + 图片 | `URL` | 节省带宽与 Base64 膨胀 |
| 私网 URL / 本地地址 + 图片 | `INLINE` | 模型无法访问内网地址 |
| INLINE 且文件超过 4MB | `TEXT` | 避免单图撑爆请求体 |

### 8.4 当前方案的边界

- 能力维度只有“文本 / 图片”，没有音频、视频、PDF 原生输入；
- 路由优先级固定在代码中，不能按模型定制“优先 URL 还是优先 Base64”；
- 模型能力来源是注册表，运行时新增模型需要注册配置；
- zip / PDF 等文件没有原生“文件输入”通道，只能文本引用。

### 8.5 后续演进：从布尔能力到模态能力集合

建议把 `LlmModelConfig` 从布尔字段升级为“模态能力集合 + 载荷优先级”。

#### 第一步：扩展模型能力配置

```java
public final class LlmModelConfig {
    private final EnumSet<InputModality> inputModalities; // TEXT, IMAGE, AUDIO, VIDEO, FILE
    private final EnumSet<AttachmentPayloadType> supportedPayloads; // URL, INLINE, TEXT
    private final List<AttachmentPayloadType> payloadPriority;      // 例如 URL -> INLINE -> TEXT
}
```

对应模型配置表可增加：

| 字段 | 示例 | 说明 |
|---|---|---|
| `supports_image` | `1` | 是否支持图片 |
| `supports_audio` | `0` | 是否支持音频 |
| `supports_video` | `0` | 是否支持视频 |
| `supports_file` | `0` | 是否支持原生文件 |
| `payload_priority` | `URL,INLINE,TEXT` | 该模型的载荷优先级 |

#### 第二步：路由策略可插拔

把当前 `DefaultAgentAttachmentRoutingService` 作为默认实现，新增策略扩展点：

```java
public interface AgentAttachmentRoutingStrategy {
    boolean supports(InputModality modality, LlmModelConfig config);
    AttachmentPayloadType route(RoutingContext context);
}
```

不同模型可以注册自己的策略，例如：

- Anthropic：图片强制 `INLINE`；
- 某模型支持原生文件：zip 直接走 `FILE` 载荷；
- 某模型只支持文本：所有附件统一 `TEXT`。

#### 第三步：动态注册与发现

- 管理后台新增模型时，同步写入模态能力字段；
- 启动时把数据库模型配置注册进 `LlmModelConfigRegistry`；
- 运行时新增模型无需改代码，只需补配置；
- 未配置能力的模型继续走默认保守策略。

#### 第四步：文件解析扩展

新增 `AttachmentContentExtractor` 策略：

```java
public interface AttachmentContentExtractor {
    boolean supports(String fileType);
    ExtractedContent extract(InputStream input, ExtractOptions options);
}
```

可逐步接入：

| 扩展 | 能力 |
|---|---|
| PDF 文本提取 | `pdf` -> TEXT 正文 |
| Word 文本提取 | `docx` -> TEXT 正文 |
| Excel 表格提取 | `xlsx` -> 结构化文本 |
| zip 受控解压 | 解压后按文件类型递归路由 |
| 图片 OCR | 图片 -> 文本补充 |

#### 第五步：能力协商与降级顺序

```text
原生文件输入（FILE）
   ↓ 不支持
图片/音频/视频原生输入
   ↓ 不支持
URL 外链
   ↓ 不可达
INLINE Base64
   ↓ 超限
TEXT 正文/引用
```

最终目标：**模型配置声明能力，路由层按能力协商，协议层只负责序列化，任何一层缺失都能安全降级。**

<br/>

## 九、扩展点与后续演进

| 扩展点 | 当前实现 | 后续方向 |
|---|---|---|
| `ImageLoader` | `LocalFirstImageLoader` 本地优先 | OSS/S3 直读、带缓存与限流 |
| `AgentAttachmentRoutingService` | 固定决策树 | 策略化、按模型配置优先级 |
| `LlmModelConfig` | 文本 + 图片布尔能力 | 模态集合 + 载荷优先级 |
| `AttachmentContentExtractor` | 未实现 | PDF/Word/Excel/zip/OCR |
| `ChatMessageCodec` | 支持 TEXT/IMAGE | 支持 AUDIO/VIDEO/FILE |
| 协议适配器 | OpenAI / Anthropic 图片 | 音频、视频、原生文件字段 |

<br/>

## 十、验证清单与已知边界

### 10.1 功能验证

| 场景 | 预期 |
|---|---|
| 视觉模型 + 公网 URL + PNG | 路由为 `URL`，OpenAI 报文含 `image_url.url` |
| 视觉模型 + 本地地址 + PNG | 路由为 `INLINE`，报文含 Base64 |
| Anthropic + PNG | 强制 `INLINE`，报文为 `source.base64` |
| 非视觉模型 + PNG | 路由为 `TEXT`，模型收到文字引用且不报错 |
| 文本附件 <= 12000 字符 | 正文完整进入文本块 |
| 文本附件 > 12000 字符 | 截断并附带 `visitUrl` |
| zip 附件 | `TEXT` 元信息包含 `visitUrl`，不解压 |
| 单图 > 4MB | 降级 `TEXT`，不阻断对话 |
| 图片加载失败 | WARN 日志 + 文字引用降级 |
| 只发附件不发文字 | 业务层注入占位问题后正常执行 |

### 10.2 已知边界

- 框架层不校验附件归属，归属校验属于业务层；
- 框架层不限制附件数量，数量上限属于业务层；
- zip 不自动解压，包内文件不可见；
- PDF/Word/Excel 不解析正文；
- 图片 INLINE 会增加请求体体积，需要网关同步放宽限制；
- 多文本附件仍可能叠加放大上下文，后续应增加单条消息总字符预算。

### 10.3 关键代码索引

| 能力 | 代码位置 |
|---|---|
| 附件领域对象 | `agentforge-framework/agentforge-agent-core/.../agent/domain/AgentAttachment.java` |
| 多内容消息装配 | `agentforge-framework/agentforge-agent-core/.../agent/BaseAgent.java` |
| 图片内容块 | `agentforge-model/agentforge-model-api/.../model/chat/message/ImageContent.java` |
| OpenAI 协议 | `agentforge-model/agentforge-model-openai/.../openai/OpenAiMessages.java` |
| Anthropic 协议 | `agentforge-model/agentforge-model-anthropic/.../anthropic/AnthropicProtocol.java` |
| 记忆编解码 | `agentforge-framework/agentforge-agent-core/.../agent/memory/store/ChatMessageCodec.java` |
| 模型能力配置 | `agentforge-framework/agentforge-agent-core/.../agent/config/LlmModelConfig.java` |
| 模型注册表 | `agentforge-framework/agentforge-agent-core/.../agent/config/LlmModelConfigRegistry.java` |
| 业务层附件解析 | `agentforge-service/.../agent/attachment/AgentChatAttachmentService.java` |
| 默认载荷路由 | `agentforge-service/.../agent/attachment/impl/DefaultAgentAttachmentRoutingService.java` |

---

## 十一、总结

1. 框架层附件能力的核心是 `AgentAttachment -> UserMessage(List<Content>)`，不掺杂上传与存储细节；
2. 文本走正文、图片走多模态、zip/办公文档走带 URL 的文本引用，是当前阶段最稳妥的三分法；
3. OpenAI 与 Anthropic 的图片协议差异由内部 `ImageContent` 吸收，协议层对未知类型显式失败；
4. 多模态匹配由 `LlmModelConfigRegistry + 路由服务` 自动完成，后续通过扩展模态能力集合、载荷优先级与解析策略实现动态演进。

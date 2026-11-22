# Agent 会话文件上传与图片识图消息设计与实现

> 文档定位：描述「上传文件落盘 → 附件解析 → 按模型能力路由载荷 → 包装为多模态 UserMessage → 协议序列化 → 会话持久化」这条完整链路。
>
> 实现范围：`agentforge-model-api` + `agentforge-model-openai` + `agentforge-model-anthropic` + `agentforge-agent-core` + `agentforge-service-web` + `agentforge-service-ui`。
>
> 参考实现：`agentx` 仓库的 `BaseAgent.buildUserMessage` / `AgentChatAttachmentService` / `DefaultAgentAttachmentRoutingService` / `ModelConfigRegistry`。
>
> 当前状态：本文分两部分阅读。**第 3~4 章是 as-built（仓库当前已存在的实现）**，**第 5 章之后是 to-be（本方案要新增的实现）**，两者不混淆。

---

## 1. 背景与目标

Agent 会话页当前只支持纯文本对话：`POST /api/agent/chat` 与 `POST /api/chat/stream` 的请求体只有 `message` 字段，用户上传的文件无法进入模型上下文。要让 Agent 具备「看图」和「读文件」能力，必须解决三件事：

1. **文件先落盘并可以被再次引用**：上传接口要返回稳定标识，会话才能把「这条消息带了哪几个附件」持久化下来；
2. **附件要有明确的载荷形态**：同一张图片，对视觉模型可以传 URL 或 Base64，对纯文本模型只能退化为文字引用；
3. **图片要能被真正包装进 `UserMessage`**：不是塞进 prompt 字符串，而是作为 `Content` 内容块进入模型请求，并在 OpenAI / Anthropic 两套协议下正确序列化。

本方案的目标：

- 文件上传默认目录、URL 生成规则、存储布局有唯一事实来源，可直接对照配置排障；
- 图片按模型能力自动选择 **URL** / **INLINE(Base64)** / **TEXT(引用)** 三种载荷，内网地址自动降级为 Base64；
- 能力判断统一走 `LlmModelConfigRegistry`，不在业务代码里散落模型白名单；
- 会话消息表可回放附件卡片，刷新页面不丢附件；
- 对既有纯文本链路零破坏：多内容消息落库、压缩、恢复路径全部兼容。

## 2. 名词约定

| 名词 | 含义 |
| --- | --- |
| 附件（Attachment） | 用户随一条消息上传的文件，包含展示信息与可访问地址 |
| 载荷形态（PayloadType） | 附件真正下发给模型时的形态：`URL` 外链、`INLINE` 内联 Base64、`TEXT` 纯文本引用 |
| 附件类型（AttachmentType） | 业务分类：`IMAGE`、`TEXT`、`UNKNOWN` |
| 多模态消息 | `UserMessage` 内含多个 `Content`（文本块 + 图片块） |
| 视觉模型能力 | 由 `LlmModelConfig.supportsImage` 表达，注册表统一维护 |

---

## 3. 现状盘点（as-built）：上传链路已有什么

### 3.1 上传模块结构

文件上传已是一个独立业务模块，位于：

```text
agentforge-service/agentforge-service-backend/agentforge-service-web/src/main/java/cloud/changlu/agentforge/service/modules/upload
├── config/LocalUploadProperties.java        # 存储根目录、URL 前缀、大小上限
├── config/UploadWebMvcConfiguration.java    # /upload/** 静态映射 + Multipart 限制
├── controller/FileController.java           # POST /api/file/upload
├── domain/vo/UploadFileVo.java              # 上传返回体
├── service/FileUploadService.java           # 存储抽象
└── service/impl/LocalFileUploadServiceImpl.java  # 本地磁盘实现
```

### 3.2 上传接口与返回体

```java
@RestController
@RequestMapping("/api/file")
public class FileController {

    private static final long DEFAULT_USER_ID = 1L;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadFileVo upload(@RequestParam("file") MultipartFile file) {
        return fileUploadService.upload(file, currentUserId());
    }
}
```

返回体 `UploadFileVo` 有 5 个字段：

| 字段 | 含义 | 示例 |
| --- | --- | --- |
| `visitUrl` | 可直接访问的完整地址 | `http://127.0.0.1:8080/upload/1/8f3c....png` |
| `fileName` | 相对存储路径（同时是物理文件名） | `1/8f3c....png` |
| `originFileName` | 用户上传时的原始文件名 | `截图 2026-11-20.png` |
| `fileType` | 归一化后的扩展名（小写，不含点） | `png` |
| `fileSize` | 字节数 | `183462` |

### 3.3 当前落盘规则

```java
@Override
public UploadFileVo upload(MultipartFile file, Long userId) {
    validateFile(file);
    validateUserId(userId);

    String originFileName = normalizeOriginalFileName(file.getOriginalFilename());
    String fileType = extractFileType(originFileName);
    String storageFileName = UUID.randomUUID().toString().replace("-", "") + extension(fileType);
    Path relativeFile = Paths.get(String.valueOf(userId), storageFileName);
    Path targetFile = properties.getStorageRoot().resolve(relativeFile).normalize();
    ensureInsideStorageRoot(targetFile);
    ...
}
```

关键点：

- 相对路径固定为 `{userId}/{uuid32}{.ext}`，按用户分目录；
- 物理文件名与用户原始文件名解耦，避免重名覆盖与路径穿越；
- `ensureInsideStorageRoot` 做一次规范化后的前缀校验，防 `../` 逃逸；
- 扩展名经过 `[A-Za-z0-9]{1,16}` 白名单校验，非法扩展名直接丢空。

### 3.4 静态访问映射

```java
@Override
public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry.addResourceHandler(properties.getResourcePattern())   // /upload/**
            .addResourceLocations(properties.getStorageRoot().toUri().toString());
}
```

`visitPath + "/**"` 映射到存储根目录，因此 `visitUrl` 与磁盘路径是一一对应的。这也是后续「把 `visitUrl` 反解成磁盘文件」的基础。

### 3.5 前端已有上传能力

`src/utils/fileUpload.js` 已封装标准上传：

```javascript
export async function uploadFile(file) {
  const formData = new FormData()
  formData.append('file', file)
  const response = await fetch('/api/file/upload', { method: 'POST', body: formData })
  ...
  return response.json()
}
```

`src/components/ImageUploadField.vue` 已用它做单图上传（含类型与 10MB 前置校验），但只用于 Agent 头像等配置场景，**尚未接入会话输入框**。

### 3.6 现状结论

上传层已经具备「落盘 + 暴露 URL + 返回元信息」三项能力，**缺口全部在会话侧**：

- 请求 DTO 没有附件字段；
- 没有附件归属校验与文件元数据表；
- 模型消息里没有图片内容类型；
- 会话消息表没有附件快照列。

---

## 4. 现状盘点（as-built）：消息与模型能力层

### 4.1 `UserMessage` 已支持多内容，但内容类型只有文本

`UserMessage` 的构造与访问已经按 LangChain4j 的多内容模型实现：

```java
public UserMessage(List<Content> contents) { ... }

public List<Content> contents() { return contents; }

@Override
public String text() {
    if (hasSingleText()) {
        return ((TextContent) contents.get(0)).text();
    }
    throw new UnsupportedOperationException(
            "UserMessage does not expose a single text payload; use contents() instead");
}
```

注意 `text()` 的语义：**只有单条 `TextContent` 时才可调用，否则抛异常**。这是后续所有兼容性改造的根源。

`ContentType` 目前只有一个枚举值：

```java
public enum ContentType {
    TEXT(TextContent.class);
}
```

因此「图片入消息」的框架层改造，最小集合是：新增 `ImageContent` + 在 `ContentType` 增加 `IMAGE`。

### 4.2 OpenAI 协议：多模态分支已存在，但只处理文本块

`OpenAiMessages.serialize` 已经为「非单文本的 `UserMessage`」预留了多模态分支：

```java
} else if (message instanceof UserMessage && !((UserMessage) message).hasSingleText()) {
    result.add(multimodalUserMessage((UserMessage) message));
}
```

但 `multimodalUserMessage` 内部只识别 `TextContent`，遇到其他类型会被静默跳过：

```java
static Map<String, Object> multimodalUserMessage(UserMessage message) {
    for (Content content : contents) {
        if (content instanceof TextContent) {
            block.put("type", "text");
            block.put("text", ((TextContent) content).text());
            blocks.add(block);
        }
    }
    ...
}
```

**风险点**：如果只新增了 `ImageContent` 而没有补分支，图片会被直接丢弃且不报错——模型只会收到文字，表现为「上传成功但模型说看不到图」。因此适配器必须显式补齐并针对未知类型抛异常。

### 4.3 Anthropic 协议：同样只处理文本块

```java
static Object userContent(UserMessage message) {
    if (message.hasSingleText()) {
        return message.text();
    }
    for (Content content : contents) {
        if (content instanceof TextContent) {
            block.put("type", "text");
            block.put("text", ((TextContent) content).text());
            blocks.add(block);
        }
    }
    return blocks;
}
```

Anthropic 的图片块结构与 OpenAI 不同：

- OpenAI：`{"type":"image_url","image_url":{"url":"data:image/png;base64,..."}}`
- Anthropic：`{"type":"image","source":{"type":"base64","media_type":"image/png","data":"..."}}`

所以两套适配器不能共用同一个序列化实现，但可以共用同一个 `ImageContent` 领域对象。

### 4.4 记忆编解码：只认 TEXT，遇到图片直接抛异常

```java
private void putUserMessage(Map<String, Object> map, UserMessage message) {
    if (message.hasSingleText()) {
        map.put(FIELD_TEXT, message.text());
        return;
    }
    for (Content content : message.contents()) {
        encoded.put(FIELD_CONTENT_TYPE, content.type().name());
        if (!(content instanceof TextContent)) {
            throw new IllegalArgumentException(
                    "Unsupported chat content type: " + content.type());
        }
        encoded.put(FIELD_TEXT, ((TextContent) content).text());
    }
}
```

解码侧同样只有 `TEXT` 分支：

```java
private Content decodeContent(Object value) {
    if (ContentType.TEXT.name().equals(contentType)) {
        return new TextContent(Json.string(map.get(FIELD_TEXT)));
    }
    throw new IllegalStateException("Unsupported chat content type: " + contentType);
}
```

这条链路直接决定：**只要 `UserMessage` 里有图片，且记忆使用 MySQL 存储，第二轮对话就会因编解码异常导致记忆丢失**（`MysqlChatMemoryStore` 对 `decodeAll` 异常是 catch 后返回空列表，表现为「聊两句就失忆」）。因此编解码改造不是可选优化，而是必需项。

### 4.5 `LlmModelConfigRegistry` 已有视觉能力标记，但无人使用

注册表本身已经区分了「文本模型」和「视觉模型」：

```java
private static LlmModelConfig text(String model, int input, int output) {
    return config(null, model, input, output, false);
}

private static LlmModelConfig image(String model, int input, int output) {
    return config(null, model, input, output, true);   // supportsImage = true
}
```

`LlmModelConfig` 也提供了语义化访问器：

```java
public boolean isSupportsImage() { return supportsImage; }
public boolean isSupportsMultimodal() { return supportsImage; }
```

例如 `gpt-5.5`、`qwen3.7-plus`、`kimi-k2.6` 等被注册为 `image(...)`，而 `deepseek-v4-pro`、`glm-5` 等是 `text(...)`。

**但全局搜索 `isSupportsImage` / `isSupportsMultimodal` 在业务代码中没有任何调用点**。`ReActAgentFactory` 只使用了 `getMaxInputTokens` 和 `getMaxOutputTokens`：

```java
LlmModelConfig modelAdvancedConfig =
        MODEL_CONFIG_REGISTRY.getConfig(llmConfig.getUrl(), llmConfig.getModelName());
...
.maxTokens(toActualTokens(chatModelConfig.getMaxOutputKTokens(),
                          modelAdvancedConfig.getMaxOutputTokens()))
```

这正是本方案要接上的关键一环：**能力信息已经在注册表里，只需要在附件路由时读出来用**。

### 4.6 会话消息表没有附件列

```sql
CREATE TABLE IF NOT EXISTS ai_agent_session_message (
    id, session_id, seq_num, role_type, status,
    started_at, finished_at, elapsed_ms,
    content, events,
    create_user_id, is_deleted, create_time, modify_time
);
```

`content` 只存文本，`events` 存前端事件快照。没有附件相关字段，因此历史回放时无法渲染附件卡片。

同时仓库**没有文件元数据表**（现有表清单中无 `ai_file_info` 之类的结构），上传接口也不落库，只返回 `visitUrl`。这意味着附件归属校验缺少数据基础。

### 4.7 现状结论表

| 能力 | 现状 | 结论 |
| --- | --- | --- |
| 文件落盘 | 已实现，`{userId}/{uuid}.{ext}` | 可直接复用 |
| 静态访问 URL | 已实现，`/upload/**` | 可直接复用 |
| 文件元数据落库 | 无 | **需新增** |
| 附件归属校验 | 无 | **需新增** |
| `ImageContent` 内容类型 | 无 | **需新增** |
| OpenAI 图片序列化 | 分支存在但只处理文本 | **需补齐** |
| Anthropic 图片序列化 | 只处理文本 | **需补齐** |
| 记忆编解码图片支持 | 遇到图片抛异常 | **需补齐（必修）** |
| 模型视觉能力判断 | 注册表已有，无人调用 | **需接入** |
| 会话消息附件快照 | 无 | **需新增** |
| 前端会话附件入口 | 无 | **需新增** |

---

## 5. 上传文件默认目录路径规范（to-be 固化）

### 5.1 目录解析的真实链路

默认目录不是硬编码常量，而是**三级回退**：

```text
① 显式环境变量 AGENTFORGE_FILE_STORAGE_PATH
        ↓ 未设置
② 系统属性 user.dir.upload
        ↓ 未设置
③ 兜底常量 DEFAULT_UPLOAD_DIR = "./upload"
```

对应代码：

```java
/** Physical root directory used by the local file storage implementation. */
public String getFileLocalStoragePath() {
    return environment.getProperty("agentforge.file.local.storage-path", getUserDirUpload());
}

/** Upload directory resolved before Spring starts, with a standalone fallback. */
public String getUserDirUpload() {
    return environment.getProperty("user.dir.upload", DEFAULT_UPLOAD_DIR);
}
```

而 `user.dir.upload` 由 `SystemPropertyUtil` 在应用启动前写入系统属性，值是**绝对路径且以分隔符结尾**：

```java
/** 本地文件上传目录。 */
public static void setLocalUploadFileDir() {
    setCustomDir("upload");
}

public static void setCustomDir(String dirName) {
    File resolved = new File(serviceHome(), dirName);
    if (!resolved.exists() && !resolved.mkdirs()) {
        log.warn("Failed to create {} directory: {}", dirName, resolved.getAbsolutePath());
    }
    String propertyName = "user.dir." + dirName;
    String path = resolved.getAbsolutePath() + File.separator;
    System.setProperty(propertyName, path);
    log.info("{}{} path: {}", dirName.substring(0, 1).toUpperCase(), dirName.substring(1), path);
}
```

**目录在启动时就会被创建**，所以「第一次上传报目录不存在」这类问题应优先怀疑 `serviceHome()` 解析错了层级，而不是权限问题。

### 5.2 `serviceHome()` 的三段探测

`serviceHome()` 决定 `upload` 目录最终挂在哪个父目录下，规则是「找包含 `conf` 目录的那一层」：

```java
private static File resolveServiceHome() {
    String base = System.getProperty("user.dir");

    if (new File(base, CONF_DIR).exists()) {
        return new File(base);                                   // ① 当前目录就是服务主目录
    }
    if (new File(base, SERVICE_DIR + File.separator + CONF_DIR).exists()) {
        return new File(base, SERVICE_DIR);                       // ② 当前目录下存在 agentforge-service/conf
    }
    File parent = new File(base).getParentFile();
    if (parent != null && new File(parent, CONF_DIR).exists()) {
        return parent;                                            // ③ 上一级目录存在 conf
    }
    return new File(base);                                        // ④ 兜底：就用当前目录
}
```

对应到实际启动方式：

| 启动方式 | `user.dir` | `serviceHome()` | 上传目录 |
| --- | --- | --- | --- |
| 在 `agentforge-service/` 下启动 | `.../agentforge-service` | `.../agentforge-service` | `.../agentforge-service/upload` |
| 在仓库根目录启动（脚本方式） | `.../AgentForge` | `.../AgentForge/agentforge-service` | `.../AgentForge/agentforge-service/upload` |
| 在 `agentforge-service-web/` 模块目录启动 | `.../agentforge-service-web` | 上一级（若含 `conf`） | 视探测结果而定 |
| IDE 直接运行 main | 通常为模块目录 | 按 ①②③④ 顺序探测 | 以启动日志 `Upload path:` 为准 |

**排障约定**：启动日志会打印 `Upload path: /绝对路径/`，这是唯一权威值。排查「文件传上去了但访问 404」时，先比对日志路径与 `visitUrl` 中的相对路径。

### 5.3 配置项清单

`application.yml` 中的完整配置：

```yaml
agentforge:
  file:
    max-size: ${AGENTFORGE_FILE_MAX_SIZE:10MB}
    local:
      # Upload responses use these values to build a directly accessible visitUrl.
      storage-path: ${AGENTFORGE_FILE_STORAGE_PATH:${user.dir.upload}}
      visit-protocol: ${AGENTFORGE_FILE_VISIT_PROTOCOL:http}
      visit-host: ${AGENTFORGE_FILE_VISIT_HOST:127.0.0.1}
      visit-port: ${AGENTFORGE_FILE_VISIT_PORT:${server.port}}
      visit-path: ${AGENTFORGE_FILE_VISIT_PATH:/upload}
```

| 配置项 | 环境变量 | 默认值 | 作用 |
| --- | --- | --- | --- |
| `storage-path` | `AGENTFORGE_FILE_STORAGE_PATH` | `${user.dir.upload}` | 磁盘物理根目录 |
| `visit-protocol` | `AGENTFORGE_FILE_VISIT_PROTOCOL` | `http` | 拼接 `visitUrl` 的协议 |
| `visit-host` | `AGENTFORGE_FILE_VISIT_HOST` | `127.0.0.1` | 拼接 `visitUrl` 的主机 |
| `visit-port` | `AGENTFORGE_FILE_VISIT_PORT` | `${server.port}` | 拼接 `visitUrl` 的端口 |
| `visit-path` | `AGENTFORGE_FILE_VISIT_PATH` | `/upload` | 静态映射前缀 |
| `max-size` | `AGENTFORGE_FILE_MAX_SIZE` | `10MB` | 单文件上限 |

### 5.4 URL 拼接与反向解析

正向拼接（`LocalUploadProperties`）：

```java
public String buildVisitUrl(String relativePath) {
    String normalizedPath = relativePath.replace('\\', '/');
    return visitBaseUrl + visitPath + "/" + normalizedPath;
}
```

`visitBaseUrl` 由 `buildVisitBaseUrl` 组装，并对 IPv6 主机做方括号包裹，对非法协议/端口直接抛异常：

```java
private static String buildVisitBaseUrl(String protocol, String host, String port) {
    if (!StringUtils.hasText(protocol) || !protocol.trim().matches("(?i)https?")
            || !StringUtils.hasText(host)
            || !StringUtils.hasText(port) || !port.trim().matches("\\d{1,5}")) {
        throw new IllegalArgumentException("Invalid local upload visit address configuration");
    }
    ...
}
```

**反向解析**是本方案新增的能力：把 `visitUrl` 还原为磁盘文件，供 INLINE 转 Base64 与文本附件读取使用。落地位置建议放在 `LocalUploadProperties`：

```java
/**
 * 把 visitUrl 反解为存储根目录下的绝对路径；不属于本服务的地址返回 null。
 *
 * <p>只接受与 visitBaseUrl + visitPath 前缀完全匹配的地址，避免把外部 URL 当作本地文件读取。
 */
public Path resolveLocalPath(String visitUrl) {
    if (!StringUtils.hasText(visitUrl)) {
        return null;
    }
    String prefix = visitBaseUrl + visitPath + "/";
    if (!visitUrl.startsWith(prefix)) {
        return null;
    }
    String relative = visitUrl.substring(prefix.length());
    // 去掉可能存在的查询串
    int queryIndex = relative.indexOf('?');
    if (queryIndex >= 0) {
        relative = relative.substring(0, queryIndex);
    }
    if (!StringUtils.hasText(relative)) {
        return null;
    }
    Path resolved = storageRoot.resolve(relative).normalize();
    return resolved.startsWith(storageRoot) ? resolved : null;
}
```

这个方法后面被三处复用：INLINE 图片转 Base64、TEXT 附件读正文、附件删除清理。

### 5.5 存储布局约定

最终磁盘结构：

```text
{storageRoot}/
└── {userId}/
    ├── 8f3c2a1b9d4e4c6f8a7b5d3e1f0c2a4b.png
    ├── 1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d.pdf
    └── ...
```

本方案新增一条会话维度的预留目录约定，用于后续按会话清理附件，**初版不强制启用**：

```text
{storageRoot}/{userId}/{yyyyMMdd}/{uuid}.{ext}      # 方案 B：按日期分桶，避免单目录文件过多
```

> 决策：初版保持现有 `{userId}/{uuid}.{ext}` 不变，避免历史 `visitUrl` 失效。日期分桶作为后续容量治理项，需要配套历史文件迁移脚本，不在本次范围内。

### 5.6 Docker 部署下的目录

```yaml
volumes:
  - ./upload:/opt/agentforge/upload
```

容器内 `user.dir.upload` 解析为 `/opt/agentforge/upload/`，通过宿主 `./upload` 挂载持久化。因此容器重建不会丢附件，但**换宿主机目录会导致历史 `visitUrl` 仍然可用、物理文件却已丢失**，需要运维保证挂载目录稳定。

---

## 6. 附件端到端链路设计

### 6.1 总览

```text
[前端]  选择/粘贴图片
   │  multipart/form-data
   ▼
POST /api/file/upload  ──────────────► 落盘 {userId}/{uuid}.{ext}
   │                                    写 ai_file_info 元数据（新增）
   ▼  { fileId, visitUrl, fileName, fileType, fileSize }
[前端]  携带 files + message 发起对话
   │  files: [{ fileId, originFileName, fileType, visitUrl }]
   │  POST /api/agent/chat  或  POST /api/chat/stream
   ▼
[Service] 按 fileId 校验归属 → 组装 AgentAttachment（含 type / payloadType）
   │
   ▼
[Agent]  buildUserMessage：文本块 + 图片块 → UserMessage(List<Content>)
   │
   ▼
[Model]  OpenAI: image_url  /  Anthropic: source.base64
   │
   ▼
[落库]   ai_agent_memory（ChatMessageCodec 支持 IMAGE）
         ai_agent_session_message.req（用户请求快照，含 files）
```

### 6.2 三个必须前置决策的边界

**① 附件标识用 `fileId` 还是 `visitUrl`？**

`agentx` 用 `fileIds` + `sys_file_info` 表，好处是能做归属校验、能改名、能统计引用。AgentForge 目前没有文件表。

结论：**新增 `ai_file_info` 元数据表**，对话请求传 `files` 集合，每一项包含：

```json
{
  "fileId": 1024,
  "originFileName": "报错截图.png",
  "fileType": "png",
  "visitUrl": "http://127.0.0.1:8080/upload/1/8f3c.png"
}
```

服务端**只把 `fileId` 作为附件解析与归属校验依据**，`originFileName` / `fileType` / `visitUrl` 用于消息快照与历史回显。理由：

- 只用 `visitUrl` 无法校验「这条附件是不是当前用户传的」，存在越权读取风险；
- 后续要做附件删除、按会话清理、引用计数，都需要主键；
- `visitUrl` 里的 `userId` 是目录名，属于实现细节，不应作为鉴权依据；
- 消息回放只需要一次请求快照即可还原文件名、类型与缩略图地址，无需 N+1 回查文件表。

**② 图片传 URL 还是 Base64？**

取决于模型能否访问该 URL。本地部署的 `visitHost` 默认是 `127.0.0.1`，公网模型**绝对访问不到**。因此：

- URL 可达 → 传 URL（省 token、省带宽）；
- URL 不可达（localhost / 127.x / 10.x / 172.16-31.x / 192.168.x）→ 转 Base64；
- 模型不支持图片 → 退化为文字引用。

这个判断逻辑与 `agentx` 的 `DefaultAttachmentUrlAccessibilityResolver` 完全一致，本方案直接移植。

**③ 多张图片是否一次性全传？**

需要设上限。否则一张长截图或十张图会把上下文直接打满。

结论：**单条消息最多 5 张图片，单张 INLINE 上限 4MB，超限降级为文字引用并给出提示**。

### 6.3 载荷路由规则

路由输入：附件类型 + 模型能力 + URL 可达性。输出：`PayloadType`。

```text
                    ┌─────────────────────────┐
                    │ 模型 supportsImage ?    │
                    └───────┬─────────────────┘
                     否 ────┴──► TEXT（文字引用）
                     是
                      │
              ┌───────▼────────┐
              │ 附件是 IMAGE ? │
              └───┬────────────┘
              否  │           是
              TEXT│           │
                  │    ┌──────▼──────────────────┐
                  │    │ visitUrl 公网可达 ?     │
                  │    └──┬──────────────────────┘
                  │    是 │            否
                  │   URL │            │
                  │       │      ┌─────▼──────────┐
                  │       │      │ 文件 ≤ 4MB ?   │
                  │       │      └──┬─────────────┘
                  │       │      是 │        否
                  │       │     INLINE│      TEXT
                  └───────┴─────────┴──────────┘
```

对应枚举（与 `agentx` 对齐）：

```java
public enum AttachmentPayloadType {
    URL,      // 直接把外链交给模型
    INLINE,   // 本地读文件转 Base64 内联
    TEXT      // 退化为文字引用
}

public enum AgentAttachmentType {
    IMAGE, TEXT, UNKNOWN
}
```

### 6.4 内网地址判定规则

```java
private static final Pattern PRIVATE_10  = Pattern.compile("^10\\..*");
private static final Pattern PRIVATE_172 = Pattern.compile("^172\\.(1[6-9]|2\\d|3[0-1])\\..*");
private static final Pattern PRIVATE_192 = Pattern.compile("^192\\.168\\..*");
private static final Pattern LOOPBACK_127 = Pattern.compile("^127\\..*");

public boolean isAccessible(String visitUrl) {
    URI uri = new URI(visitUrl.trim());
    String host = uri.getHost();
    if ("localhost".equalsIgnoreCase(host)) return false;
    if (LOOPBACK_127.matcher(host).matches()
            || PRIVATE_10.matcher(host).matches()
            || PRIVATE_172.matcher(host).matches()
            || PRIVATE_192.matcher(host).matches()) return false;
    return true;
}
```

补充说明：该规则只判断「是否明显不可达」，不做真实连通性探测。真实探测会给每次对话增加 RTT，且模型侧可达性无法完全验证，因此采用保守策略——**宁可多转 Base64，也不要让模型收到打不开的 URL**。

### 6.5 附件类型判定

```java
private AgentAttachmentType resolveAttachmentType(String fileType) {
    if (!StringUtils.hasText(fileType)) return AgentAttachmentType.UNKNOWN;
    String t = fileType.trim().toLowerCase();
    if (t.startsWith("image/") || t.equals("png") || t.equals("jpg") || t.equals("jpeg")
            || t.equals("gif") || t.equals("webp") || t.equals("bmp")) {
        return AgentAttachmentType.IMAGE;
    }
    if (t.startsWith("text/") || t.equals("txt") || t.equals("md") || t.equals("markdown")) {
        return AgentAttachmentType.TEXT;
    }
    return AgentAttachmentType.UNKNOWN;
}
```

注意现网 `fileType` 是**不带点的扩展名**（`png`），因此必须同时兼容 `image/png` 与 `png` 两种写法。

### 6.6 文字附件的处理

`TEXT` 类型不走图片通道，而是读取正文拼进文本块：

- 纯文本类（`txt` / `md` / `markdown`）：读取正文，单附件最多保留 **12000 字符**；
- 超出上限时截断，并在截断标记中补上该附件的 `visitUrl`，模型可提示用户访问完整内容；
- 其他类型（PDF、Word、Excel、zip 等）：初版不解析正文，元信息中**内联附件的访问地址**。

> 12000 字符的量级：中文约 12000 字（约 15 页 A4），英文约 2000～2500 词（约 5～6 页 A4）。该限制是“单附件”维度，多个文本附件仍可能叠加放大上下文，后续可增加单条消息总字符预算。

```java
private static final int MAX_TEXT_ATTACHMENT_LENGTH = 12000;

private String buildTextPayload(ResolvedChatAttachment item, AgentAttachmentType type) {
    StringBuilder builder = new StringBuilder(buildMetadataTextPayload(item));
    if (type == AgentAttachmentType.TEXT) {
        String textContent = readTextAttachmentContent(item);
        if (StringUtils.hasText(textContent)) {
            builder.append("\n以下是附件正文内容：\n").append(textContent);
        }
    }
    return builder.toString();
}

private String buildMetadataTextPayload(ResolvedChatAttachment item) {
    StringBuilder builder = new StringBuilder("文件：").append(item.getName());
    // 类型、大小...
    // 非文本附件（如 zip）无法解析正文时，内联访问地址供模型按需引用
    if (StringUtils.hasText(item.getVisitUrl())) {
        builder.append("，访问地址 ").append(item.getVisitUrl());
    }
    return builder.toString();
}

private String truncate(String content, ResolvedChatAttachment item) {
    if (content.length() > MAX_TEXT_ATTACHMENT_LENGTH) {
        StringBuilder builder =
                new StringBuilder(content.substring(0, MAX_TEXT_ATTACHMENT_LENGTH));
        builder.append("\n...[附件内容过长，已截断");
        if (StringUtils.hasText(item.getVisitUrl())) {
            builder.append("，完整内容请访问：").append(item.getVisitUrl());
        }
        builder.append("]");
        return builder.toString();
    }
    return content;
}
```

> 边界说明：PDF/Word/zip 正文解析需要引入解析库并处理分页、编码与解压安全，属于独立能力，本方案只预留扩展点（`AttachmentContentExtractor` 策略接口），不在本次实现。

---

## 7. 核心代码实现

### 7.1 新增 `ImageContent`（agentforge-model-api）

图片内容的最小领域对象。设计上**只承载数据，不承载下载逻辑**，避免 model 层依赖 HTTP 客户端。

```java
package cloud.changlu.agentforge.model.chat.message;

import java.util.Objects;

/**
 * 图片内容块，支持外链与 Base64 两种形态。
 *
 * <p>二选一：{@code url} 非空表示外链；{@code base64Data} + {@code mimeType} 非空表示内联。
 *
 * @author changlu
 * @since 2026-11-21
 */
public final class ImageContent implements Content {

    /** 图片细节档位，对齐 OpenAI 的 detail 参数；AUTO 由模型自行决定。 */
    public enum DetailLevel {
        AUTO, LOW, HIGH
    }

    private final String url;
    private final String base64Data;
    private final String mimeType;
    private final DetailLevel detailLevel;

    private ImageContent(String url, String base64Data, String mimeType, DetailLevel detailLevel) {
        boolean hasUrl = url != null && !url.trim().isEmpty();
        boolean hasBase64 = base64Data != null && !base64Data.trim().isEmpty();
        if (!hasUrl && !hasBase64) {
            throw new IllegalArgumentException("ImageContent requires either url or base64Data");
        }
        if (hasBase64 && (mimeType == null || mimeType.trim().isEmpty())) {
            throw new IllegalArgumentException("ImageContent requires mimeType when base64Data is used");
        }
        this.url = hasUrl ? url.trim() : null;
        this.base64Data = hasBase64 ? base64Data : null;
        this.mimeType = mimeType == null ? null : mimeType.trim();
        this.detailLevel = detailLevel == null ? DetailLevel.AUTO : detailLevel;
    }

    /** 外链形态。 */
    public static ImageContent fromUrl(String url) {
        return new ImageContent(url, null, null, DetailLevel.AUTO);
    }

    /** 外链形态，指定细节档位。 */
    public static ImageContent fromUrl(String url, DetailLevel detailLevel) {
        return new ImageContent(url, null, null, detailLevel);
    }

    /** 内联形态，{@code base64Data} 不含 {@code data:} 前缀。 */
    public static ImageContent fromBase64(String base64Data, String mimeType) {
        return new ImageContent(null, base64Data, mimeType, DetailLevel.AUTO);
    }

    public boolean isUrl() { return url != null; }

    public boolean isInline() { return base64Data != null; }

    public String url() { return url; }

    public String base64Data() { return base64Data; }

    public String mimeType() { return mimeType; }

    public DetailLevel detailLevel() { return detailLevel; }

    /** 拼接为 data URI，供 OpenAI 协议直接使用。 */
    public String toDataUri() {
        return "data:" + mimeType + ";base64," + base64Data;
    }

    @Override
    public ContentType type() { return ContentType.IMAGE; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ImageContent that = (ImageContent) o;
        return Objects.equals(url, that.url)
                && Objects.equals(base64Data, that.base64Data)
                && Objects.equals(mimeType, that.mimeType)
                && detailLevel == that.detailLevel;
    }

    @Override
    public int hashCode() {
        return Objects.hash(url, base64Data, mimeType, detailLevel);
    }

    @Override
    public String toString() {
        return "ImageContent{"
                + (url != null ? "url='" + url + '\'' : "base64=" + (base64Data == null ? 0 : base64Data.length()) + " chars")
                + ", mimeType='" + mimeType + '\''
                + ", detailLevel=" + detailLevel
                + '}';
    }
}
```

同步扩展枚举：

```java
public enum ContentType {
    TEXT(TextContent.class),
    IMAGE(ImageContent.class);

    private final Class<? extends Content> contentClass;
    ...
}
```

### 7.2 OpenAI 序列化：补 `image_url` 分支

改造 `OpenAiMessages.multimodalUserMessage`：

```java
static Map<String, Object> multimodalUserMessage(UserMessage message) {
    List<Content> contents = message.contents();
    ArrayList<Object> blocks = new ArrayList<Object>(contents.size());
    for (Content content : contents) {
        if (content instanceof TextContent) {
            LinkedHashMap<String, Object> block = new LinkedHashMap<String, Object>();
            block.put("type", "text");
            block.put("text", ((TextContent) content).text());
            blocks.add(block);
        } else if (content instanceof ImageContent) {
            blocks.add(imageBlock((ImageContent) content));
        } else {
            // 显式失败，避免图片被静默丢弃后模型"看不到图"
            throw new IllegalArgumentException(
                    "Unsupported chat content type: " + content.type());
        }
    }
    LinkedHashMap<String, Object> item = new LinkedHashMap<String, Object>();
    item.put("role", "user");
    if (message.name() != null) {
        item.put("name", message.name());
    }
    item.put("content", blocks);
    return item;
}

private static Map<String, Object> imageBlock(ImageContent content) {
    LinkedHashMap<String, Object> imageUrl = new LinkedHashMap<String, Object>();
    imageUrl.put("url", content.isUrl() ? content.url() : content.toDataUri());
    if (content.detailLevel() != ImageContent.DetailLevel.AUTO) {
        imageUrl.put("detail", content.detailLevel().name().toLowerCase(Locale.ROOT));
    }
    LinkedHashMap<String, Object> block = new LinkedHashMap<String, Object>();
    block.put("type", "image_url");
    block.put("image_url", imageUrl);
    return block;
}
```

生成的报文片段：

```json
{
  "role": "user",
  "content": [
    { "type": "text", "text": "这张报错截图里是什么原因？" },
    { "type": "image_url", "image_url": { "url": "data:image/png;base64,iVBORw0KGgo..." } }
  ]
}
```

### 7.3 Anthropic 序列化：补 `source.base64` 分支

```java
static Object userContent(UserMessage message) {
    if (message.hasSingleText()) {
        return message.text();
    }
    List<Content> contents = message.contents();
    ArrayList<Map<String, Object>> blocks = new ArrayList<Map<String, Object>>(contents.size());
    for (Content content : contents) {
        if (content instanceof TextContent) {
            LinkedHashMap<String, Object> block = new LinkedHashMap<String, Object>();
            block.put("type", "text");
            block.put("text", ((TextContent) content).text());
            blocks.add(block);
        } else if (content instanceof ImageContent) {
            blocks.add(anthropicImageBlock((ImageContent) content));
        } else {
            throw new IllegalArgumentException(
                    "Unsupported chat content type: " + content.type());
        }
    }
    return blocks;
}

/**
 * Anthropic 只接受 base64 source；外链图片必须先由调用方转成内联形态。
 *
 * <p>因此 Anthropic 场景下 URL 载荷会被路由层强制改写为 INLINE。
 */
private static Map<String, Object> anthropicImageBlock(ImageContent content) {
    if (!content.isInline()) {
        throw new IllegalArgumentException(
                "Anthropic requires base64 image content; url image must be converted to inline first");
    }
    LinkedHashMap<String, Object> source = new LinkedHashMap<String, Object>();
    source.put("type", "base64");
    source.put("media_type", content.mimeType());
    source.put("data", content.base64Data());

    LinkedHashMap<String, Object> block = new LinkedHashMap<String, Object>();
    block.put("type", "image");
    block.put("source", source);
    return block;
}
```

**协议差异导致的额外约束**：Anthropic 不支持图片外链，所以在 `AnthropicModel` 场景下，路由层需要把 `URL` 强制降级为 `INLINE`。这个约束在 `7.5` 的路由实现里体现为 `provider` 参数。

生成的报文片段：

```json
{
  "role": "user",
  "content": [
    { "type": "text", "text": "这张报错截图里是什么原因？" },
    { "type": "image", "source": {
        "type": "base64", "media_type": "image/png", "data": "iVBORw0KGgo..."
    }}
  ]
}
```

### 7.4 记忆编解码：支持 IMAGE（必修）

这是**不做就会丢记忆**的改造点。编码侧：

```java
private void putUserMessage(Map<String, Object> map, UserMessage message) {
    if (message.name() != null) {
        map.put(FIELD_NAME, message.name());
    }
    if (message.hasSingleText()) {
        map.put(FIELD_TEXT, message.text());
        return;
    }
    List<Object> contents = new ArrayList<Object>();
    for (Content content : message.contents()) {
        Map<String, Object> encoded = new LinkedHashMap<String, Object>();
        encoded.put(FIELD_CONTENT_TYPE, content.type().name());
        if (content instanceof TextContent) {
            encoded.put(FIELD_TEXT, ((TextContent) content).text());
        } else if (content instanceof ImageContent) {
            ImageContent image = (ImageContent) content;
            // 只存引用，不存 Base64，避免记忆表膨胀
            if (image.isUrl()) {
                encoded.put("url", image.url());
            } else {
                encoded.put("base64", image.base64Data());
                encoded.put("mimeType", image.mimeType());
            }
            encoded.put("detailLevel", image.detailLevel().name());
        } else {
            throw new IllegalArgumentException(
                    "Unsupported chat content type: " + content.type());
        }
        contents.add(encoded);
    }
    map.put(FIELD_CONTENTS, contents);
}
```

解码侧：

```java
private Content decodeContent(Object value) {
    Map<String, Object> map = Json.object(value);
    if (map == null) {
        throw new IllegalStateException("Chat message content JSON must be an object");
    }
    String contentType = Json.string(map.get(FIELD_CONTENT_TYPE));
    if (ContentType.TEXT.name().equals(contentType)) {
        return new TextContent(Json.string(map.get(FIELD_TEXT)));
    }
    if (ContentType.IMAGE.name().equals(contentType)) {
        String url = Json.string(map.get("url"));
        if (url != null && !url.trim().isEmpty()) {
            return ImageContent.fromUrl(url, parseDetailLevel(Json.string(map.get("detailLevel"))));
        }
        String base64 = Json.string(map.get("base64"));
        String mimeType = Json.string(map.get("mimeType"));
        if (base64 == null || mimeType == null) {
            throw new IllegalStateException("Image content requires url or base64+mimeType");
        }
        return ImageContent.fromBase64(base64, mimeType);
    }
    throw new IllegalStateException("Unsupported chat content type: " + contentType);
}

private static ImageContent.DetailLevel parseDetailLevel(String value) {
    if (value == null) {
        return ImageContent.DetailLevel.AUTO;
    }
    try {
        return ImageContent.DetailLevel.valueOf(value);
    } catch (IllegalArgumentException e) {
        return ImageContent.DetailLevel.AUTO;
    }
}
```

> **存储策略权衡**：记忆表按「URL 优先」存储。原因是被路由为 INLINE 的图片，其 `visitUrl` 本身仍有效，第二轮回放时重新按能力路由一次即可，不必把几 MB 的 Base64 反复写进 `ai_agent_memrory.content`。若某张图已被物理删除，则回放时降级为文字引用。

### 7.5 附件路由服务（service 层）

```java
package cloud.changlu.agentforge.service.modules.agent.attachment;

/** 附件载荷路由：按模型能力与 URL 可达性决定下发给模型的形态。 */
public interface AgentAttachmentRoutingService {

    AttachmentPayloadType route(
            AgentAttachmentType attachmentType,
            String visitUrl,
            long fileSize,
            LlmModelConfig modelConfig,
            Integer providerCode);
}
```

实现：

```java
@Service
public class DefaultAgentAttachmentRoutingService implements AgentAttachmentRoutingService {

    /** 单张 INLINE 图片上限，超过则降级为文字引用，避免上下文被单图打满。 */
    private static final long MAX_INLINE_IMAGE_BYTES = 4L * 1024 * 1024;

    private static final Pattern PRIVATE_10 = Pattern.compile("^10\\..*");
    private static final Pattern PRIVATE_172 = Pattern.compile("^172\\.(1[6-9]|2\\d|3[0-1])\\..*");
    private static final Pattern PRIVATE_192 = Pattern.compile("^192\\.168\\..*");
    private static final Pattern LOOPBACK_127 = Pattern.compile("^127\\..*");

    @Override
    public AttachmentPayloadType route(
            AgentAttachmentType attachmentType,
            String visitUrl,
            long fileSize,
            LlmModelConfig modelConfig,
            Integer providerCode) {
        // 1. 模型不支持图片：一律文字引用
        if (modelConfig == null || !modelConfig.isSupportsImage()) {
            return AttachmentPayloadType.TEXT;
        }
        // 2. 只有 IMAGE 才进入图片通道
        if (attachmentType != AgentAttachmentType.IMAGE) {
            return AttachmentPayloadType.TEXT;
        }
        // 3. Anthropic 不支持外链图片，只能内联
        boolean providerRequiresInline =
                providerCode != null && providerCode == LlmEnum.ANTHROPIC.getCode();
        if (!providerRequiresInline && isPubliclyAccessible(visitUrl)) {
            return AttachmentPayloadType.URL;
        }
        // 4. 需要内联时校验体积
        if (fileSize > 0 && fileSize <= MAX_INLINE_IMAGE_BYTES) {
            return AttachmentPayloadType.INLINE;
        }
        // 5. 体积超限或信息缺失：保守降级
        return AttachmentPayloadType.TEXT;
    }

    private boolean isPubliclyAccessible(String visitUrl) {
        if (visitUrl == null || visitUrl.trim().isEmpty()) {
            return false;
        }
        try {
            URI uri = new URI(visitUrl.trim());
            String host = uri.getHost();
            if (host == null || host.trim().isEmpty()) {
                return false;
            }
            if ("localhost".equalsIgnoreCase(host)) {
                return false;
            }
            return !(LOOPBACK_127.matcher(host).matches()
                    || PRIVATE_10.matcher(host).matches()
                    || PRIVATE_172.matcher(host).matches()
                    || PRIVATE_192.matcher(host).matches());
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
```

### 7.6 附件解析服务：`files` → `AgentAttachment`

```java
@Service
public class AgentChatAttachmentService {

    private static final int MAX_ATTACHMENTS_PER_MESSAGE = 5;
    private static final int MAX_TEXT_ATTACHMENT_LENGTH = 12000;

    private final FileInfoService fileInfoService;
    private final AgentAttachmentRoutingService routingService;
    private final LocalUploadProperties uploadProperties;

    /**
     * 校验归属并把 fileIds 解析为可访问附件。
     *
     * @throws AgentForgeException 附件不存在、越权、数量超限
     */
    public List<ResolvedChatAttachment> resolve(List<Long> fileIds, Long userId) {
        if (fileIds == null || fileIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> normalized = fileIds.stream()
                .filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }
        if (normalized.size() > MAX_ATTACHMENTS_PER_MESSAGE) {
            throw new AgentForgeException("TOO_MANY_ATTACHMENTS",
                    "单条消息最多携带 " + MAX_ATTACHMENTS_PER_MESSAGE + " 个附件");
        }
        Map<Long, FileInfoPojo> found = fileInfoService.selectByIds(normalized).stream()
                .collect(Collectors.toMap(FileInfoPojo::getId, Function.identity()));
        List<ResolvedChatAttachment> result = new ArrayList<ResolvedChatAttachment>();
        for (Long fileId : normalized) {
            FileInfoPojo pojo = found.get(fileId);
            if (pojo == null) {
                throw new AgentForgeException("ATTACHMENT_NOT_FOUND",
                        "附件不存在或已删除, fileId=" + fileId);
            }
            if (pojo.getCreateUserId() != null && !Objects.equals(pojo.getCreateUserId(), userId)) {
                throw new AgentForgeException("ATTACHMENT_FORBIDDEN",
                        "无权引用该附件, fileId=" + fileId);
            }
            result.add(ResolvedChatAttachment.builder()
                    .fileId(pojo.getId())
                    .name(StringUtils.hasText(pojo.getOriginFileName())
                            ? pojo.getOriginFileName() : pojo.getFileName())
                    .visitUrl(pojo.getVisitUrl())
                    .fileType(pojo.getFileType())
                    .fileSize(pojo.getFileSize())
                    .build());
        }
        return result;
    }

    /** 转成 Agent 框架层附件对象，附带已解析的载荷形态。 */
    public List<AgentAttachment> toAgentAttachments(
            List<ResolvedChatAttachment> resolved,
            LlmModelConfig modelConfig,
            Integer providerCode) {
        if (resolved == null || resolved.isEmpty()) {
            return Collections.emptyList();
        }
        List<AgentAttachment> attachments = new ArrayList<AgentAttachment>(resolved.size());
        for (ResolvedChatAttachment item : resolved) {
            AgentAttachmentType type = resolveAttachmentType(item.getFileType());
            long size = item.getFileSize() == null ? 0L : item.getFileSize();
            AttachmentPayloadType payload =
                    routingService.route(type, item.getVisitUrl(), size, modelConfig, providerCode);

            AgentAttachment attachment = AgentAttachment.builder()
                    .fileId(item.getFileId())
                    .name(item.getName())
                    .type(type)
                    .fileType(item.getFileType())
                    .size(size)
                    .url(item.getVisitUrl())
                    .payloadType(payload)
                    .build();
            // TEXT 形态在解析阶段就把正文准备好，避免进入 Agent 后再做 IO
            if (payload == AttachmentPayloadType.TEXT) {
                attachment.setText(buildTextPayload(item, type));
                attachment.setUrl(null);
            }
            attachments.add(attachment);
        }
        return attachments;
    }
}
```

### 7.7 框架层：`AgentAttachment` 与 `AgentRequest` 扩展

`AgentAttachment` 放在 `agentforge-agent-core` 的 `domain` 包，服务层与框架层共用：

```java
package cloud.changlu.agentforge.agent.domain;

/** 随一条用户消息携带的附件，由业务层解析后传入框架层。 */
public final class AgentAttachment {

    private final Long fileId;
    private final String name;
    private final AgentAttachmentType type;
    private final String fileType;
    private final Long size;
    private final String url;
    private final AttachmentPayloadType payloadType;
    private final String text;

    // 构造、getter 略；使用 Builder 便于后续扩展
}
```

`AgentRequest` 增加 `attachments` 字段，保持向后兼容（不传即为纯文本）：

```java
public class AgentRequest {

    private Object memoryId;
    private String executionId;
    private String question;
    private java.util.Map<String, Object> extensions;

    /** 本次消息携带的附件；为空表示纯文本对话。 */
    private java.util.List<AgentAttachment> attachments;

    public java.util.List<AgentAttachment> getAttachments() {
        return attachments;
    }

    public void setAttachments(java.util.List<AgentAttachment> attachments) {
        this.attachments = attachments;
    }

    public static class AgentRequestBuilder {
        private java.util.List<AgentAttachment> attachments;

        public AgentRequestBuilder attachments(java.util.List<AgentAttachment> attachments) {
            this.attachments = attachments;
            return this;
        }
    }
}
```

`AgentChatContext` 增加同名透传访问器：

```java
/** 本次请求携带的附件，直接取自 AgentRequest。 */
public java.util.List<AgentAttachment> getAttachments() {
    return request == null ? null : request.getAttachments();
}
```

### 7.8 框架层：`BaseAgent.buildUserMessage` 组装多模态消息

这是整条链路的核心。改造现有 `buildUserMessage`：

```java
protected UserMessage buildUserMessage(AgentChatContext chatContext) {
    List<Content> contents = new ArrayList<Content>();
    String question = chatContext.getQuestion();
    // 纯图片无文字时给模型一个可理解的占位指令
    contents.add(TextContent.from(
            question == null || question.trim().isEmpty()
                    ? "我上传了附件，请结合附件内容回答。"
                    : question.trim()));

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
}

private void appendAttachmentContents(
        List<Content> contents, AgentAttachment attachment, int index) {
    String displayName = hasText(attachment.getName()) ? attachment.getName() : "未命名文件";

    // ① 业务层已准备好正文（TEXT 载荷）：直接拼文本
    if (hasText(attachment.getText())) {
        contents.add(TextContent.from("附件" + index + "：" + displayName
                + "\n" + attachment.getText()));
        return;
    }

    // ② 无地址：只能给引用信息
    if (!hasText(attachment.getUrl())) {
        contents.add(TextContent.from(buildAttachmentReferenceText(attachment, index, displayName)));
        return;
    }

    // ③ 图片通道
    if (attachment.getType() == AgentAttachmentType.IMAGE) {
        if (attachment.getPayloadType() == AttachmentPayloadType.INLINE
                && appendInlineImage(contents, attachment, index, displayName)) {
            return;
        }
        if (attachment.getPayloadType() == AttachmentPayloadType.URL) {
            contents.add(ImageContent.fromUrl(
                    attachment.getUrl(), ImageContent.DetailLevel.AUTO));
            return;
        }
        // URL 形态但模型不支持外链：尝试内联，失败则降级
        if (appendInlineImage(contents, attachment, index, displayName)) {
            return;
        }
    }

    // ④ 兜底：文字引用
    contents.add(TextContent.from(buildAttachmentReferenceText(attachment, index, displayName)));
}

/**
 * 把本地图片读成 Base64。读取失败返回 false，由调用方降级为文字引用，
 * 保证单个附件异常不会阻断整轮对话。
 */
private boolean appendInlineImage(
        List<Content> contents, AgentAttachment attachment, int index, String displayName) {
    try {
        ImageBinary binary = imageLoader.load(attachment.getUrl());
        contents.add(ImageContent.fromBase64(binary.base64(), binary.mimeType()));
        return true;
    } catch (Exception e) {
        log.warn("[Agent.buildUserMessage] 图片转 Base64 失败，降级为文字引用 | name={}, url={}",
                attachment.getName(), attachment.getUrl(), e);
        return false;
    }
}

private String buildAttachmentReferenceText(
        AgentAttachment attachment, int index, String displayName) {
    StringBuilder builder = new StringBuilder("附件").append(index).append("：").append(displayName);
    if (hasText(attachment.getFileType())) {
        builder.append("，类型 ").append(attachment.getFileType());
    }
    if (attachment.getSize() != null) {
        builder.append("，大小 ").append(attachment.getSize()).append(" bytes");
    }
    if (hasText(attachment.getUrl())) {
        builder.append("，访问地址 ").append(attachment.getUrl());
    }
    return builder.toString();
}
```

### 7.9 `ImageLoader`：URL → Base64

框架层不直接依赖 Spring，用接口 + 默认实现，由 service 层注入带 `LocalUploadProperties` 的实现。

```java
package cloud.changlu.agentforge.agent.domain;

/** 图片二进制加载抽象，隔离框架层与具体存储实现。 */
public interface ImageLoader {

    ImageBinary load(String url);

    final class ImageBinary {
        private final byte[] bytes;
        private final String mimeType;

        public ImageBinary(byte[] bytes, String mimeType) {
            this.bytes = bytes;
            this.mimeType = mimeType;
        }

        public byte[] bytes() { return bytes; }

        public String mimeType() { return mimeType; }

        public String base64() {
            return java.util.Base64.getEncoder().encodeToString(bytes);
        }
    }
}
```

service 层实现：**优先走本地磁盘**（避免自己 HTTP 请求自己），非本服务地址才走网络。

```java
@Component
public class LocalFirstImageLoader implements ImageLoader {

    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final Map<String, String> EXT_MIME = Map.of(
            "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg",
            "gif", "image/gif", "webp", "image/webp", "bmp", "image/bmp");

    private final LocalUploadProperties uploadProperties;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Override
    public ImageBinary load(String url) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("image url must not be blank");
        }
        // 本地文件优先：同一台机器上传的图片无需走 HTTP
        Path local = uploadProperties.resolveLocalPath(url);
        if (local != null && Files.isRegularFile(local)) {
            try {
                long size = Files.size(local);
                if (size > MAX_BYTES) {
                    throw new IllegalStateException("image exceeds " + MAX_BYTES + " bytes");
                }
                return new ImageBinary(Files.readAllBytes(local), detectMime(local));
            } catch (IOException e) {
                throw new IllegalStateException("failed to read local image: " + local, e);
            }
        }
        return download(url);
    }

    private ImageBinary download(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.trim()))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .header("User-Agent", "AgentForge/1.0")
                    .build();
            HttpResponse<byte[]> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("download failed, status=" + response.statusCode());
            }
            byte[] body = response.body();
            if (body == null || body.length == 0) {
                throw new IllegalStateException("download failed, empty body");
            }
            if (body.length > MAX_BYTES) {
                throw new IllegalStateException("image exceeds " + MAX_BYTES + " bytes");
            }
            String contentType = response.headers().firstValue("Content-Type").orElse(null);
            return new ImageBinary(body, normalizeMime(contentType));
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("download image failed: " + url, e);
        }
    }

    private String detectMime(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        return EXT_MIME.getOrDefault(ext, "image/png");
    }

    private String normalizeMime(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "image/png";
        }
        int semicolon = raw.indexOf(';');
        String mime = semicolon >= 0 ? raw.substring(0, semicolon) : raw;
        mime = mime.trim().toLowerCase(Locale.ROOT);
        return mime.startsWith("image/") ? mime : "image/png";
    }
}
```

**为什么本地优先**：`visitHost` 默认 `127.0.0.1`，服务自己请求自己会额外消耗一次 HTTP 往返和一次文件 IO；直接读磁盘更快，也避免了「反向代理没配好导致自请求失败」的环境问题。

### 7.10 新增文件元数据表 `ai_file_info`

```sql
-- 2026.11.21：新增文件元数据表，支撑附件归属校验、会话引用与后续清理
CREATE TABLE IF NOT EXISTS `ai_file_info` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `file_name`        VARCHAR(255) NOT NULL COMMENT '相对存储路径，形如 1/uuid.png',
    `origin_file_name` VARCHAR(255)          DEFAULT NULL COMMENT '上传时原始文件名',
    `visit_url`        VARCHAR(512) NOT NULL COMMENT '可直接访问的完整地址',
    `file_type`        VARCHAR(32)           DEFAULT NULL COMMENT '小写扩展名，不含点',
    `file_size`        BIGINT                DEFAULT NULL COMMENT '字节数',
    `storage_mode`     VARCHAR(32)  NOT NULL DEFAULT 'local' COMMENT '存储模式：local',
    `create_user_id`   BIGINT       NOT NULL DEFAULT 1 COMMENT '上传者',
    `is_deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '0 正常，1 已删除',
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `modify_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_create_user` (`create_user_id`),
    KEY `idx_visit_url` (`visit_url`(191))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文件元数据';
```

SQLite 版本（`sql/sqllite/1.0.x/`）：

```sql
CREATE TABLE IF NOT EXISTS ai_file_info (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    file_name        TEXT    NOT NULL,
    origin_file_name TEXT             DEFAULT NULL,
    visit_url        TEXT    NOT NULL,
    file_type        TEXT             DEFAULT NULL,
    file_size        INTEGER          DEFAULT NULL,
    storage_mode     TEXT    NOT NULL DEFAULT 'local',
    create_user_id   INTEGER NOT NULL DEFAULT 1,
    is_deleted       INTEGER NOT NULL DEFAULT 0,
    create_time      TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time      TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_file_info_user ON ai_file_info (create_user_id);
```

> 脚本放置位置遵循现有约定：MySQL 放 `sql/increment/1.0.x/`，SQLite 放 `sql/sqllite/1.0.x/`，文件名用 `yyyyMMddHHmmss_v1.0.x.sql`。执行后再复制一份到 `sql/local/` 作为已执行记录。

### 7.11 上传接口改造：返回 `fileId` 并落库

`FileUploadService` 契约扩展（保持旧方法可用）：

```java
public interface FileUploadService {

    /** 存储文件并写入元数据，返回带 fileId 的结果。 */
    UploadFileVo upload(MultipartFile file, Long userId);
}
```

`UploadFileVo` 增加 `fileId` 字段：

```java
public class UploadFileVo {

    private Long fileId;             // 新增：元数据主键，对话时用它引用
    private final String visitUrl;
    private final String fileName;
    private final String originFileName;
    private final String fileType;
    private final long fileSize;

    // 其余不变
}
```

`LocalFileUploadServiceImpl.upload` 在原有落盘逻辑后追加一步：

```java
@Override
public UploadFileVo upload(MultipartFile file, Long userId) {
    validateFile(file);
    validateUserId(userId);

    String originFileName = normalizeOriginalFileName(file.getOriginalFilename());
    String fileType = extractFileType(originFileName);
    String storageFileName =
            UUID.randomUUID().toString().replace("-", "") + extension(fileType);
    Path relativeFile = Paths.get(String.valueOf(userId), storageFileName);
    Path targetFile = properties.getStorageRoot().resolve(relativeFile).normalize();
    ensureInsideStorageRoot(targetFile);

    try {
        Files.createDirectories(targetFile.getParent());
        try (InputStream input = file.getInputStream()) {
            Files.copy(input, targetFile);
        }
    } catch (IOException e) {
        throw new AgentForgeException(CODE_UPLOAD_FAILED, "File upload failed", e);
    }

    String relativeName = relativeFile.toString().replace('\\', '/');
    String visitUrl = properties.buildVisitUrl(relativeName);

    // 新增：写元数据，拿到 fileId
    Long fileId = fileInfoService.save(FileInfoSaveCommand.builder()
            .fileName(relativeName)
            .originFileName(originFileName)
            .visitUrl(visitUrl)
            .fileType(fileType)
            .fileSize(file.getSize())
            .storageMode("local")
            .createUserId(userId)
            .build());

    return new UploadFileVo(
            fileId, visitUrl, relativeName, originFileName, fileType, file.getSize());
}
```

**一致性约定**：先落盘再写库。若写库失败，删除已落盘文件并抛异常，避免出现「有文件无记录」的孤儿文件：

```java
try {
    fileId = fileInfoService.save(command);
} catch (RuntimeException e) {
    try {
        Files.deleteIfExists(targetFile);
    } catch (IOException ignored) {
        // 删除失败只记录，不掩盖原始异常
    }
    throw new AgentForgeException(CODE_UPLOAD_FAILED, "File metadata save failed", e);
}
```

### 7.12 对话请求透传文件集合

新增 `ChatFileRef`，承载前端上传成功后回传的文件元信息：

```java
public class ChatFileRef {
    private Long fileId;
    private String originFileName;
    private String fileType;
    private String visitUrl;

    public static List<Long> extractFileIds(List<ChatFileRef> files) {
        // 去重并忽略空 fileId
    }
}
```

`ChatStreamRequest` 与 `AgentChatReq` 各增加一个文件集合字段：

```java
/** 本次消息引用的文件列表；每一项含 fileId / originFileName / fileType / visitUrl。 */
private List<ChatFileRef> files;

/** 本次消息实际引用的附件主键。 */
public List<Long> resolveFileIds() {
    return ChatFileRef.extractFileIds(files);
}
```

`AgentChatReq` 额外增加已解析的附件载体（内部使用，不参与反序列化）：

```java
/** 已解析的附件，由 Controller/Service 填充，不接收前端直接传入。 */
private transient java.util.List<AgentAttachment> attachments;
```

`StandardReActAgentManager.toAgentRequest` 把附件带进框架：

```java
private AgentRequest toAgentRequest(AgentChatReq req) {
    String sessionId = req.getSessionId();
    boolean isDebug = Boolean.TRUE.equals(req.getIsDebug());
    if (sessionId == null || sessionId.trim().isEmpty()) {
        sessionId = (isDebug ? "debug-" : "chat-") + req.getAid();
    }
    Map<String, Object> extensions = new HashMap<String, Object>();
    extensions.put(SessionLifecycleMiddleware.EXT_SESSION_ID, sessionId);
    extensions.put(AgentTraceAttributes.AID, req.getAid());
    extensions.put(AgentTraceAttributes.DEBUG, isDebug);
    if (req.getAiMessageId() != null) {
        extensions.put(SessionLifecycleMiddleware.EXT_AI_MESSAGE_ID, req.getAiMessageId());
    }
    return AgentRequest.builder()
            .memoryId(sessionId)
            .executionId(req.getExecutionId())
            .question(req.getMessage().trim())
            .attachments(req.getAttachments())   // 新增
            .extensions(extensions)
            .build();
}
```

`LegacyChatStreamServiceImpl` 在 `buildRequest` 之前完成解析：

```java
List<Long> fileIds = body.resolveFileIds();
// 解析附件（含归属校验与载荷路由），需要当前 Agent 的模型能力
List<AgentAttachment> attachments = attachmentService.toAgentAttachments(
        attachmentService.resolve(fileIds, userId),
        resolveModelCapability(config),
        resolveProviderCode(config));

AgentChatReq request = buildRequest(config, body, init, debug);
request.setAttachments(attachments);
```

其中模型能力与协议码的解析放在 `AgentRuntimeConfigResolver` 旁边，避免在 Controller 里散落：

```java
private LlmModelConfig resolveModelCapability(AgentRuntimeConfig config) {
    ModelConfigPojo pojo = modelConfigMapper.selectById(
            config.getAgentConfig().getChatModel().getLlmModelId());
    if (pojo == null) {
        return null;
    }
    return LlmModelConfigRegistry.registry().getConfig(pojo.getUrl(), pojo.getModelname());
}
```

**校验顺序约定**：先校验附件（存在性、归属、数量），再初始化会话。否则会出现「会话已建、AI 消息已插入，但附件非法导致整轮失败」的脏数据。

---

## 8. 会话持久化与历史回放

### 8.1 消息表增加用户请求快照列

```sql
-- 2026.11.21：ai_agent_session_message 新增 req，保存用户消息发送时的完整请求快照
ALTER TABLE `ai_agent_session_message`
    ADD COLUMN `req` LONGTEXT DEFAULT NULL COMMENT '用户消息请求快照 JSON（含附件）' AFTER `events`;
```

SQLite：

```sql
ALTER TABLE ai_agent_session_message ADD COLUMN req TEXT DEFAULT NULL;
```

**为什么存请求快照而不是只存 `files` 中的 `fileId`**：

- 附件被删除后，历史消息仍需展示「当时传了什么」；
- 原始文件名属于消息语义的一部分，不应随元数据变化；
- 前端渲染只需一次查询，不必 N+1 回查文件表。

`req` 快照结构（`files` 字段名与前端请求保持一致，值以服务端校验后的文件元数据为准，**不存 Base64**）：

```json
{
  "aid": "4v7qri4t4vo0",
  "sessionId": "chat-xxx",
  "message": "帮我看下这张报错截图",
  "isDebug": false,
  "files": [
    {
      "fileId": 1024,
      "originFileName": "报错截图.png",
      "fileType": "png",
      "visitUrl": "http://127.0.0.1:8080/upload/1/8f3c.png"
    }
  ]
}
```

### 8.2 写入时机

`AgentChatAttachmentService.buildUserReqJson` 负责构造快照，`AgentSessionServiceImpl.initChat` 插入用户消息时写入 `req`：

```java
req.put("files", files);   // 每一项：fileId / originFileName / fileType / visitUrl

AgentSessionMessagePojo userMessage = new AgentSessionMessagePojo();
userMessage.setSessionId(session.getId());
userMessage.setSeqNum(userSeq);
userMessage.setRoleType(SessionMessageRoleEnum.USER.getCode());
userMessage.setStatus(SessionMessageStatusEnum.SUCCEEDED.getCode());
userMessage.setContent(message);
userMessage.setReq(reqJson);   // 新增
userMessage.setCreateUserId(userId);
agentSessionMessageMapper.insert(userMessage);
```

`reqJson` 为 `null` 时保持原行为，纯文本消息不受影响。

### 8.3 历史回放

`SessionMessageVo` 增加字段：

```java
/** 用户消息请求快照 JSON；纯文本或 AI 消息为 null。 */
private String req;
```

前端 `sessionReplay.js` 解析 `req.files`：

```javascript
return req.files.filter((item) => item && item.visitUrl).map((item) => ({
  fileId: item.fileId,
  name: item.originFileName,
  fileType: item.fileType,
  fileSize: item.fileSize,
  visitUrl: item.visitUrl
}))
```

用户消息渲染时直接使用 `name / fileType / visitUrl` 展示缩略图与文件卡片，无需再次请求文件元数据。

---

## 9. 兼容性改造清单（不做会出问题）

引入「多内容 `UserMessage`」后，所有对 `message.text()` 的调用点都需要审计，因为该方法在多内容时**会抛 `UnsupportedOperationException`**。

### 9.1 `Agent.lastUserText`（高危）

```java
private static String lastUserText(List<ChatMessage> messages) {
    for (int i = messages.size() - 1; i >= 0; i--) {
        if (messages.get(i).type() == ChatMessageType.USER) {
            return messages.get(i).text();     // 多内容时抛异常
        }
    }
    return null;
}
```

该方法用于恢复流程（`ResumePlan.completed(..., question, ...)`）。图片消息会导致恢复直接失败。

改造：

```java
private static String lastUserText(List<ChatMessage> messages) {
    for (int i = messages.size() - 1; i >= 0; i--) {
        if (messages.get(i).type() != ChatMessageType.USER) {
            continue;
        }
        UserMessage userMessage = (UserMessage) messages.get(i);
        if (userMessage.hasSingleText()) {
            return userMessage.text();
        }
        // 多内容：取第一个文本块，忽略图片
        for (Content content : userMessage.contents()) {
            if (content instanceof TextContent) {
                return ((TextContent) content).text();
            }
        }
        return "[图片消息]";
    }
    return null;
}
```

### 9.2 `TokenStatsUtil.getMessageContent`（中危）

现有实现已做兜底，但 `String.valueOf(contents)` 会把整个 `Content` 对象 `toString()` 计入 token，导致**图片被按 Base64 长度估算**，压缩阈值被误触发。

```java
if (message.type() == ChatMessageType.USER) {
    UserMessage userMessage = (UserMessage) message;
    if (userMessage.hasSingleText()) {
        return userMessage.singleText();
    }
    return String.valueOf(userMessage.contents());   // 图片会带入 Base64 长度
}
```

改造：文本块拼接，图片按固定 token 折算。

```java
if (message.type() == ChatMessageType.USER) {
    UserMessage userMessage = (UserMessage) message;
    if (userMessage.hasSingleText()) {
        return userMessage.singleText();
    }
    StringBuilder builder = new StringBuilder();
    for (Content content : userMessage.contents()) {
        if (content instanceof TextContent) {
            builder.append(((TextContent) content).text()).append(' ');
        } else if (content instanceof ImageContent) {
            // 图片不计入字符统计，由 estimateMessageTokens 单独加成
            builder.append("[image] ");
        }
    }
    return builder.toString();
}
```

并在 `estimateMessageTokens` 中为 `ImageContent` 增加固定开销（建议 1000 token/张，与主流厂商计费口径同量级）：

```java
private static int imageContentTokens(List<Content> contents) {
    int count = 0;
    for (Content content : contents) {
        if (content instanceof ImageContent) {
            count++;
        }
    }
    return count * IMAGE_TOKEN_ESTIMATE;
}
```

### 9.3 `ChatMessageCodec`（必修，见 7.4）

不支持 `IMAGE` 会导致 MySQL 记忆读取失败并静默清空。这是**必须与 `ImageContent` 同批上线**的改造。

### 9.4 日志与追踪

`LoggingIAgentMiddleware` 已在打印 `aiMessage().text()`，属于 AI 消息，不涉及用户多内容消息，无需改造。但若后续打印用户消息，需走 `TokenStatsUtil.getMessageContent` 而非 `text()`。

### 9.5 兼容清单汇总

| 位置 | 风险 | 处理 |
| --- | --- | --- |
| `Agent.lastUserText` | 恢复流程抛异常 | 改取首个文本块 |
| `TokenStatsUtil.getMessageContent` | 图片按 Base64 长度计 token | 文本块拼接 + 图片固定折算 |
| `TokenStatsUtil.estimateMessageTokens` | 图片不参与统计 | 增加图片固定开销 |
| `ChatMessageCodec` 编码/解码 | 记忆丢失 | 支持 IMAGE |
| `OpenAiMessages` | 图片被静默丢弃 | 补 `image_url` + 未知类型抛异常 |
| `AnthropicProtocol` | 图片被静默丢弃 | 补 `source.base64` + 未知类型抛异常 |

---

## 10. 前端接入

### 10.1 输入框改造

在 `PublishedAgentChatPage.vue` 与调试面板 `AgentDebugPanel.vue` 的 composer 区域增加附件区，复用已有 `uploadFile`，两处交互保持一致：

- 左侧 `+` 打开菜单，当前只提供「添加照片和文件 / 从计算机上传」；
- 选择文件后，附件卡片展示在输入框上方：图片显示缩略图，文本文件显示内容预览，其他文件显示文档图标，文件名位于卡片内部底行；
- 有附件或输入多行时，输入区独占一行，`+` 与发送按钮下移到底部工具栏，光标从输入区最左侧开始。

```vue
<div class="composer-pending-files" v-if="attachments.length">
  <div v-for="(item, index) in attachments" :key="item.fileId" class="pending-file-item">
    <div class="pending-file-card" :class="{ 'is-image': isImage(item.fileType) }">
      <img v-if="isImage(item.fileType)" :src="item.visitUrl" class="pending-file-image" />
      <span v-else-if="item.previewText" class="pending-file-preview">{{ item.previewText }}</span>
      <span v-else class="pending-file-icon"><i class="el-icon-document"></i></span>
      <span class="pending-file-name">{{ item.name }}</span>
      <button class="pending-file-remove" @click.stop="removeAttachment(index)">×</button>
    </div>
  </div>
</div>
```

上传处理：

```javascript
async handlePickFiles(files) {
  for (const file of Array.from(files)) {
    if (this.attachments.length >= 5) {
      this.$message.warning('单条消息最多 5 个附件')
      break
    }
    if (file.size > 10 * 1024 * 1024) {
      this.$message.error(`${file.name} 超过 10MB`)
      continue
    }
    try {
      const result = await uploadFile(file)
      this.attachments.push(result)     // result 含 fileId / visitUrl / fileType / originFileName
    } catch (e) {
      this.$message.error(e.message || '上传失败')
    }
  }
}
```

发送时带上 `files` 集合，每一项保留上传时的展示字段，便于消息快照与历史回显：

```javascript
await runChatStream({
  aid: this.aid,
  sessionId: this.sessionId,
  message: text,
  files: this.attachments.map((item) => ({
    fileId: item.fileId,
    originFileName: item.name,
    fileType: item.fileType,
    visitUrl: item.visitUrl
  }))
}, { ... })
```

### 10.2 允许「只发图片」

当前后端校验 `message` 非空。需放宽为「文本或附件至少有一项」：

```java
java.util.List<Long> fileIds = req.resolveFileIds();
if ((req.getMessage() == null || req.getMessage().trim().isEmpty())
        && (fileIds == null || fileIds.isEmpty())) {
    throw new IllegalArgumentException("对话内容或附件不能同时为空");
}
```

同时 `AgentRequest.Builder.build()` 的 `question` 非空校验需要同步放宽，或在服务层兜底填空字符串（框架层 `buildUserMessage` 已有占位指令）。

推荐后者：**框架层保持 `question` 可以为空，由 `buildUserMessage` 注入占位文本**，避免修改既有的参数校验契约。

### 10.3 用户气泡展示附件

附件展示在用户消息文本上方；`message.attachments` 由 `req.files` 回放得到，字段为 `name / fileType / visitUrl`：

```vue
<div class="user-attachments" v-if="message.attachments && message.attachments.length">
  <template v-for="item in message.attachments">
    <img v-if="isImage(item.fileType)" :key="item.fileId"
         :src="item.visitUrl" class="bubble-image" @click="preview(item)" />
    <a v-else :key="item.fileId" :href="item.visitUrl" target="_blank"
       rel="noopener" class="bubble-file">{{ item.name }}</a>
  </template>
</div>
<div class="user-bubble" v-if="message.text">{{ message.text }}</div>
```

图片点击用 `el-image` 的预览或轻量 lightbox，不引入新依赖。

---

## 11. 实施步骤

按依赖顺序分四批，每批可独立编译、独立验证。

### 批次一：框架层多模态基础设施（无业务行为变化）

1. `agentforge-model-api`：新增 `ImageContent`，`ContentType` 增加 `IMAGE`；
2. `agentforge-model-openai`：`OpenAiMessages.multimodalUserMessage` 补 `image_url` 分支，未知类型抛异常；
3. `agentforge-model-anthropic`：`AnthropicProtocol.userContent` 补 `source.base64` 分支；
4. `agentforge-agent-core`：`ChatMessageCodec` 编解码支持 `IMAGE`；
5. `agentforge-agent-core`：`AgentAttachment`、`AttachmentPayloadType`、`AgentAttachmentType`、`ImageLoader` 领域对象。

验证：单元测试覆盖「含图片的 `UserMessage`」在两种协议下的序列化快照，以及编解码往返一致。

### 批次二：兼容性修复（防回归）

6. `Agent.lastUserText` 改为取首个文本块；
7. `TokenStatsUtil.getMessageContent` 与 `estimateMessageTokens` 增加图片处理。

验证：构造一条「文本 + 图片」的 `UserMessage` 写入记忆，再走压缩与恢复流程，确认无异常、token 估算合理。

### 批次三：上传与附件链路（业务闭环）

8. 新增 `ai_file_info` 建表脚本（MySQL + SQLite）与 `FileInfoService`；
9. `FileUploadService` 落库并返回 `fileId`，失败回滚已落盘文件；
10. 新增 `AgentAttachmentRoutingService` 与 `AgentChatAttachmentService`；
11. `AgentChatReq` / `ChatStreamRequest` 增加 `files`，`StandardReActAgentManager` 透传；
12. `BaseAgent.buildUserMessage` 组装多模态消息；
13. `LocalUploadProperties.resolveLocalPath` + `LocalFirstImageLoader`。

验证：上传图片 → 视觉模型对话 → 模型能描述图片内容；上传图片 → 文本模型对话 → 模型收到文字引用且不报错。

### 批次四：会话持久化与前端

14. `ai_agent_session_message` 增加 `req` 列，`initChat` 写入含 `files` 的请求快照；
15. `SessionMessageVo` 输出 `req`，前端从 `req.files` 回放渲染；
16. 前端 composer 附件区、气泡附件展示、`files` 透传；
17. 放宽「文本或附件至少一项」校验。

验证：刷新页面后附件卡片仍在；历史图片可点击预览；只发图片也能正常对话。

---

## 12. 验证清单

### 12.1 功能验证

| 场景 | 预期 |
| --- | --- |
| 视觉模型 + 本地部署（`127.0.0.1`）+ PNG | 路由为 INLINE，模型能描述图片 |
| 视觉模型 + 公网 `visitHost` + PNG | 路由为 URL，报文含 `image_url.url` 为 http 地址 |
| 视觉模型 + Anthropic 协议 + PNG | 强制 INLINE，报文为 `source.base64` |
| 文本模型 + PNG | 路由为 TEXT，模型收到文字引用，无异常 |
| 超过 5 个附件 | 返回 `TOO_MANY_ATTACHMENTS` |
| 引用他人附件 | 返回 `ATTACHMENT_FORBIDDEN` |
| 引用已删除附件 | 返回 `ATTACHMENT_NOT_FOUND` |
| 单图 5MB | 超 4MB 上限，降级 TEXT |
| 文本附件超过 12000 字符 | 截断并附带 `visitUrl`，模型可提示用户访问完整内容 |
| 上传 zip 附件 | 路由为 TEXT，元信息包含 `visitUrl`，不做解压解析 |
| 只发图片不发文字 | 正常对话，模型收到占位指令 |
| 上传后容器重启 | `visitUrl` 仍可访问（`./upload` 挂载） |

### 12.2 回归验证

| 场景 | 预期 |
| --- | --- |
| 纯文本多轮对话 | 行为不变，记忆正常 |
| 图片消息后继续多轮对话 | 记忆不丢（验证 7.4 编解码改造） |
| 图片对话触发上下文压缩 | 不抛异常，压缩后仍可继续 |
| 图片对话中断后恢复 | 恢复成功（验证 9.1 改造） |
| 历史纯文本会话回放 | 展示不变 |

### 12.3 自动化测试建议

- `ImageContentTest`：构造参数校验、`toDataUri`、equals/hashCode；
- `OpenAiMessagesImageTest`：图片块 JSON 结构断言（URL 与 Base64 两种）；
- `AnthropicProtocolImageTest`：`source.base64` 结构与「外链抛异常」断言；
- `ChatMessageCodecImageTest`：含图片消息的编码-解码往返一致；
- `AgentAttachmentRoutingServiceTest`：决策树六个分支全覆盖；
- `LocalUploadPropertiesResolveTest`：`resolveLocalPath` 正常、前缀不符、路径穿越三类用例。

---

## 13. 边界与后续演进

### 13.1 本方案明确不做

| 项 | 原因 |
| --- | --- |
| PDF / Word / Excel 正文解析 | 需引入解析库与分页、编码处理，属独立能力 |
| 音频 / 视频理解 | 模型与协议差异大，需单独设计 |
| 按会话清理附件 | 需引用计数与删除策略，先保证链路可用 |
| 上传文件病毒扫描 | 需外部引擎，属安全加固项 |
| 图片压缩与格式转换 | 初版直接透传，后续按 token 成本优化 |
| 前端上传进度条 | 复用现有 `uploadFile`，后续可加 `onUploadProgress` |

### 13.2 已预留的扩展点

- `AttachmentContentExtractor` 策略接口：后续接入 PDF/Word 正文提取；
- `ImageLoader` 接口：后续可替换为 OSS/S3 直读；
- `storage_mode` 字段：后续支持 OSS 等非本地存储；
- `AttachmentPayloadType` 枚举：后续可扩展 `FILE_REF` 等形态。

### 13.3 关键风险与对策

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| Base64 使请求体膨胀 | 单图 4MB 转 Base64 约 5.3MB，可能触发网关限制 | 4MB 上限 + 超限降级 TEXT；网关限制需同步确认 |
| 记忆表膨胀 | `ai_agent_memrory.content` 过大 | 记忆只存 URL，不存 Base64（见 7.4 说明） |
| 图片 URL 过期或文件被删 | 回放时图片 404 | 快照保留 `visitUrl`，前端 `onerror` 显示占位 |
| `visitHost` 配置错误 | 生成错误的 `visitUrl` | 启动日志打印上传目录；文档明确排障依据 |
| Anthropic 外链限制 | 图片被静默丢弃 | 路由层按 provider 强制 INLINE，序列化层抛异常兜底 |

---

## 14. 与 agentx 实现的对应关系

| 能力 | agentx 位置 | AgentForge 落地位置 |
| --- | --- | --- |
| 附件领域对象 | `AgentAttachment` | `agentforge-agent-core/agent/domain/AgentAttachment` |
| 载荷枚举 | `AttachmentPayloadType` / `AgentAttachmentType` | 同包新增 |
| 能力注册表 | `ModelConfigRegistry`（含 `AttachmentTypeSupport` / `payloadPriority`） | 复用现有 `LlmModelConfigRegistry.supportsImage` |
| 载荷路由 | `DefaultAgentAttachmentRoutingService` | 新增 `AgentAttachmentRoutingService` |
| URL 可达性 | `DefaultAttachmentUrlAccessibilityResolver` | 合并进路由服务（私有方法） |
| 附件解析 | `AgentChatAttachmentService` | 新增同名服务（增加归属校验） |
| 消息组装 | `BaseAgent.buildUserMessage` | 改造 `BaseAgent.buildUserMessage` |
| 图片转 Base64 | `ImageUtil.getImageBase64FromUrl` | 新增 `ImageLoader` + `LocalFirstImageLoader` |
| 本地上传目录 | `LocalUploadConfig`（`getUserDirUpload`） | 复用现有 `LocalUploadProperties` |

**关键差异**：`agentx` 的 `ModelConfigRegistry` 额外维护了 `payloadPriority`（每种附件类型的载荷优先级列表），AgentForge 的 `LlmModelConfig` 只有 `supportsImage` 布尔值。因此本方案把优先级固化在路由服务里（图片优先 URL，不可达则 INLINE，再退 TEXT），能力信息仍然只从注册表读。后续若需要按模型定制优先级，再扩展 `LlmModelConfig`。

---

## 15. 附录：关键类清单

### 15.1 新增

```text
agentforge-model/agentforge-model-api
└── model/chat/message/ImageContent.java

agentforge-framework/agentforge-agent-core
├── agent/domain/AgentAttachment.java
├── agent/domain/AttachmentPayloadType.java
├── agent/domain/AgentAttachmentType.java
└── agent/domain/ImageLoader.java

agentforge-service/agentforge-service-backend/agentforge-service-web
├── service/domain/req/ChatFileRef.java
├── modules/agent/attachment/AgentAttachmentRoutingService.java
├── modules/agent/attachment/impl/DefaultAgentAttachmentRoutingService.java
├── modules/agent/attachment/AgentChatAttachmentService.java
├── modules/upload/service/FileInfoService.java
├── modules/upload/service/impl/FileInfoServiceImpl.java
├── modules/upload/dao/FileInfoMapper.java
├── modules/upload/domain/FileInfoPojo.java
└── modules/upload/loader/LocalFirstImageLoader.java
```

### 15.2 改造

```text
agentforge-model/agentforge-model-api
└── model/chat/message/ContentType.java                    + IMAGE

agentforge-model/agentforge-model-openai
└── OpenAiMessages.java                                   + image_url 分支

agentforge-model/agentforge-model-anthropic
└── AnthropicProtocol.java                                + source.base64 分支

agentforge-framework/agentforge-agent-core
├── agent/BaseAgent.java                                   buildUserMessage 多模态
├── agent/Agent.java                                       lastUserText 兼容
├── agent/domain/AgentRequest.java                         + attachments
├── agent/domain/AgentChatContext.java                     + getAttachments
├── agent/memory/store/ChatMessageCodec.java               + IMAGE 编解码
└── agent/extend/middlewares/compact/TokenStatsUtil.java   图片 token 估算

agentforge-service/agentforge-service-backend/agentforge-service-web
├── modules/upload/config/LocalUploadProperties.java       + resolveLocalPath
├── modules/upload/domain/vo/UploadFileVo.java             + fileId
├── modules/upload/service/impl/LocalFileUploadServiceImpl.java  落库
├── modules/agent/domain/req/AgentChatReq.java             + files/attachments
├── modules/agent/controller/AgentChatController.java      校验放宽
├── modules/agent/service/impl/LegacyChatStreamServiceImpl.java  解析文件
├── modules/agent/runtime/mode/react/StandardReActAgentManager.java  透传
├── modules/session/domain/AgentSessionMessagePojo.java    + req
├── modules/session/domain/vo/SessionMessageVo.java        + req
└── modules/session/service/impl/AgentSessionServiceImpl.java     写请求快照

agentforge-service/agentforge-service-ui
├── src/pages/published/PublishedAgentChatPage.vue        附件区与气泡
└── src/utils/sessionReplay.js                            回放附件

agentforge-service/sql
├── increment/1.0.x/20261121xxxxxx_v1.0.x.sql             ai_file_info + 消息表列
└── sqllite/1.0.x/20261121xxxxxx_v1.0.x.sql
```

---

## 16. 变更记录

| 日期 | 内容 |
| --- | --- |
| 2026-11-21 | 初版：上传目录规范、附件路由、多模态消息组装、双协议序列化、会话快照与兼容性改造 |
| 2026-11-22 | 对话请求统一为 `files` 集合（fileId / originFileName / fileType / visitUrl），会话改为 `req` 请求快照回放；同步 composer 交互与多行输入布局；文本超长截断补充 `visitUrl`，zip 等非文本附件元信息内联访问地址 |

---
title: 阅读资源 Middleware
sidebar_label: 阅读资源 Middleware
description: 用 ReadResourceTool 与 ReadArtifactMiddleware 让 Agent 读取用户提供的图片和网页链接
sidebar_position: 6
displayed_sidebar: sdkSidebar
---

# 阅读资源 Middleware

用户在对话里发来一张图片链接或一篇文章链接时，模型本身并不知道怎么访问它。`ReadResourceTool` 提供一个模型可调用的 `read_resource` 工具，负责探测资源元信息；`ReadArtifactMiddleware` 在工具执行后收集结果，并在下一次模型调用前把图片或引用装配成 `UserMessage` 附件。

它把“模型决定要不要读”和“框架怎么把资源交给模型”分开：

```text
模型调用 read_resource
        ↓
ReadResourceTool 探测 http/https 元信息（不下载正文）
        ↓
ReadArtifactMiddleware 收集结果
        ↓
下一次模型调用前装配 UserMessage
        ↓
图片走多模态通道；网页/文本走引用
```

## 1. 快速接入

### 1.1 注册读取工具

```java
import cloud.changlu.agentforge.agent.extend.middlewares.read.tool.ReadResourceTool;
import cloud.changlu.agentforge.agent.extend.middlewares.read.tool.ReadToolOptions;
import cloud.changlu.agentforge.agent.tool.local.LocalToolFactory;
import cloud.changlu.agentforge.model.tool.execution.ToolService;
import cloud.changlu.agentforge.model.tool.spec.ToolSpecification;
import cloud.changlu.agentforge.model.tool.ToolExecutor;

import java.util.Collections;
import java.util.Map;

ReadResourceTool readTool = ReadResourceTool.builder()
        .options(ReadToolOptions.builder()
                .maxPaths(5)                      // 单次最多 5 个 URL
                .maxImageBytes(10 * 1024 * 1024)  // 图片探测上限 10MB
                .timeoutMillis(5000)              // 连接与读取超时
                .build())
        .build();

Map<ToolSpecification, ToolExecutor> readTools =
        LocalToolFactory.buildLocalToolsWithThought(
                Collections.<Object>singletonList(readTool), true);

ToolService toolService = new ToolService();
toolService.tools(readTools);
```

### 1.2 注册读取中间件

```java
import cloud.changlu.agentforge.agent.extend.middlewares.read.middleware.ReadArtifactMiddleware;

ReadArtifactMiddleware readMiddleware = ReadArtifactMiddleware.builder()
        .supportsImage(true)                          // 当前模型是否支持图片
        .requiresInlineImage(false)                   // Anthropic 设为 true
        .maxInlineImageBytes(4L * 1024 * 1024)        // 超过 4MB 降级为文字引用
        .dedupeEnabled(true)                          // 同一批资源去重
        .logEnabled(true)
        .build();
```

### 1.3 组装 Agent

```java
ReActAgent agent = ReActAgent.builder()
        .agentName("resource-agent")
        .systemPrompt("当用户提供 http/https 图片或网页链接时，先调用 read_resource 读取资源。")
        .chatModel(chatModel)
        .streamingChatModel(streamingChatModel)
        .chatMemoryProvider(new MessageWindowChatMemoryProvider(50))
        .toolService(toolService)
        .middleware(readMiddleware)
        .build();
```

工具和中间件必须同时注册：

- 只注册工具：模型能拿到元信息 JSON，但图片不会自动进入多模态通道；
- 只注册中间件：没有 `read_resource` 可调用，中间件收集不到资源。

## 2. 工具能读取什么

`read_resource` 只接受 `http` / `https` URL，并对目标发起 `HEAD` 请求读取元信息：

| 类型 | 判定 | 后续处理 |
| --- | --- | --- |
| 图片 | `Content-Type: image/*` 或常见图片扩展名 | 进入图片通道（URL 或 Base64） |
| 网页 | `text/html` | 生成带访问地址的文字引用 |
| 文本 | `text/*` | 生成带访问地址的文字引用 |
| 其他文件 | 其他类型或扩展名 | 生成带访问地址的文字引用 |

限制：

- 单次最多 5 个 URL；
- 只允许公网地址，`localhost`、`127.x`、`10.x`、`172.16-31.x`、`192.168.x` 会被拒绝（SSRF 保护）；
- 只读取响应头，不下载正文；资源大小取 `Content-Length`；
- 探测超时默认 5 秒。

> `read_resource` 不是网页正文提取器。网页和文本资源会以“可访问地址 + 元信息”的方式交给模型。如果你需要抽取网页正文、PDF 文本或 OCR，建议实现自己的读取工具，并让 `ReadArtifactMiddleware` 识别它（见第 6 节）。

## 3. 图片如何交给模型

中间件根据模型能力与 URL 可达性决定载荷形态：

```text
模型支持图片？
  ├─ 否 -> TEXT（文字引用）
  └─ 是
      ├─ 非图片 -> TEXT
      └─ 图片
          ├─ requiresInlineImage=true（如 Anthropic） -> INLINE
          ├─ 公网可达 URL -> URL
          ├─ 图片不超过 maxInlineImageBytes -> INLINE
          └─ 其他 -> TEXT
```

| 形态 | 框架行为 | 适用场景 |
| --- | --- | --- |
| `URL` | 直接把外链交给模型 | OpenAI 等支持外链图片的模型 |
| `INLINE` | 下载图片并转 Base64 | Anthropic、内网图片、需要避免外链依赖 |
| `TEXT` | 只给名称、类型、大小和访问地址 | 非视觉模型、超大图片、读取失败 |

图片下载或 Base64 转换失败时会自动降级为文字引用，并记录一条警告日志，不会中断整轮对话。

> 默认的 `ReadResourceTool` 会拒绝私网和环回地址，因此不会产生私网图片。如果自定义 `ReadResourceFetcher` 返回了私网 URL，中间件仍会将其按 `INLINE` 处理。

## 4. 中间件执行时机

`ReadArtifactMiddleware` 实现了三个关键回调：

| 回调 | 行为 |
| --- | --- |
| `afterToolExecution` | 识别读取工具，把成功的资源元信息放入当前运行的待处理队列 |
| `afterConcurrentToolExecution` | 并发批次逐个收集，保持批量读取可用 |
| `beforeModelCall` | 把队列中的资源装配为 `UserMessage` 写入会话记忆，再重建请求 |

因此模型看到的是：

```text
第 1 轮：模型决定调用 read_resource
        ↓
工具返回资源元信息
        ↓
中间件在下一次模型调用前追加一条用户消息：
        「以下是本轮读取到的资源，请结合附件内容继续回答。」
        + 图片 / 引用
        ↓
第 2 轮：模型基于附件继续回答
```

装配后的消息会写入 `ChatMemory`，后续轮次仍然可见。

### 注册顺序建议

`beforeModelCall` 按中间件注册顺序执行：

```java
.middlewares(Arrays.asList(
        userMessageAppendMiddleware,
        readMiddleware,
        compactMiddleware,
        loggingMiddleware))
```

把读取中间件放在上下文压缩之前，压缩才能看到新追加的资源消息；放在日志之前，日志才能记录最终请求。

## 5. 自定义图片加载

默认使用内置的 `UrlImageLoader` 读取 `http/https` 图片。如果你的图片在本地磁盘、OSS、S3 或需要签名访问，可以实现 `ImageLoader`：

```java
public class OssImageLoader implements ImageLoader {

    @Override
    public ImageBinary load(String url) {
        byte[] bytes = ossClient.read(url);
        return new ImageBinary(bytes, "image/png");
    }
}
```

启动时注册一次全局默认实现：

```java
AttachmentContentAssembler.setDefaultImageLoader(new OssImageLoader());
```

也可以在构建中间件时只对当前 Agent 生效：

```java
ReadArtifactMiddleware.builder()
        .imageLoader(new OssImageLoader())
        .build();
```

## 6. 识别自定义读取工具

如果你的业务已经有自己的读取工具，只要返回结构包含 `ReadBatchResult.SCHEMA`，中间件就能识别：

```java
ReadArtifactMiddleware.builder()
        .readToolNames("read_resource", "fetch_article")
        .schema(ReadBatchResult.SCHEMA)
        .build();
```

也可以通过自定义 `ReadResourceFetcher` 替换探测实现，例如接入内部文档服务：

```java
ReadResourceTool.builder()
        .fetcher((paths, hint) -> {
            ReadBatchResult batch = new ReadBatchResult();
            // 逐项探测并填充 ReadResult
            return batch;
        })
        .build();
```

自定义实现应继续遵守：

- 只接受允许的协议与主机；
- 限制单次资源数量与超时；
- 失败时返回 `ReadResult.failure(...)`，不要抛出未捕获异常；
- 返回结构保持与 `ReadResultJson` 一致。

## 7. 与直接上传附件的区别

| 场景 | 推荐方式 |
| --- | --- |
| 用户在本地上传文件 | 业务层读文件后构造 `AgentAttachment`，随 `AgentRequest.attachments` 传入 |
| 用户发来公网图片链接 | `ReadResourceTool` + `ReadArtifactMiddleware` |
| 用户发来内网图片链接 | 直接构造 `AgentAttachment`（`INLINE`），由业务 `ImageLoader` 读取 |
| 需要网页正文 / PDF / OCR | 自定义读取工具或 `AttachmentContentExtractor` 扩展 |

直接上传的附件由业务层完成归属校验和正文准备；阅读资源工具只处理模型主动决定要读的 URL。

## 8. 边界与建议

- 工具只做元信息探测，不负责正文抽取；
- 公网 URL 可达性由服务端判断，部署在特殊网络环境时要验证出口网络；
- 图片内联会增加请求体体积，网关与模型 Provider 需要同步放宽限制；
- 非视觉模型一律降级为文字引用，不要在提示词里要求它描述图片；
- 资源读取是“按需”的：模型不调用 `read_resource` 就不会产生附件；
- 生产环境建议记录读取失败的 URL 与错误码，便于排查不可达资源。

下一步：了解如何直接给 Agent 携带文本、图片和文件引用：[Agent 智能体：流式与非流式](../agent)。

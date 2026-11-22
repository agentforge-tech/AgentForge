# 无状态 Agent 读取资源对接实现（ReActAgentFactory）

> 适用版本：release_1.x
> 适用模块：`agentforge-service-backend/agentforge-service-web`
> 对接类：`cloud.changlu.agentforge.service.modules.agent.runtime.mode.react.ReActAgentFactory`
> 框架实现文档：[Agent无状态ReadTool与Middleware附件装配技术方案.md](../../../../../02、通用ReActagent层/middleware/Agent无状态ReadTool与Middleware附件装配技术方案.md)

---

## 一、背景

框架层已经提供无状态读取能力：

- `ReadResourceTool`：只接收 URL，提取图片/网页链接元数据；
- `ReadArtifactMiddleware`：`afterToolExecution` 收集元数据，`beforeModelCall` 转换为 `UserMessage` 附件；
- `UrlImageLoader`：INLINE 时按 URL 读取图片字节；
- `AttachmentContentAssembler`：与 `BaseAgent` 共用附件装配逻辑。

service 层要做的事情只有一件：**在 `ReActAgentFactory` 装配 Agent 时，把 Read Tool 注册进工具表，把 Read Middleware 注册进中间件链，并把当前模型能力传入 Middleware。**

<br/>

## 二、对接总览

```text
ReActAgentFactory.createReActAgent()
   │
   ├─ 读取 ModelConfigPojo / LlmModelConfig
   │     └─ supportsImage / provider
   │
   ├─ buildToolService()
   │     └─ 注册 ReadResourceTool(read_resource)
   │
   ├─ 构建 ReadArtifactMiddleware
   │     ├─ supportsImage(modelAdvancedConfig.isSupportsImage())
   │     └─ requiresInlineImage(provider == ANTHROPIC)
   │
   └─ ReActAgent.builder()
         .toolService(toolService)
         .middlewares(... userMessageAppend, readArtifact, compact ...)
         .build()

启动期（全局一次）：
AttachmentContentAssembler.setDefaultImageLoader(LocalFirstImageLoader)
```

运行时：

```text
用户：看下这张图 https://example.com/a.png
   │
   ▼
模型调用 read_resource(paths=["https://example.com/a.png"])
   │
   ▼
ReadResourceTool 只探测元数据
   │
   ▼
ReadArtifactMiddleware.afterToolExecution 收集
   │
   ▼
ReadArtifactMiddleware.beforeModelCall
   ├─ 公网 URL + 视觉模型 -> URL
   ├─ 私网 URL / Anthropic -> INLINE
   └─ 非视觉模型 / 超限 -> TEXT
   │
   ▼
AttachmentContentAssembler -> UserMessage(TextContent + ImageContent)
   │
   ▼
模型继续回答
```

<br/>

## 三、工具注册

`ReActAgentFactory.buildToolService` 中注册 `ReadResourceTool`：

```java
private ToolService buildToolService(
        StandardAgentConfig standardAgentConfig, boolean todoEnabled) {
    ToolService toolService = new ToolService();

    // 无状态读取资源工具：识图 / 网页链接引用
    toolService.tools(
            LocalToolFactory.buildLocalTools(
                    Collections.<Object>singletonList(ReadResourceTool.builder().build())));

    if (todoEnabled) {
        toolService.tools(
                LocalToolFactory.buildLocalToolsWithThought(
                        Collections.<Object>singletonList(todoPlanTool), true));
    }
    // HTTP / MCP 插件工具...
    return toolService;
}
```

说明：

- `ReadResourceTool.builder().build()` 已内置默认 `HttpReadResourceFetcher`、超时、大小、路径数量限制与日志；
- Tool 无状态，可以安全地在多个 Agent 之间复用一个实例；
- 当前所有 Standard ReAct Agent 默认具备 `read_resource` 工具。

<br/>

## 四、中间件注册

`ReActAgentFactory.createReActAgent` 中构建并加入中间件链：

```java
boolean requiresInlineImage =
        modelConfig.getProvider() != null
                && modelConfig.getProvider() == LlmProviderEnum.ANTHROPIC.getCode();

ReadArtifactMiddleware readArtifactMiddleware =
        ReadArtifactMiddleware.builder()
                .supportsImage(modelAdvancedConfig.isSupportsImage())
                .requiresInlineImage(requiresInlineImage)
                .build();

List<IAgentMiddleware> middlewares = new ArrayList<IAgentMiddleware>();
middlewares.add(userMessageAppendMiddleware);
middlewares.add(readArtifactMiddleware);
middlewares.add(compactSummarizationMiddleware);
middlewares.add(new LoggingIAgentMiddleware());
middlewares.add(streamingMiddleware);
middlewares.add(sessionLifecycleMiddleware);
```

### 4.1 顺序说明

推荐位置：

```text
UserMessageAppendMiddleware
ReadArtifactMiddleware
CompactSummarizationMiddleware
Logging / Streaming / SessionLifecycle
Todo / Trace
```

原因：

- Read Middleware 在 `beforeModelCall` 追加本轮读取到的资源；
- 放在压缩中间件之前，让新增附件参与同一轮 Token 预算判断；
- 放在追加消息之后，避免运行期补充消息与读取资源互相覆盖。

<br/>

## 五、模型能力映射

工厂已经能拿到当前模型的 `LlmModelConfig` 与 provider，因此不需要业务代码再维护模型白名单。

```java
boolean supportsImage = modelAdvancedConfig.isSupportsImage();
boolean requiresInlineImage =
        modelConfig.getProvider() != null
                && modelConfig.getProvider() == LlmProviderEnum.ANTHROPIC.getCode();
```

| 条件 | Middleware 行为 |
|---|---|
| `supportsImage=false` | 图片统一降级为 TEXT 引用 |
| `requiresInlineImage=true`（Anthropic） | 图片强制 INLINE，避免外链协议错误 |
| 公网 URL + 视觉模型 | 使用 URL，节省 Base64 体积 |
| 私网 URL + 视觉模型 | INLINE，读取 URL 字节后转 Base64 |
| INLINE 且图片超过 4MB | 降级 TEXT，避免临时 Base64 放大 |

上下文扩展仍可覆盖默认值：

```java
context.putExtension("attachment.supportsImage", false);
context.putExtension("attachment.requiresInlineImage", true);
```

<br/>

## 六、ImageLoader 收敛到装配器

`ImageLoader` 不再由 `ReActAgentFactory`、`ReActAgent` 或 `ReadArtifactMiddleware` 分别注入，统一收敛到静态工具类 `AttachmentContentAssembler`：

| 位置 | 变化 |
|---|---|
| `AttachmentContentAssembler` | 内置默认 `UrlImageLoader`，静态方法直接使用 |
| `BaseAgent` | 移除 `setImageLoader`，直接调用静态装配方法 |
| `ReActAgent.Builder` | 移除 `imageLoader(...)` |
| `ReadArtifactMiddleware.Builder` | `imageLoader(...)` 仅保留为可选覆盖（测试/特殊场景） |
| service 启动配置 | 通过 `setDefaultImageLoader` 注册一次 `LocalFirstImageLoader` |

```java
@Configuration
public class AttachmentImageLoaderConfiguration {

    public AttachmentImageLoaderConfiguration(LocalFirstImageLoader localFirstImageLoader) {
        AttachmentContentAssembler.setDefaultImageLoader(localFirstImageLoader);
    }
}
```

因此用户直接上传附件与 Agent 主动读取 URL，最终使用同一套图片加载与降级逻辑，且无需在每个 Agent 装配处重复传递 `ImageLoader`。

<br/>

## 七、临时资源控制

| 控制点 | 行为 |
|---|---|
| 暂存键 | `memoryId:executionId`，按 run 隔离 |
| beforeModelCall | `drain` 后立即 `pending.remove(key)`，暂存元数据队列释放 |
| 数量 | **不设二次上限**，按上一轮实际读取结果装配 |
| 去重 | 默认按 `dedupeKey` 去重 |
| INLINE 体积 | 超过 `maxInlineImageBytes`（默认 4MB）降级 TEXT |
| 停止/异常 | `onStop` / `onStopWithError` 清理当前 run 队列 |
| Tool 探测 | 只读取响应头，不下载正文 |
| ImageLoader | 流式读取、无缓存、方法结束释放字节数组 |

<br/>

## 八、日志

Read Tool 与 Read Middleware 默认使用 JUL 打印关键步骤：

| 日志 | 说明 |
|---|---|
| `read_resource.start` | paths 数量、hint 摘要 |
| `read_resource.probe` | status、content-type、size、耗时 |
| `read_resource.done` | success / failed / elapsedMs |
| `read_middleware.collect` | accepted / rejected / duplicated |
| `read_middleware.convert` | url、kind、payload |
| `read_middleware.inline` | 触发 INLINE 的原因 |
| `read_middleware.degrade` | 无 loader、超限、降级原因 |

URL 查询参数会打码，不打印 Base64。

<br/>

## 九、自定义

### 9.1 调整 Tool 限制

```java
ReadToolOptions options =
        ReadToolOptions.builder()
                .maxPaths(5)
                .maxImageBytes(10 * 1024 * 1024)
                .timeoutMillis(5000)
                .build();

ReadResourceTool tool = ReadResourceTool.builder().options(options).build();
```

### 9.2 调整 Middleware

```java
ReadArtifactMiddleware middleware =
        ReadArtifactMiddleware.builder()
                .supportsImage(true)
                .requiresInlineImage(false)
                .maxInlineImageBytes(4L * 1024 * 1024)
                .dedupeEnabled(true)
                .build();
```

图片加载器默认使用 `AttachmentContentAssembler` 的全局默认实现；只有测试或特殊场景才需要显式调用 `.imageLoader(customLoader)` 覆盖。

### 9.3 关闭读取能力

当前默认对 Standard ReAct Agent 开启。若需要按 Agent 配置关闭，可在 `StandardAgentConfig` 增加开关，再在 `ReActAgentFactory` 中条件注册 Tool 与 Middleware。

<br/>

## 十、验证

### 10.1 自动验证

```bash
mvn -pl agentforge-framework/agentforge-agent-core -am -DskipTests package
mvn -pl agentforge-service/agentforge-service-backend/agentforge-service-web -am -DskipTests package
```

核心测试：

- `ReadResultJsonTest`：契约 JSON 往返；
- `ReadArtifactMiddlewareTest`：收集 + 装配。

### 10.2 手工验证

| 场景 | 预期 |
|---|---|
| 用户粘贴公网图片 URL | 模型调用 `read_resource`，视觉模型看到图片 |
| 用户粘贴网页链接 | 模型调用 `read_resource`，收到 LINK 的 TEXT 引用 |
| 非视觉模型 + 图片 URL | 图片降级为 TEXT 引用，不报错 |
| Anthropic + 图片 URL | 强制 INLINE，协议报文为 `source.base64` |
| 同一 URL 重复读取 | 同一轮内只装配一次 |
| 本轮结束 / 异常 | 暂存队列被清理 |

---

## 十一、变更记录

| 日期 | 内容 |
|---|---|
| 2026-11-22 | service 层对接：`ReActAgentFactory` 注册 ReadResourceTool 与 ReadArtifactMiddleware |
| 2026-11-22 | 按模型 `supportsImage` 与 Anthropic provider 自动设置 Middleware 默认能力 |
| 2026-11-22 | `AttachmentContentAssembler` 改为静态工具类，避免每次构建用户消息都实例化 |
| 2026-11-22 | `ImageLoader` 收敛到装配器：Agent/Factory 不再注入，service 启动时注册 `LocalFirstImageLoader` 为全局默认 |
| 2026-11-22 | `ReadResult.kind` 改为 `ReadResourceKind` 枚举，Middleware 使用枚举分支判断 |
| 2026-11-22 | INLINE 超过 4MB 降级 TEXT |
| 2026-11-22 | 暂存队列按 run 隔离，beforeModel drain 后释放，停止/异常清理 |

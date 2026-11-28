---
title: Agent 智能体：流式与非流式
sidebar_label: Agent智能体（流式、非流式）
description: 使用 ReActAgent 组织 think → act → 记忆写回循环，并在同步与流式之间自由切换
sidebar_position: 2
displayed_sidebar: sdkSidebar
---

# Agent 智能体：流式与非流式

AgentForge 的 Agent 运行时围绕 ReAct 循环组织：模型先思考是否需要调用工具，需要时执行工具并把结果写回会话记忆，再进入下一轮思考，直到模型给出最终回答或达到最大步数。

同一个 `ReActAgent` 实例、同一套配置、同一个 `memoryId`，既可以用 `run()` 同步执行，也可以用 `runStream()` 流式执行。

## 依赖（v1.0.0）

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-agent-core</artifactId>
    <version>1.0.2-SNAPSHOT</version>
</dependency>
```

`agentforge-agent-core` 会传递引入 `agentforge-model-api` 与 `agentforge-model-core`。实际运行时还需要至少一个模型 Provider：

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-model-openai</artifactId>
    <version>1.0.2-SNAPSHOT</version>
</dependency>
```

## 1. ReAct 循环

```text
User Question
     │
     ▼
┌─────────────────────────────┐
│ step = 1                    │
│  check cancel               │
│  think  ──► ChatModel       │
│    │                        │
│    ├── 无工具调用 ──► STOP   │
│    │                        │
│    └── 有工具调用            │
│          act ──► ToolService│
│           │                 │
│           └── 写回记忆 ──────┘
│                  step + 1
└─────────────────────────────┘
```

- **think**：携带当前会话消息与全部工具声明调用模型；
- **act**：执行模型返回的 `ToolExecutionRequest`，把结果写回记忆；
- **stop**：模型不再请求工具时结束，返回文本回答；
- **maxSteps**：达到步长上限时返回 `MAX_STEPS` 状态。

## 2. 核心类分工

| 类 | 职责 |
| --- | --- |
| `IAgent` | 对外契约：`run` / `runStream` / `cancel` / `clearContext` |
| `BaseAgent` | 上下文初始化、用户消息写入、按 `memoryId` 维护取消标志 |
| `Agent` | 非流式模板方法、流式入口、运行参数校验 |
| `BaseReActAgent` | 单步 `step()`：取消检查 → think → 取消检查 → act |
| `ReActAgent` | 装配模型、工具、记忆、系统提示词与中间件 |
| `ReActTokenStream` | 流式驱动器：用回调递归推进 think → act 循环 |

## 3. 构建 Agent

```java
import cloud.changlu.agentforge.agent.ReActAgent;
import cloud.changlu.agentforge.agent.domain.AgentSettings;
import cloud.changlu.agentforge.agent.extend.memory.window.MessageWindowChatMemoryProvider;
import cloud.changlu.agentforge.model.tool.execution.ToolService;

ToolService toolService = new ToolService();
toolService.tools(new WeatherTools());

ReActAgent agent = ReActAgent.builder()
        .agentName("weather-agent")
        .description("查询天气的 ReAct Agent")
        .systemPrompt("你是一个天气助手，需要时调用工具，不要编造数据。")
        .chatModel(chatModel)
        .streamingChatModel(streamingChatModel)
        .chatMemoryProvider(new MessageWindowChatMemoryProvider(50))
        .toolService(toolService)
        .agentSettings(AgentSettings.builder()
                .maxSteps(10)
                .aiCallRetry(3)
                .aiCallRetryDelay(2000)
                .enableConcurrentToolExecution(true)
                .build())
        .build();
```

### Builder 参数

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `chatModel` | 非流式必填 | 同步模型 |
| `streamingChatModel` | 流式必填 | 流式模型 |
| `chatMemoryProvider` | 是 | 按 `memoryId` 提供会话记忆 |
| `toolService` | 非流式必填 | 工具声明与执行器注册中心 |
| `systemPrompt` | 否 | 每轮注入的系统提示词 |
| `agentSettings` | 否 | 步长、重试、并发工具开关 |
| `middleware(s)` | 否 | 按注册顺序执行的中间件 |

> 建议同时设置 `chatModel` 与 `streamingChatModel`，这样同一个 Agent 可以在同步与流式之间自由切换。

## 4. 非流式执行

```java
import cloud.changlu.agentforge.agent.domain.AgentRequest;
import cloud.changlu.agentforge.agent.step.ChatResult;

ChatResult result = agent.run(AgentRequest.builder()
        .memoryId("user-1")
        .question("北京今天天气怎么样？")
        .build());

System.out.println(result.getRes());
System.out.println(result.getRunState().getDescription());
```

`run()` 会阻塞到整个 ReAct 循环结束，返回：

- `getRes()`：最终回答，或取消/步长限制的说明；
- `getRunState()`：`SUCCESS`、`MODEL_CALL_ERROR`、`CANCEL`、`MAX_STEPS`；
- `getRunStateCode()` / `getRunStateDescription()`：便于接口层直接返回。

## 5. 流式执行

```java
agent.runStream(AgentRequest.builder()
                .memoryId("user-1")
                .question("上海呢？")
                .build())
        .onPartialThinking(thinking ->
                System.out.print("[think] " + thinking.text()))
        .onPartialResponse(token ->
                System.out.print(token))
        .onToolExecuted(execution -> {
            System.out.println("\n[tool] " + execution.request().name());
            System.out.println("[result] " + execution.result().text());
        })
        .onIntermediateResponse(response ->
                System.out.println("[next-round] toolCalls="
                        + response.aiMessage().toolExecutionRequests().size()))
        .onCompleteResponse(response ->
                System.out.println("\n[done] " + response.aiMessage().text()))
        .onError(error ->
                System.err.println("[error] " + error.getMessage()))
        .start();
```

### TokenStream 回调

| 回调 | 触发时机 | 典型用途 |
| --- | --- | --- |
| `onPartialThinking` | 模型产生推理增量 | 展示思考过程 |
| `onPartialResponse` | 模型产生可见文本增量 | 逐字渲染回答 |
| `onToolExecuted` | 单个工具执行完成 | 展示工具进度与结果 |
| `onIntermediateResponse` | 本轮有工具调用，工具执行后 | 标记进入下一轮 |
| `onCompleteResponse` | 最终回答完成 | 结束渲染、写入审计 |
| `onError` | 执行失败或被取消 | 错误兜底 |

`runStream()` 返回句柄时不会立即发起请求，注册回调后调用 `start()` 才开始执行。

## 6. AgentSettings

```java
AgentSettings settings = AgentSettings.builder()
        .maxSteps(10)                          // 最大 think + act 轮数，默认 10
        .aiCallRetry(3)                        // 模型调用失败重试次数，默认 3
        .aiCallRetryDelay(2000)                // 首次重试等待 2s，后续指数退避
        .enableConcurrentToolExecution(true)   // 同一轮多个工具并发执行，默认开启
        .build();
```

- `enableConcurrentToolExecution` 仅在工具数量大于 1 时生效；
- 并发执行时会自动在系统提示词中追加并发调用提示；
- 非流式路径会使用 `aiCallRetry` 做指数退避重试。

## 7. 会话记忆

```java
ChatMemoryProvider memoryProvider = new MessageWindowChatMemoryProvider(50);
```

- 同一个 `memoryId` 复用同一份记忆；
- 窗口默认只保留最近 50 条消息；
- 系统消息始终保留，淘汰带工具调用的 AI 消息时会同步淘汰对应工具结果；
- 可通过 `ChatMemoryStore` 替换为 MySQL、Redis 等持久化实现。

```java
agent.clearContext("user-1");
```

## 8. 取消执行

```java
agent.cancel("user-1");
```

取消是**协作式**的：

- 非流式在每轮 `think` 前后检查取消标记；
- 流式在每次 `chat()` 入口检查取消标记；
- 已经发出的 HTTP 请求或正在执行的工具不会被强制中断，因此取消延迟上限是当前一次模型调用或工具调用。

## 9. 自定义 Agent

如果 ReAct 不足以表达你的执行策略，可以继承模板类：

```java
public class MyAgent extends Agent {

    @Override
    protected StepResult step(int currentStep, AgentChatContext chatContext) {
        // 自定义单步执行策略
        return StepResult.toStop("done");
    }

    @Override
    protected TokenStream stepStream(AgentChatContext chatContext) {
        // 返回自定义流式驱动器
        return null;
    }

    // 同时实现 getChatModel / getChatMemoryProvider / getToolService /
    // getAgentSettings / getMiddlewareManager
}
```

大多数场景不需要自定义 Agent，优先通过工具、中间件和 `AgentSettings` 扩展。

## 10. 携带附件（图片 / 文本 / 文件引用）

`AgentRequest` 支持通过 `attachments` 携带附件。框架会把附件装配成 `UserMessage(List<Content>)`，与用户问题一起写入会话记忆；同步执行和流式执行使用同一个请求结构。

### 10.1 最小示例

```java
import cloud.changlu.agentforge.agent.domain.AgentAttachment;
import cloud.changlu.agentforge.agent.domain.AgentAttachmentType;
import cloud.changlu.agentforge.agent.domain.AttachmentPayloadType;
import cloud.changlu.agentforge.agent.domain.AgentRequest;

AgentRequest request = AgentRequest.builder()
        .memoryId("session-001")
        .question("请描述这张图片的内容")
        .attachments(Arrays.asList(
                AgentAttachment.builder()
                        .fileId(1024L)
                        .name("报错截图.png")
                        .type(AgentAttachmentType.IMAGE)
                        .fileType("png")
                        .size(183462L)
                        .url("https://example.com/upload/8f3c.png")
                        .payloadType(AttachmentPayloadType.URL)
                        .build()))
        .build();

ChatResult result = agent.run(request);
```

流式执行只需要把同一个 `request` 交给 `runStream()`：

```java
agent.runStream(request)
        .onPartialResponse(token -> System.out.print(token))
        .start();
```

### 10.2 三类附件

| 附件类型 | `type` | 推荐载荷 | 框架行为 |
| --- | --- | --- | --- |
| 纯文本 | `TEXT` | `TEXT` | 优先使用 `text` 正文；为空时生成带 URL 的引用 |
| 图片 | `IMAGE` | `URL` / `INLINE` / `TEXT` | 外链、Base64 或文字引用 |
| 压缩包 / 办公文档 / 未知类型 | `UNKNOWN` | `TEXT` | 只生成名称、类型、大小和访问地址引用 |

### 10.3 文本附件

框架不会读取本地文件。文本正文由业务层准备好后放入 `text`：

```java
AgentAttachment doc = AgentAttachment.builder()
        .fileId(2048L)
        .name("需求说明.md")
        .type(AgentAttachmentType.TEXT)
        .fileType("md")
        .size(8421L)
        .url("https://example.com/upload/需求说明.md")
        .payloadType(AttachmentPayloadType.TEXT)
        .text("以下是附件正文内容：\n......")
        .build();
```

如果 `text` 为空，模型只会收到文件名、类型、大小和访问地址。单条消息的附件数量、单附件正文长度应由业务层限制，避免上下文被撑爆。

### 10.4 图片附件

图片有三种交给模型的方式：

```java
// 1. 外链：直接把 URL 交给支持外链图片的模型
AgentAttachment urlImage = AgentAttachment.builder()
        .name("chart.png")
        .type(AgentAttachmentType.IMAGE)
        .fileType("png")
        .url("https://example.com/chart.png")
        .payloadType(AttachmentPayloadType.URL)
        .build();

// 2. 内联：框架下载图片并转 Base64，适合私网地址或 Anthropic
AgentAttachment inlineImage = AgentAttachment.builder()
        .name("chart.png")
        .type(AgentAttachmentType.IMAGE)
        .fileType("png")
        .url("https://example.com/chart.png")
        .payloadType(AttachmentPayloadType.INLINE)
        .build();

// 3. 文字引用：模型不支持图片，或图片过大时降级
AgentAttachment textImage = AgentAttachment.builder()
        .name("chart.png")
        .type(AgentAttachmentType.IMAGE)
        .fileType("png")
        .url("https://example.com/chart.png")
        .payloadType(AttachmentPayloadType.TEXT)
        .build();
```

`INLINE` 默认使用内置的 `UrlImageLoader` 下载 `http/https` 图片。图片读取或 Base64 转换失败时，框架会自动降级为文字引用，不会中断整轮对话。

> 直连 SDK 时，框架按你声明的 `payloadType` 装配，不会自动判断模型是否支持图片。如果模型不支持视觉输入，请把图片设为 `TEXT`。AgentForge Service 会依据模型能力自动完成这层路由。

### 10.5 自定义图片加载

如果图片在本地磁盘、OSS、S3 或需要签名访问，可以实现 `ImageLoader` 并注册为全局默认实现：

```java
public class OssImageLoader implements ImageLoader {

    @Override
    public ImageBinary load(String url) {
        byte[] bytes = ossClient.read(url);
        return new ImageBinary(bytes, "image/png");
    }
}

AttachmentContentAssembler.setDefaultImageLoader(new OssImageLoader());
```

只需要在启动阶段设置一次；后续所有 `INLINE` 图片都会使用该实现。

### 10.6 常见约束

- `AgentRequest.Builder.build()` 要求 `question` 非空。只发附件时，请注入占位问题，例如「我上传了附件，请结合附件内容回答。」；
- `payloadType` 为空时默认 `TEXT`，`type` 为空时默认 `UNKNOWN`；
- `URL` / `INLINE` 只对 `IMAGE` 生效；
- 附件归属校验、数量限制、正文截断属于业务层职责，框架不重复校验；
- 如果附件来自用户提供的 URL，而不是本地上传，可以搭配 [阅读资源 Middleware](./middleware/read) 让模型按需读取。

## 11. 常见问题

| 现象 | 原因与处理 |
| --- | --- |
| 一直循环到最大步数 | 工具描述不清晰或工具结果无法让模型收敛；补充系统提示词并检查工具返回 |
| 工具调用没有被执行 | 工具未注册到 `ToolService`，或模型没有看到工具声明 |
| 流式没有输出思考内容 | 模型本身不返回 reasoning 增量，或 Provider 未开启 `returnThinking` |
| 同一个会话上下文错乱 | 同一个 `memoryId` 并发运行了多轮；应由业务层串行化 |
| 取消后下一轮立即被取消 | 检查是否复用了旧实例；`prepareForNewRun` 会清理上一轮残留标记 |

下一步：了解执行过程中的 [消息事件](./message-event)。

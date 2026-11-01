---
title: Model 模型协议层
sidebar_label: Model
description: 用一套 Provider-neutral 契约完成 OpenAI、Anthropic 与 OpenAI-compatible 模型的同步和流式调用
sidebar_position: 1
displayed_sidebar: sdkSidebar
---

# Model 模型协议层

Model 层是 AgentForge 最底层、最稳定的模型访问抽象。它不绑定任何厂商 SDK，只定义统一的请求、响应、消息、流式回调、HTTP 传输与异常边界。上层 Agent 只依赖 `agentforge-model-api`，因此切换模型时不需要修改 ReAct 主循环。

## 依赖（v1.0.0）

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-model-openai</artifactId>
    <version>1.0.0</version>
</dependency>
```

Anthropic：

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-model-anthropic</artifactId>
    <version>1.0.0</version>
</dependency>
```

需要按配置动态选择 Provider 时，引入工厂模块：

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-model-registry</artifactId>
    <version>1.0.0</version>
</dependency>
```

也可以使用 BOM 统一管理版本：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>cloud.changlu.agentforge</groupId>
            <artifactId>agentforge-ai-bom</artifactId>
            <version>1.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

## 1. 这一层解决什么问题

如果 Agent 直接依赖某个厂商 SDK，会同时绑死三样东西：SDK 类型、请求/响应结构、字段命名。换成 Anthropic 时，system 变成顶层字段、工具变成 `tool_use`、响应变成 content block 数组，上层代码只能重写。

AgentForge 的边界非常明确：

```text
Agent / Application
       │
       ▼
ChatModel / StreamingChatModel          <- 统一入口
       │
       ▼
ChatRequest + Message + Parameters      <- 统一入参
       │
       ▼
Provider Adapter(OpenAI / Anthropic)    <- 只做协议转换
       │
       ▼
HttpTransport -> Provider HTTP API      <- 厂商差异隔离在 Provider 内部
       │
       ▼
ChatResponse + TokenUsage + FinishReason <- 统一出参
```

一句话：**上层永远看到 `ChatRequest` 进、`ChatResponse` 出；厂商协议只存在于 Provider 模块内部。**

## 2. 核心契约

```text
标准 ChatModel 协议
├── 入口契约：ChatModel / StreamingChatModel
├── 入参契约：ChatRequest + ChatRequestParameters（+ ToolChoice）
├── 消息契约：System / User / Ai / ToolResult / Custom
├── 出参契约：ChatResponse + TokenUsage + FinishReason
├── 流式契约：StreamingChatResponseHandler
├── 传输契约：HttpTransport / StreamingHttpResponseHandler
└── 异常契约：ModelException
```

### 2.1 ChatModel

```java
@FunctionalInterface
public interface ChatModel {

    ChatResponse chat(ChatRequest chatRequest);

    default String chat(String userMessage);
    default ChatResponse chat(ChatMessage... messages);
    default ChatResponse chat(List<? extends ChatMessage> messages);
}
```

Provider 只需要实现主入口 `ChatResponse chat(ChatRequest)`，三个便利方法都是 `default`，最终收敛为同一个 `ChatRequest`。

### 2.2 StreamingChatModel

```java
public interface StreamingChatModel {

    void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler);

    default void chat(String userMessage, StreamingChatResponseHandler handler);
    default void chat(StreamingChatResponseHandler handler, ChatMessage... messages);
    default void chat(List<? extends ChatMessage> messages, StreamingChatResponseHandler handler);
}
```

AgentForge 选择回调式流式 API，而不是 Reactor / RxJava，原因是协议层要足够轻、且必须兼容 Java 8。流式生命周期被固定为：

```text
onPartialThinking(partialThinking)   0..N 次   推理增量，可选
onPartialResponse(partialText)       0..N 次   可见文本增量
onCompleteResponse(response)         1 次      聚合完成的标准响应
onError(error)                       1 次      失败
```

`onCompleteResponse` 返回的仍然是标准 `ChatResponse`，工具调用增量、token usage、finish reason 都由 Provider 在流结束前聚合完成。

## 3. 发起同步请求

```java
import cloud.changlu.agentforge.model.chat.message.SystemMessage;
import cloud.changlu.agentforge.model.chat.message.UserMessage;
import cloud.changlu.agentforge.model.chat.request.ChatRequest;
import cloud.changlu.agentforge.model.chat.request.DefaultChatRequestParameters;
import cloud.changlu.agentforge.model.chat.response.ChatResponse;

ChatRequest request = ChatRequest.builder()
        .message(SystemMessage.from("你是一个 Java 助手。"))
        .message(UserMessage.from("解释一下 CompletableFuture。"))
        .parameters(DefaultChatRequestParameters.builder()
                .modelName("gpt-4o-mini")
                .temperature(0.2)
                .maxTokens(1024)
                .build())
        .build();

ChatResponse response = chatModel.chat(request);

System.out.println(response.aiMessage().text());
System.out.println(response.finishReason());
System.out.println(response.tokenUsage().totalTokens());
```

### 参数合并规则

`DefaultChatRequestParameters.merge(defaults, overrides)` 会先应用模型级默认参数，再应用请求级参数：

```text
Model Builder defaults
        │
        ▼
merge(defaults, overrides)
        ▲
        │
Request overrides
```

- 请求级非空值覆盖模型级默认值；
- `customParameters` 按 Map 合并，同名 key 以请求级为准；
- `tools`、`toolChoice` 等默认方法允许后续扩展而不破坏已有实现。

## 4. 发起流式请求

```java
import cloud.changlu.agentforge.model.chat.response.StreamingChatResponseHandler;

streamingChatModel.chat(request, new StreamingChatResponseHandler() {
    @Override
    public void onPartialThinking(String thinking) {
        System.out.print("[think] " + thinking);
    }

    @Override
    public void onPartialResponse(String token) {
        System.out.print(token);
    }

    @Override
    public void onCompleteResponse(ChatResponse response) {
        System.out.println("\n[done] " + response.aiMessage().text());
    }

    @Override
    public void onError(Throwable error) {
        error.printStackTrace();
    }
});
```

如果是推理模型，`onPartialThinking` 会先收到 `reasoning_content` / `thinking` 增量；不需要时保持默认空实现即可。

## 5. 选择 Provider

### 5.1 直接构建（推荐用于固定模型）

```java
ChatModel chatModel = OpenAiChatModel.builder()
        .baseUrl("https://api.openai.com/v1")
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o-mini")
        .temperature(0.2)
        .maxTokens(1024)
        .build();
```

Anthropic：

```java
AnthropicChatModel chatModel = AnthropicChatModel.builder()
        .apiKey(System.getenv("ANTHROPIC_API_KEY"))
        .modelName("claude-3-5-sonnet-latest")
        .maxTokens(2048)
        .build();
```

### 5.2 通过 LlmFactory 动态构建

适合模型配置来自数据库、管理后台或配置文件的场景：

```java
import cloud.changlu.agentforge.model.registry.LlmFactory;
import cloud.changlu.agentforge.model.registry.config.LlmBasicConfig;
import cloud.changlu.agentforge.model.registry.enums.LlmEnum;

LlmBasicConfig config = LlmBasicConfig.builder()
        .provider(LlmEnum.OPENAI.getCode())   // 1 = OpenAI，2 = Anthropic
        .url("https://api.openai.com/v1")
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o-mini")
        .prop("temperature", "0.2")
        .prop("maxTokens", "1024")
        .build();

ChatModel chatModel = LlmFactory.buildChatModel(config);
StreamingChatModel streamingChatModel = LlmFactory.buildStreamChatModel(config);
```

### 5.3 OpenAI-compatible 服务

`OpenAiChatModel` 的 `baseUrl` 可配置，因此 DashScope、Ollama、Xinference 等兼容服务可以直接复用：

```java
ChatModel model = OpenAiChatModel.builder()
        .baseUrl("http://localhost:11434/v1")
        .apiKey("ollama")
        .modelName("qwen2.5:7b")
        .build();
```

## 6. 厂商能力如何映射

| 统一概念 | OpenAI Chat Completions | Anthropic Messages |
| --- | --- | --- |
| `SystemMessage` | `messages[role=system]` | 顶层 `system` 字段 |
| `UserMessage` | `messages[role=user]` | `content` 中的 `text` block |
| `AiMessage` | `choices[0].message` | `content` block 数组 |
| `ToolExecutionRequest` | `tool_calls` | `tool_use` block |
| `ToolExecutionResultMessage` | `role=tool` | `tool_result` block |
| 流式文本 | SSE `delta.content` | SSE `content_block_delta` |
| 推理增量 | `reasoning_content` | `thinking` block |
| Token 用量 | `usage.prompt_tokens` 等 | `usage.input_tokens` 等 |

所有字段差异都由 Provider 消化，Agent 层不需要写 `if (provider == ...)`。

## 7. 异常与稳定性

模型调用失败统一抛出 `ModelException`：

```java
try {
    ChatResponse response = chatModel.chat(request);
} catch (ModelException e) {
    System.err.println("HTTP: " + e.statusCode());
    System.err.println("Body: " + e.responseBody());
}
```

`Agent` 层会对非流式模型调用执行指数退避重试，重试次数与首次等待时间由 `AgentSettings` 控制：

```java
AgentSettings settings = AgentSettings.builder()
        .aiCallRetry(3)
        .aiCallRetryDelay(2000)
        .build();
```

## 8. 最佳实践

1. **业务代码依赖接口**：注入 `ChatModel` / `StreamingChatModel`，不要在业务层直接 `new` Provider；
2. **参数分层**：模型级配置放 Builder，单次请求差异放 `ChatRequestParameters`；
3. **不要依赖厂商字段**：需要厂商特有能力时使用 `customParameters`，不要在业务层解析厂商 JSON；
4. **流式回调保持轻量**：Provider 可能从 I/O 线程回调，耗时逻辑应转交业务线程池；
5. **统一记录 Token**：`ChatResponse.tokenUsage()` 已归一化，可直接用于计费与监控。

下一步：把模型接入 [Agent 智能体（流式、非流式）](./agent)。

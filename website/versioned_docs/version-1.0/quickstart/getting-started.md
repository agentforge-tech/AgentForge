---
title: 快速开始
sidebar_label: 快速开始
description: 从 Maven 依赖、模型配置到第一个 ReAct Agent 的完整上手路径
sidebar_position: 2
displayed_sidebar: sdkSidebar
---

# 快速开始

本文用最短路径完成三件事：引入依赖、调用模型、构建一个可以调用工具的 ReAct Agent。示例代码均基于 **AgentForge 1.0.0**。

## 1. 环境要求

- JDK 8 或 JDK 17（推荐 JDK 17，公共 API 兼容 Java 8 bytecode）
- Maven 3.8+
- 一个可用的模型 API Key（OpenAI、Anthropic 或 OpenAI-compatible 服务）

## 2. 引入最新依赖

推荐使用 BOM 统一管理版本：

```xml
<properties>
    <agentforge.version>1.0.0</agentforge.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>cloud.changlu.agentforge</groupId>
            <artifactId>agentforge-ai-bom</artifactId>
            <version>${agentforge.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- Agent 运行时 -->
    <dependency>
        <groupId>cloud.changlu.agentforge</groupId>
        <artifactId>agentforge-agent-core</artifactId>
    </dependency>

    <!-- OpenAI / OpenAI-compatible Provider -->
    <dependency>
        <groupId>cloud.changlu.agentforge</groupId>
        <artifactId>agentforge-model-openai</artifactId>
    </dependency>

    <!-- 如需 Anthropic，再额外引入 -->
    <dependency>
        <groupId>cloud.changlu.agentforge</groupId>
        <artifactId>agentforge-model-anthropic</artifactId>
    </dependency>
</dependencies>
```

如果只想先跑通模型调用，也可以只引入 Provider：

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-model-openai</artifactId>
    <version>1.0.0</version>
</dependency>
```

## 3. 调用第一个模型

```java
import cloud.changlu.agentforge.model.chat.ChatModel;
import cloud.changlu.agentforge.model.openai.OpenAiChatModel;

public class FirstCall {
    public static void main(String[] args) {
        ChatModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini")
                .temperature(0.2)
                .maxTokens(512)
                .build();

        String answer = model.chat("用一句话解释什么是 ReAct Agent。");
        System.out.println(answer);
    }
}
```

`baseUrl` 默认是 `https://api.openai.com/v1`。对接 DashScope、Ollama、Xinference 等 OpenAI-compatible 服务时，只需替换 `baseUrl` 与 `modelName`：

```java
ChatModel model = OpenAiChatModel.builder()
        .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-plus")
        .build();
```

## 4. 定义一个本地工具

```java
import cloud.changlu.agentforge.model.tool.P;
import cloud.changlu.agentforge.model.tool.Tool;

public class WeatherTools {

    @Tool("查询指定城市的实时天气")
    public String getWeather(
            @P(name = "city", description = "城市名称，例如：北京") String city) {
        return "{\"city\":\"" + city + "\",\"weather\":\"晴\",\"temperature\":\"26℃\"}";
    }
}
```

`@Tool` 方法的返回值会自动转换为工具结果并写回会话记忆。详细规则见 [Tool 扩展设计](../sdk/tool)。

## 5. 构建 Agent

```java
import cloud.changlu.agentforge.agent.ReActAgent;
import cloud.changlu.agentforge.agent.domain.AgentRequest;
import cloud.changlu.agentforge.agent.domain.AgentSettings;
import cloud.changlu.agentforge.agent.extend.memory.window.MessageWindowChatMemoryProvider;
import cloud.changlu.agentforge.agent.step.ChatResult;
import cloud.changlu.agentforge.model.chat.ChatModel;
import cloud.changlu.agentforge.model.chat.StreamingChatModel;
import cloud.changlu.agentforge.model.openai.OpenAiChatModel;
import cloud.changlu.agentforge.model.openai.OpenAiStreamingChatModel;
import cloud.changlu.agentforge.model.tool.execution.ToolService;

public class FirstAgent {
    public static void main(String[] args) {
        ChatModel chatModel = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini")
                .build();

        StreamingChatModel streamingChatModel = OpenAiStreamingChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini")
                .build();

        ToolService toolService = new ToolService();
        toolService.tools(new WeatherTools());

        ReActAgent agent = ReActAgent.builder()
                .agentName("weather-agent")
                .description("查询天气的 ReAct Agent")
                .systemPrompt("你是一个天气助手，需要时调用工具，不要编造天气数据。")
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

        ChatResult result = agent.run(AgentRequest.builder()
                .memoryId("user-1")
                .question("北京今天天气怎么样？")
                .build());

        System.out.println(result.getRes());
        System.out.println(result.getRunState().getDescription());
    }
}
```

## 6. 切换为流式输出

同一个 Agent 实例、同一个 `memoryId`，可以直接切换为流式执行：

```java
agent.runStream(AgentRequest.builder()
                .memoryId("user-1")
                .question("上海呢？")
                .build())
        .onPartialThinking(thinking -> System.out.print(thinking.text()))
        .onPartialResponse(token -> System.out.print(token))
        .onToolExecuted(execution ->
                System.out.println("\n[tool] " + execution.request().name()))
        .onCompleteResponse(response ->
                System.out.println("\n[done] " + response.aiMessage().text()))
        .onError(Throwable::printStackTrace)
        .start();
```

`runStream()` 返回的是懒启动句柄：注册完回调后调用 `start()` 才真正发起模型请求。

## 7. 会话、取消与清理

- **多轮会话**：使用相同的 `memoryId`，Agent 会自动从 `ChatMemoryProvider` 读取并写回上下文；
- **取消执行**：调用 `agent.cancel(memoryId)`，Agent 会在下一轮 think 或下一次模型调用前协作式退出；
- **清理上下文**：调用 `agent.clearContext(memoryId)` 清空该会话记忆；
- **并发约束**：同一个 `memoryId` 不应并发运行两轮对话。

```java
agent.cancel("user-1");
agent.clearContext("user-1");
```

## 8. 常见问题

| 现象 | 排查方向 |
| --- | --- |
| `chatModel is null, need set` | 非流式执行必须设置 `chatModel` |
| `streamingChatModel is null, need set` | 流式执行必须设置 `streamingChatModel` |
| `toolService is null, need set` | 非流式执行必须设置 `toolService`，即使工具为空也建议传入空实例 |
| `ChatMemoryProvider is null, need set` | 设置 `MessageWindowChatMemoryProvider` 或其他自定义实现 |
| 模型返回 401 / 403 | 检查 `apiKey`、`baseUrl` 与模型服务权限 |
| 工具没有被调用 | 检查 `@Tool` 方法是否已注册，工具描述是否足够明确 |

下一步：阅读 [Agent 智能体（流式、非流式）](../sdk/agent) 了解 ReAct 循环与状态管理。

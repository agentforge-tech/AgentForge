---
title: Quick Start
sidebar_label: Quick Start
description: From Maven dependencies and model configuration to your first ReAct Agent
sidebar_position: 2
displayed_sidebar: sdkSidebar
---

# Quick Start

This guide covers the shortest path to three outcomes: add dependencies, call a model, and build a ReAct Agent that can call tools. All examples target **AgentForge 1.0.0**.

## 1. Requirements

- JDK 8 or JDK 17 (JDK 17 recommended; public APIs remain Java 8 bytecode compatible)
- Maven 3.8+
- A model API key (OpenAI, Anthropic, or an OpenAI-compatible service)

## 2. Add the latest dependencies

The BOM is the recommended way to manage versions:

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
    <!-- Agent runtime -->
    <dependency>
        <groupId>cloud.changlu.agentforge</groupId>
        <artifactId>agentforge-agent-core</artifactId>
    </dependency>

    <!-- OpenAI / OpenAI-compatible Provider -->
    <dependency>
        <groupId>cloud.changlu.agentforge</groupId>
        <artifactId>agentforge-model-openai</artifactId>
    </dependency>

    <!-- Add this only if you need Anthropic -->
    <dependency>
        <groupId>cloud.changlu.agentforge</groupId>
        <artifactId>agentforge-model-anthropic</artifactId>
    </dependency>
</dependencies>
```

If you only want to try a model call, a single Provider is enough:

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-model-openai</artifactId>
    <version>1.0.0</version>
</dependency>
```

## 3. Call your first model

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

        String answer = model.chat("Explain ReAct Agent in one sentence.");
        System.out.println(answer);
    }
}
```

The default `baseUrl` is `https://api.openai.com/v1`. For DashScope, Ollama, Xinference, or other OpenAI-compatible services, change `baseUrl` and `modelName`:

```java
ChatModel model = OpenAiChatModel.builder()
        .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-plus")
        .build();
```

## 4. Define a local tool

```java
import cloud.changlu.agentforge.model.tool.P;
import cloud.changlu.agentforge.model.tool.Tool;

public class WeatherTools {

    @Tool("Get the current weather for a city")
    public String getWeather(
            @P(name = "city", description = "City name, for example: Beijing") String city) {
        return "{\"city\":\"" + city + "\",\"weather\":\"Sunny\",\"temperature\":\"26C\"}";
    }
}
```

The return value of a `@Tool` method is converted into a tool result and written back to conversation memory. See [Tool Extensions](../sdk/tool) for the full rules.

## 5. Build an Agent

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
                .description("ReAct Agent for weather queries")
                .systemPrompt("You are a weather assistant. Call tools when needed and never invent weather data.")
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
                .question("What is the weather in Beijing today?")
                .build());

        System.out.println(result.getRes());
        System.out.println(result.getRunState().getDescription());
    }
}
```

## 6. Switch to streaming

The same Agent instance and the same `memoryId` can switch to streaming:

```java
agent.runStream(AgentRequest.builder()
                .memoryId("user-1")
                .question("What about Shanghai?")
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

`runStream()` returns a lazy handle: the model request starts only after callbacks are registered and `start()` is called.

## 7. Sessions, cancellation, and cleanup

- **Multi-turn sessions**: reuse the same `memoryId`; the Agent reads and writes context through `ChatMemoryProvider`;
- **Cancel**: call `agent.cancel(memoryId)`; the Agent exits cooperatively before the next think or model call;
- **Clear context**: call `agent.clearContext(memoryId)` to clear session memory;
- **Concurrency**: do not run two turns concurrently for the same `memoryId`.

```java
agent.cancel("user-1");
agent.clearContext("user-1");
```

## 8. Troubleshooting

| Symptom | What to check |
| --- | --- |
| `chatModel is null, need set` | Non-streaming execution requires `chatModel` |
| `streamingChatModel is null, need set` | Streaming execution requires `streamingChatModel` |
| `toolService is null, need set` | Non-streaming execution requires `toolService`; pass an empty instance if needed |
| `ChatMemoryProvider is null, need set` | Set `MessageWindowChatMemoryProvider` or a custom implementation |
| Model returns 401 / 403 | Check `apiKey`, `baseUrl`, and model permissions |
| Tools are not called | Check that `@Tool` methods are registered and descriptions are clear |

Next: read [Agent (Streaming & Non-streaming)](../sdk/agent) to learn about the ReAct loop and state management.

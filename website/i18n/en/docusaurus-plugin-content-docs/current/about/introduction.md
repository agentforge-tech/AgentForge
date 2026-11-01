---
title: Meet AgentForge
sidebar_label: Meet AgentForge
description: AgentForge positioning, layered design, core modules, and the shortest path to get started
sidebar_position: 1
displayed_sidebar: sdkSidebar
---

# Meet AgentForge

AgentForge is an open-source Agent Framework for Java developers. It starts from **LLM protocols and model abstractions**, then builds tool calling, conversation memory, middleware, reasoning loops, and the Agent Runtime layer by layer. Applications can turn model capabilities into executable tasks without binding themselves to a single model vendor.

> **Forge Intelligence into Action.**

`AgentForge = Agent + Forge`: `Agent` represents an intelligent entity that understands goals, reasons, calls tools, and completes tasks. `Forge` emphasizes engineering, composition, and reinforcement that turn raw model intelligence into reliable application capabilities.

## Latest dependency

The current framework version is **1.0.0**. The BOM is the recommended way to manage versions:

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

Then add modules as needed, without repeating the version:

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-agent-core</artifactId>
</dependency>
```

To run a quick example, `agentforge-agent-core` alone is enough: it transitively brings `agentforge-model-api` and `agentforge-model-core`.

## Why bottom-up

Agent memory, tools, planning, and execution loops all eventually become model requests and responses. AgentForge stabilizes contracts such as `ChatModel`, `StreamingChatModel`, `ChatMessage`, and `ChatResponse` first, then composes Agent capabilities on top. Business code therefore does not depend tightly on any vendor SDK.

```text
LLM Provider
    ↓  Message / Request / Response
    ↓  Context / Memory
    ↓  Tool / Skill / MCP
    ↓  Reasoning / Planning
    ↓  Agent Runtime
    ↓  Multi-Agent / Sandbox / Observability
Real Action
```

This layering brings four direct benefits:

1. **Provider-neutral**: upper layers depend only on unified contracts; switching models does not require rewriting the main loop;
2. **Protocol adapters live low**: OpenAI / Anthropic differences in fields, headers, SSE, and finish reasons stay inside Provider modules;
3. **Independently replaceable**: models, transports, tools, memory, and middleware can all be replaced as needed;
4. **Ready to use**: embed the SDK in a Java application, or start the complete workbench with AgentForge Service.

## Project structure

```text
AgentForge
├── agentforge-ai-parent          # Dependency and plugin version management
├── agentforge-ai-bom             # Unified BOM
├── agentforge-model              # Model layer
│   ├── agentforge-model-api      # Provider-neutral model contracts
│   ├── agentforge-model-core     # Default implementations, HTTP transport, Tool engine
│   ├── agentforge-model-openai   # OpenAI / OpenAI-compatible Provider
│   ├── agentforge-model-anthropic# Anthropic Provider
│   └── agentforge-model-registry # Out-of-the-box ChatModel factory
├── agentforge-framework
│   ├── agentforge-agent-core     # ReAct Agent runtime
│   └── agentforge-harness-agent  # Harness runtime (planned)
├── agentforge-service            # Vue frontend + Spring Boot backend
└── agentforge-examples           # Reference examples
```

## Core capabilities

| Capability | Description | Entry |
| --- | --- | --- |
| Unified model protocol | One `ChatRequest` / `ChatResponse` for OpenAI, Anthropic, and OpenAI-compatible services | [Model](../sdk/model) |
| ReAct Agent | Synchronous and streaming share one think → act → memory loop | [Agent](../sdk/agent) |
| Messages and events | Unified messages, streaming callbacks, and tool execution events | [Messages & Events](../sdk/message-event) |
| Middleware | Attach custom logic to model calls, tool execution, and loop lifecycle | [Middleware](../sdk/middleware) |
| Tool extensions | Local Java methods, HTTP plugins, and MCP services as Function Calls | [Tool Extensions](../sdk/tool) |
| Service workbench | Model configuration, Agent debugging, versioning, sessions, and tracing | [Service](../service/introduction) |

## A minimal call

```java
ChatModel model = OpenAiChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o-mini")
        .build();

String answer = model.chat("Explain ReAct Agent in one sentence.");
System.out.println(answer);
```

Add `ReActAgent` when you need tools and multi-step reasoning:

```java
ToolService toolService = new ToolService();
toolService.tools(new WeatherTools());

ReActAgent agent = ReActAgent.builder()
        .agentName("weather-agent")
        .systemPrompt("You are a weather assistant. Call tools when needed.")
        .chatModel(chatModel)
        .streamingChatModel(streamingChatModel)
        .chatMemoryProvider(new MessageWindowChatMemoryProvider(50))
        .toolService(toolService)
        .build();

ChatResult result = agent.run(AgentRequest.builder()
        .memoryId("user-1")
        .question("What is the weather in Beijing today?")
        .build());
```

## Choose your path

- **New to AgentForge**: run your first Agent from [Quick Start](../quickstart/getting-started);
- **Understand the model layer**: read [Model](../sdk/model);
- **Build a full Agent**: read [Agent](../sdk/agent) and [Tool Extensions](../sdk/tool);
- **Add observability and governance**: read [Middleware](../sdk/middleware) and [Messages & Events](../sdk/message-event);
- **Use the ready-made workbench**: visit [AgentForge Service](../service/introduction).

## Service workbench

AgentForge Service is an out-of-the-box workbench built on the framework. It covers the full path from model configuration to production release:

```text
Model configuration → Agent configuration → Debug → Version → Publish → Chat and session history
```

It is a good fit for teams that want to experience AgentForge quickly without building a frontend and backend from scratch. See [Service introduction](../service/introduction).

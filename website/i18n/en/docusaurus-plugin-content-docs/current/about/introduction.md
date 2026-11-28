---
title: Meet AgentForge
sidebar_label: Meet AgentForge
description: AgentForge positioning, layered design, core modules, and the shortest path to get started
sidebar_position: 1
displayed_sidebar: sdkSidebar
---

# Meet AgentForge

AgentForge is an open-source Agent Framework for Java developers. It starts from **LLM protocols and model abstractions**, then builds tool calling, conversation memory, middleware, reasoning loops, HITL interrupts, and the Agent Runtime layer by layer. Applications can turn model capabilities into executable tasks without binding themselves to a single model vendor.

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
            <version>1.0.2-SNAPSHOT</version>
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
│   └── agentforge-harness-agent  # Harness runtime (initial local runtime)
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
| Human-in-the-Loop | Permission `ALLOW / DENY / ASK`, approval interrupts, AskUser and batch recovery | [Permission Design & HITL](../sdk/permission-hitl) |
| Harness Agent | Initial local runtime: Workspace, LocalRuntime, engineering tools and resource lifecycle | [Build a HarnessAgent](../harness/quickstart) |
| Service workbench | Model configuration, Agent debugging, versioning, sessions, and tracing | [Service](../service/introduction) |

HITL is now initially available: permission approval and AskUser share one interrupt kernel with frozen tool batches, `SUSPENDED` outcomes, and explicit recovery. Harness Agent provides an initial local runtime that binds a Workspace and `LocalRuntime` with file, search, and command tools. Remote sandboxes, cross-process recovery, and Harness-level permission policy selection are still evolving.

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
- **Add permission approval and human confirmation**: read [Permission Design & HITL](../sdk/permission-hitl);
- **Run an engineering Agent with workspace boundaries**: read [Build a HarnessAgent](../harness/quickstart);
- **Use the ready-made workbench**: visit [AgentForge Service](../service/introduction).

## SDK documentation map

The SDK section is organized as "use first, understand next, extend last":

```text
Getting started
  Meet AgentForge → Quick Start
Harness implementation
  Build a HarnessAgent → Runtime design and implementation
Core components
  Model → Agent → Permission Design & HITL → Messages & Events → Middleware → Tool → Memory
Memory extensions
  Window memory
Agent Tool extensions
  JavaLocalTool → HTTP → MCP
Middleware extensions
  Logging → Trace → Append
```

Tool and Middleware topic pages follow a three-layer structure: design rationale first, then how to compose them with an Agent, and finally how to replace storage, executors, event adapters, or formatting strategies. This lets you copy a minimal example first and extend it as your project grows.

## Service workbench

AgentForge Service is an out-of-the-box workbench built on the framework. It covers the full path from model configuration to production release:

```text
Model configuration → Agent configuration → Debug → Version → Publish → Chat and session history
```

It is a good fit for teams that want to experience AgentForge quickly without building a frontend and backend from scratch. See [Service introduction](../service/introduction).

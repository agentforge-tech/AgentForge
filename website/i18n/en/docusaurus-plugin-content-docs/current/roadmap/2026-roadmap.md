---
id: roadmap
title: 2026 AgentForge RoadMap
sidebar_label: 2026 RoadMap
description: AgentForge 2026 technical roadmap and core capability plan
toc_max_heading_level: 4
displayed_sidebar: roadmapSidebar
---

# 2026 AgentForge RoadMap

AgentForge builds its Java Agent stack bottom-up: stabilize the model protocol and core contracts first, then extend the ReAct Agent, Harness Runtime, and different Agent application forms.

Current framework version: **1.0.0**. All reference links in this article open in a new window.

## Core project structure

```text
AgentForge
├── agentforge-ai-parent           # Dependency and plugin version management
├── agentforge-ai-bom              # Unified BOM
├── agentforge-model               # Model layer
│   ├── agentforge-model-api       # Provider-neutral contracts and unified req/vo
│   ├── agentforge-model-core      # Vendor-free default implementations and execution engine
│   ├── agentforge-model-openai    # OpenAI / OpenAI-compatible Provider
│   ├── agentforge-model-anthropic # Anthropic Provider
│   └── agentforge-model-registry  # Out-of-the-box ChatModel factory
├── agentforge-framework
│   ├── agentforge-agent-core      # ReAct Agent runtime
│   └── agentforge-harness-agent   # Harness runtime (permissions / approval / sandbox / resumable execution)
├── agentforge-service             # Out-of-the-box service (Vue frontend + Spring Boot backend)
│   ├── agentforge-service-ui      # Vue 2 + Vite frontend
│   └── agentforge-service-web     # Spring Boot backend
├── agentforge-examples
├── pom.xml
└── README.md
```

The project has two core parts:

1. **Agent framework layer**: ReAct core, tracing, token accounting, Agent Team, and other foundational components;
2. **Agent standard Service layer**: an extensible Agent workbench that turns framework capabilities into configurable, debuggable, publishable product features.

## Model protocol layer

### LLM Model

- [x] OpenAI protocol: <a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/01%E3%80%81model%E6%A8%A1%E5%9E%8B%E5%8D%8F%E8%AE%AE%E5%B1%82/chatmodel/openai/OpenAI%E5%8D%8F%E8%AE%AE02%E3%80%81AgentForge%20%E9%80%82%E9%85%8DOpenAI%E6%8E%A5%E5%85%A5%E6%A0%B8%E5%BF%83%E5%AE%9E%E8%B7%B5.md" target="_blank" rel="noopener noreferrer">OpenAI Protocol 02: AgentForge OpenAI Integration in Practice</a>
- [x] Anthropic protocol: <a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/01%E3%80%81model%E6%A8%A1%E5%9E%8B%E5%8D%8F%E8%AE%AE%E5%B1%82/chatmodel/anthropic/Anthropic%E5%8D%8F%E8%AE%AE02%E3%80%81AgentForge%20%E9%80%82%E9%85%8DAnthropic%E6%8E%A5%E5%85%A5%E6%A0%B8%E5%BF%83%E5%AE%9E%E8%B7%B5.md" target="_blank" rel="noopener noreferrer">Anthropic Protocol 02: AgentForge Anthropic Integration in Practice</a>
- [ ] Additional Providers

### Protocol abstractions

- [x] Function Calling and executor abstraction: <a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/01%E3%80%81model%E6%A8%A1%E5%9E%8B%E5%8D%8F%E8%AE%AE%E5%B1%82/chatmodel/%E7%BB%9F%E4%B8%80ChatModel%E5%8D%8F%E8%AE%AE%E5%B1%8202%E3%80%81Function%20Calling%20%E5%B7%A5%E5%85%B7%E8%B0%83%E7%94%A8%E8%BD%AC%E6%8D%A2%E4%B8%8E%E6%89%A7%E8%A1%8C%E6%89%A9%E5%B1%95%E5%AE%9E%E7%8E%B0%EF%BC%9ATool%20%E5%A5%91%E7%BA%A6%20%2B%20ToolService%20%E6%89%A7%E8%A1%8C%E5%BE%AA%E7%8E%AF.md" target="_blank" rel="noopener noreferrer">Unified ChatModel Protocol 02: Function Calling conversion and ToolService execution loop</a>

### Embedding Model

- [ ] OpenAI Embedding protocol
- [ ] Vector retrieval and long-term memory integration

## Agent framework layer

### Core ReAct components

> **Core code**:
>
> ReAct Agent: <a href="https://github.com/agentforge-tech/AgentForge/tree/master/agentforge-framework/agentforge-agent-core" target="_blank" rel="noopener noreferrer">agentforge-agent-core</a>, package `cloud.changlu.agentforge.agent.ReActAgent`
>
> Middleware design: <a href="https://github.com/agentforge-tech/AgentForge/tree/master/agentforge-framework/agentforge-agent-core" target="_blank" rel="noopener noreferrer">agentforge-agent-core</a>, package `cloud.changlu.agentforge.agent.component.middleware`

- [x] Core ReAct framework with one call path for streaming and non-streaming
  - `ReActAgent` drives the synchronous main loop, `stream.ReActTokenStream` drives the streaming main loop, and both share think → act → memory write-back semantics, interruption, and step limits
- [x] Middleware extensions
  - `IAgentMiddleware` defines loop, AI retry, and tool execution callbacks; `IStreamingIAgentMiddleware` carries streaming events; `AgentMiddlewareManager` handles registration, ordering, and dispatch
- [x] Standard Agent session memory design and implementation: <a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/02%E3%80%81%E9%80%9A%E7%94%A8ReActagent%E5%B1%82/%E4%BC%9A%E8%AF%9D%E8%AE%B0%E5%BF%86/Agent%E4%BC%9A%E8%AF%9D%E8%AE%B0%E5%BF%86%E6%8E%A5%E5%8F%A3%E8%AE%BE%E8%AE%A1%E4%B8%8E%E5%AE%9E%E7%8E%B0.md" target="_blank" rel="noopener noreferrer">Agent session memory interface design and implementation</a>

### External protocols to Function Calls

> **Core code**: the `cloud.changlu.agentforge.agent.tool` package in <a href="https://github.com/agentforge-tech/AgentForge/tree/master/agentforge-framework/agentforge-agent-core" target="_blank" rel="noopener noreferrer">agentforge-agent-core</a>

- [x] Local Tool (Java code) conversion: <a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/02%E3%80%81%E9%80%9A%E7%94%A8ReActagent%E5%B1%82/functioncall%E6%A0%B8%E5%BF%83%E6%89%A9%E5%B1%95%E4%B8%9A%E5%8A%A1tool/local/Java%E4%BB%A3%E7%A0%81%E8%BD%AC%E6%8D%A2FunctionCall%E5%8D%8F%E8%AE%AE01%E3%80%81AgentForge%20Local%E5%B7%A5%E5%85%B7%E6%A8%A1%E5%9D%97%E6%A0%B8%E5%BF%83%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86.md" target="_blank" rel="noopener noreferrer">Java code to FunctionCall 01: AgentForge Local tool module design</a>
  - `LocalToolFactory` maps local methods to `ToolSpecification`, and `LocalToolExecutor` handles argument conversion and reflective invocation
- [x] HTTP external API conversion: <a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/02%E3%80%81%E9%80%9A%E7%94%A8ReActagent%E5%B1%82/functioncall%E6%A0%B8%E5%BF%83%E6%89%A9%E5%B1%95%E4%B8%9A%E5%8A%A1tool/http/%E9%80%82%E9%85%8DHTTP%E5%8D%8F%E8%AE%AE%E8%BD%AC%E6%8D%A2FunctionCall%E5%8D%8F%E8%AE%AE01%E3%80%81AgentForge%20HTTP%E5%B7%A5%E5%85%B7%E6%A8%A1%E5%9D%97%E6%A0%B8%E5%BF%83%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86.md" target="_blank" rel="noopener noreferrer">HTTP protocol to FunctionCall 01: AgentForge HTTP tool module design</a>
  - `HttpToolFactory` converts HTTP plugin definitions into tool descriptions, `HttpToolExecutor` executes requests, and cURL ↔ HttpPlugin conversion is supported
- [x] MCP service protocol conversion: <a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/02%E3%80%81%E9%80%9A%E7%94%A8ReActagent%E5%B1%82/functioncall%E6%A0%B8%E5%BF%83%E6%89%A9%E5%B1%95%E4%B8%9A%E5%8A%A1tool/mcp/MCP%E5%8D%8F%E8%AE%AE%E8%BD%AC%E6%8D%A2FunctionCall%E5%8D%8F%E8%AE%AE02%E3%80%81MCP%E6%A1%86%E6%9E%B6%E8%B0%83%E7%A0%94%E4%B8%8EAgentForge%E6%8E%A5%E5%85%A5%E5%AE%9E%E7%8E%B0.md" target="_blank" rel="noopener noreferrer">MCP protocol to FunctionCall 02: MCP framework research and AgentForge integration</a>
  - `McpToolFactory` discovers MCP tools and maps them to `ToolSpecification`; `McpToolExecutor` calls tools over JSON-RPC; transports support stdio and streamable-http
- [ ] Additional tool protocols

**Optimization:**

- [x] Unified Function Call standard and `__thought` injection for plugin tools: <a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/02%E3%80%81%E9%80%9A%E7%94%A8ReActagent%E5%B1%82/functioncall%E6%A0%B8%E5%BF%83%E6%89%A9%E5%B1%95%E4%B8%9A%E5%8A%A1tool/%E6%8F%92%E4%BB%B6%E5%B7%A5%E5%85%B7%E7%BB%9F%E4%B8%80FunctionCall%E6%A0%87%E5%87%86%E4%B8%8E%E6%80%9D%E8%80%83%E5%AD%97%E6%AE%B5%E6%B3%A8%E5%85%A5%E8%AE%BE%E8%AE%A1.md" target="_blank" rel="noopener noreferrer">Unified FunctionCall standard and thought field injection</a>

### External interruption of tool calls

- [ ] Interruption events: externally construct and return tool results (for example, an `askUser` tool)
- [ ] Approval confirmation: confirm or reject tool execution (for example, `rm` approval, see HITL)

### Session memory extensions

- [x] Window conversation memory: <a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/02%E3%80%81%E9%80%9A%E7%94%A8ReActagent%E5%B1%82/%E4%BC%9A%E8%AF%9D%E8%AE%B0%E5%BF%86/%E4%BC%9A%E8%AF%9D%E8%AE%B0%E5%BF%86%E6%89%A9%E5%B1%95-%E7%AA%97%E5%8F%A3%E4%BC%9A%E8%AF%9D%E8%AE%B0%E5%BF%86%E8%AE%BE%E8%AE%A1%E4%B8%8E%E5%AE%9E%E7%8E%B0.md" target="_blank" rel="noopener noreferrer">Window conversation memory design and implementation</a>

### Middleware plugins

- [x] Logging Middleware (for quick local debugging): <a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/02%E3%80%81%E9%80%9A%E7%94%A8ReActagent%E5%B1%82/middleware/%E5%86%85%E7%BD%AEmiddleware%E6%89%A9%E5%B1%95%EF%BC%9A%E6%97%A5%E5%BF%97Loggingmiddlware%E5%AE%9E%E7%8E%B0.md" target="_blank" rel="noopener noreferrer">Built-in middleware: Logging middleware implementation</a>
  - `LoggingIAgentMiddleware` logs loop, model call, and tool execution checkpoints, truncates long content, and aggregates model time, tool time, and token usage by memoryId
- [x] Agent Trace Middleware (Agent Loop monitoring with token usage): <a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/02%E3%80%81%E9%80%9A%E7%94%A8ReActagent%E5%B1%82/middleware/%E5%86%85%E7%BD%AEmiddlware%E6%89%A9%E5%B1%95%EF%BC%9AAgent%20Loop%20TraceMiddlware%E5%AE%9E%E7%8E%B0.md" target="_blank" rel="noopener noreferrer">Built-in middleware: Agent Loop Trace middleware implementation</a>
- [ ] Context compression Middleware (strategy-based compression)

### Agent Loop stability

- [ ] Dynamic model retry for protocol conversations (differentiating scenarios)
- [ ] Unified retry strategy for streaming

## Agent Harness

### Sandbox runtime

- [ ] Docker remote sandbox protocol (local and external)
- [ ] E2B sandbox environment
- [ ] Kubernetes container sandbox environment

### Human in the Loop

- [ ] Agent approval permission design
- [ ] Standard HITL approval and confirmation (tool permission interception, externally injected permission rules)

## AgentForge Service

### Resource components

#### Model configuration

- [x] Core model unified protocol design: <a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/01%E3%80%81model%E6%A8%A1%E5%9E%8B%E5%8D%8F%E8%AE%AE%E5%B1%82/chatmodel/%E7%BB%9F%E4%B8%80ChatModel%E5%8D%8F%E8%AE%AE%E5%B1%8201%E3%80%81ChatModel%20%E6%A0%B8%E5%BF%83%E5%8D%8F%E8%AE%AE%E5%B1%82%E8%AE%BE%E8%AE%A1%EF%BC%9A%E7%BB%9F%E4%B8%80%20API%20%E5%A5%91%E7%BA%A6%E5%B0%81%E8%A3%85%E4%B8%8E%E5%A4%9A%E5%8D%8F%E8%AE%AE%E6%89%A9%E5%B1%95%E5%AE%9E%E7%8E%B0.md" target="_blank" rel="noopener noreferrer">Unified ChatModel Protocol 01: unified API contract and multi-protocol extensions</a>

#### HTTP plugin tools

- [x] HTTP plugin design: <a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/agentforge-service%E6%A0%B8%E5%BF%83%E4%B8%9A%E5%8A%A1%E6%A8%A1%E5%9D%97/%E5%90%8E%E7%AB%AF/%E6%88%91%E7%9A%84%E8%B5%84%E6%BA%90/http%E6%8F%92%E4%BB%B6/AgentForge%20Service%20%E6%8E%A5%E5%85%A5%20HTTP%20%E6%8F%92%E4%BB%B6%E8%AE%BE%E8%AE%A1%E6%96%B9%E6%A1%88.md" target="_blank" rel="noopener noreferrer">AgentForge Service HTTP plugin design</a>

#### MCP plugin service

- [x] MCP plugin design: <a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/agentforge-service%E6%A0%B8%E5%BF%83%E4%B8%9A%E5%8A%A1%E6%A8%A1%E5%9D%97/%E5%90%8E%E7%AB%AF/%E6%88%91%E7%9A%84%E8%B5%84%E6%BA%90/MCP%E6%8F%92%E4%BB%B6/AgentForge%20MCP%20%E6%96%B9%E6%A1%88%E8%AE%BE%E8%AE%A1%E4%B8%8E%E5%AE%9E%E7%8E%B0%E8%AF%B4%E6%98%8E.md" target="_blank" rel="noopener noreferrer">AgentForge MCP design and implementation</a>

### Agent core implementation

#### Agent assembly

##### 1) General Agent configuration

- [x] General Agent assembly design (model configuration, HTTP, and MCP plugins): <a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/agentforge-service%E6%A0%B8%E5%BF%83%E4%B8%9A%E5%8A%A1%E6%A8%A1%E5%9D%97/%E5%90%8E%E7%AB%AF/agent%E6%A0%B8%E5%BF%83%E8%AE%BE%E8%AE%A1%E5%AE%9E%E7%8E%B0/agent%E6%9E%84%E5%BB%BA/Agent%E7%B1%BB%E5%9E%8B/%E9%80%9A%E7%94%A8agent/%E6%A0%B8%E5%BF%83%E5%9F%BA%E7%A1%80%E9%80%9A%E7%94%A8Agent%E8%A3%85%E9%85%8D%E8%AE%BE%E8%AE%A1%EF%BC%8C%E5%8C%85%E5%90%AB%E9%85%8D%E7%BD%AE%E6%A8%A1%E5%9E%8B%E3%80%81%E5%AF%B9%E6%8E%A5http%E3%80%81mcp%E6%8F%92%E4%BB%B6%E5%B7%A5%E5%85%B7.md" target="_blank" rel="noopener noreferrer">General Agent assembly design with models, HTTP, and MCP tools</a>
- [ ] Sandbox Coding Agent configuration and extensions

##### 2) Agent core components

- [x] MySQL-backed window memory persistence: <a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/agentforge-service%E6%A0%B8%E5%BF%83%E4%B8%9A%E5%8A%A1%E6%A8%A1%E5%9D%97/%E5%90%8E%E7%AB%AF/agent%E6%A0%B8%E5%BF%83%E8%AE%BE%E8%AE%A1%E5%AE%9E%E7%8E%B0/agent%E6%9E%84%E5%BB%BA/Agent%E8%AE%B0%E5%BF%86/%E5%9F%BA%E4%BA%8EMySQL%E5%AE%9E%E7%8E%B0Agent%E7%9A%84%E7%AA%97%E5%8F%A3%E4%BC%9A%E8%AF%9D%E8%AE%B0%E5%BF%86%E4%B8%8A%E4%B8%8B%E6%96%87%E6%8C%81%E4%B9%85%E5%8C%96.md" target="_blank" rel="noopener noreferrer">MySQL-backed Agent window memory persistence</a>

#### Agent session interaction

- [x] Standard Agent session and message table design: <a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/agentforge-service%E6%A0%B8%E5%BF%83%E4%B8%9A%E5%8A%A1%E6%A8%A1%E5%9D%97/%E5%90%8E%E7%AB%AF/agent%E6%A0%B8%E5%BF%83%E8%AE%BE%E8%AE%A1%E5%AE%9E%E7%8E%B0/Agent%E9%A1%B5%E9%9D%A2%E4%BC%9A%E8%AF%9D%E5%AF%B9%E8%AF%9D%E4%BA%A4%E4%BA%92/AgentForge%E6%A0%87%E5%87%86Agent%E4%BC%9A%E8%AF%9D%E4%B8%8E%E6%B6%88%E6%81%AF%E6%A0%B8%E5%BF%83%E8%A1%A8%E8%AE%BE%E8%AE%A1.md" target="_blank" rel="noopener noreferrer">Standard Agent session and message table design</a>
- [x] Core standard Agent event protocol: <a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/agentforge-service%E6%A0%B8%E5%BF%83%E4%B8%9A%E5%8A%A1%E6%A8%A1%E5%9D%97/%E5%90%8E%E7%AB%AF/agent%E6%A0%B8%E5%BF%83%E8%AE%BE%E8%AE%A1%E5%AE%9E%E7%8E%B0/Agent%E9%A1%B5%E9%9D%A2%E4%BC%9A%E8%AF%9D%E5%AF%B9%E8%AF%9D%E4%BA%A4%E4%BA%92/agentforge-service%E6%A0%B8%E5%BF%83%E4%BA%8B%E4%BB%B6%E5%8D%8F%E8%AE%AE%E8%AE%BE%E8%AE%A1%E5%AE%9A%E4%B9%89.md" target="_blank" rel="noopener noreferrer">AgentForge Service core event protocol</a>
- [x] Tool-call thought parameters and frontend interaction: <a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/agentforge-service%E6%A0%B8%E5%BF%83%E4%B8%9A%E5%8A%A1%E6%A8%A1%E5%9D%97/%E5%90%8E%E7%AB%AF/agent%E6%A0%B8%E5%BF%83%E8%AE%BE%E8%AE%A1%E5%AE%9E%E7%8E%B0/Agent%E9%A1%B5%E9%9D%A2%E4%BC%9A%E8%AF%9D%E5%AF%B9%E8%AF%9D%E4%BA%A4%E4%BA%92/Agent%E5%B7%A5%E5%85%B7%E8%B0%83%E7%94%A8%E6%80%9D%E8%80%83%E5%8F%82%E6%95%B0%E4%B8%8E%E5%89%8D%E7%AB%AF%E4%BC%98%E9%9B%85%E4%BA%A4%E4%BA%92%E8%AE%BE%E8%AE%A1.md" target="_blank" rel="noopener noreferrer">Tool-call thought parameters and frontend interaction design</a>
- [ ] Additional events and inserted Agent Loop conversations

#### Versioning and publishing

- [x] Agent versioning and publishing design: <a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/agentforge-service%E6%A0%B8%E5%BF%83%E4%B8%9A%E5%8A%A1%E6%A8%A1%E5%9D%97/%E5%90%8E%E7%AB%AF/agent%E6%A0%B8%E5%BF%83%E8%AE%BE%E8%AE%A1%E5%AE%9E%E7%8E%B0/Agent%E7%89%88%E6%9C%AC%E6%8E%A7%E5%88%B6%E4%B8%8E%E5%8F%91%E5%B8%83/Agent%E7%89%88%E6%9C%AC%E8%AE%BE%E8%AE%A1%E4%B8%8EAgent%E5%8F%91%E5%B8%83%E6%96%B9%E6%A1%88.md" target="_blank" rel="noopener noreferrer">Agent version design and publish plan</a>
- [x] Agent debug and publish conversation design: <a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/agentforge-service%E6%A0%B8%E5%BF%83%E4%B8%9A%E5%8A%A1%E6%A8%A1%E5%9D%97/%E5%90%8E%E7%AB%AF/agent%E6%A0%B8%E5%BF%83%E8%AE%BE%E8%AE%A1%E5%AE%9E%E7%8E%B0/Agent%E7%89%88%E6%9C%AC%E6%8E%A7%E5%88%B6%E4%B8%8E%E5%8F%91%E5%B8%83/%E6%A0%B8%E5%BF%83Agent%E8%B0%83%E8%AF%95%E4%B8%8E%E5%8F%91%E5%B8%83%E5%AF%B9%E8%AF%9D%E8%AE%BE%E8%AE%A1%E4%B8%8E%E5%AE%9E%E7%8E%B0.md" target="_blank" rel="noopener noreferrer">Agent debug and publish conversation design</a>

## Agent infrastructure

### Agent Loop Trace

- [x] Agent Loop Trace business monitoring design: <a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/agentforge-service%E6%A0%B8%E5%BF%83%E4%B8%9A%E5%8A%A1%E6%A8%A1%E5%9D%97/%E5%90%8E%E7%AB%AF/agent%E5%9F%BA%E7%A1%80%E8%AE%BE%E6%96%BD/agent%E9%93%BE%E8%B7%AF%E8%BF%BD%E8%B8%AA/Agent%E9%93%BE%E8%B7%AF%E8%BF%BD%E8%B8%AA%E8%AE%BE%E8%AE%A1%E5%AE%9E%E7%8E%B0%EF%BC%88%E7%81%AB%E7%84%B0%E5%9B%BE%20%26%20%E6%B5%81%E7%A8%8B%E5%9B%BE%EF%BC%89.md" target="_blank" rel="noopener noreferrer">Agent tracing (flame graph &amp; flow chart)</a>; <a href="https://github.com/agentforge-tech/AgentForge/blob/master/docs/dev/backend/AgentForge%E6%A0%B8%E5%BF%83%E6%A8%A1%E5%9D%97%E8%AE%BE%E8%AE%A1%E5%8E%9F%E7%90%86%E6%96%87%E7%AB%A0%E7%B3%BB%E5%88%97/agentforge-service%E6%A0%B8%E5%BF%83%E4%B8%9A%E5%8A%A1%E6%A8%A1%E5%9D%97/%E5%89%8D%E7%AB%AF/agent%E5%9F%BA%E7%A1%80%E8%AE%BE%E6%96%BD/agent%E9%93%BE%E8%B7%AF%E8%BF%BD%E8%B8%AA/Agent%20Trace%E6%8E%A8%E7%90%86%E9%93%BE%E8%B7%AF%E7%94%BB%E5%B8%83%E7%BB%84%E4%BB%B6%E9%80%89%E5%9E%8B%E4%B8%8E%E8%AE%BE%E8%AE%A1%E6%96%B9%E6%A1%88.md" target="_blank" rel="noopener noreferrer">Agent Trace reasoning canvas component selection and design</a>

### Agent Data Monitor

- [ ] Unified Agent Data Monitor design and implementation

### Scheduled tasks

- [ ] Standard Agent scheduled task integration

### Channel bindings

- [ ] DingTalk
- [ ] Feishu Bot and other channels

## Future forms

```text
                         +--------------------------------------+
                         |          agentforge-core             |
                         |                                      |
                         |          LLM Model Protocol          |
                         |                  |                   |
                         |            ReAct Agent               |
                         |                  |                   |
                         |           Harness Agent              |
                         +------------------+-------------------+
                                            |
                          +-----------------+-----------------+
                          |        agentforge-service         |
                          +-----------------+-------------------+
                                            |
                  +-------------------------+-------------------------+
                  |                         |                         |
                  v                         v                         v
               Platform                    CLI                     Desktop
```

1. **Platform (AgentForge-Platform)**: multi-user, multi-tenant runtime that manages all Agents;
2. **CLI (AgentForge-CLI)**: local CLI Agent, comparable to opencode, Codex, and Claude Code;
3. **Desktop (AgentForge-Desktop)**: local workbench, comparable to WorkBuddy and Codex desktop;
4. **Harness Agent**: permissions, approvals, sandboxing, and resumable execution on top of ReAct.

## Get involved

- Feature ideas and design discussions: <a href="https://github.com/agentforge-tech/AgentForge/discussions" target="_blank" rel="noopener noreferrer">GitHub Discussions</a>
- Bugs and requests: <a href="https://github.com/agentforge-tech/AgentForge/issues" target="_blank" rel="noopener noreferrer">GitHub Issues</a>
- Contribute code: <a href="../quickstart/contributing" target="_blank" rel="noopener noreferrer">Contributing</a>

The roadmap is updated as implementation progresses. Checkbox status follows the latest repository code.

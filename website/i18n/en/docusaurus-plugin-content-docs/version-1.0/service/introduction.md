---
title: AgentForge Service
sidebar_label: Service Introduction
description: An out-of-the-box Agent workbench with model configuration, Agent debugging, versioning, sessions, and tracing
sidebar_position: 1
displayed_sidebar: serviceSidebar
---

# AgentForge Service

AgentForge Service is a standard Agent workbench built on the AgentForge framework. It covers the full path from model configuration to production release:

```text
Model configuration → Agent configuration → Debug → Version → Publish → Chat and session history
```

It is a good fit for teams that want to experience AgentForge quickly without building a frontend and backend from scratch, and for enterprises that need a starting point for internal Agent applications.

## What it solves

With the SDK alone, you need to handle:

- How to manage model keys and switch Providers;
- How to persist Agent configuration, version it, and publish it;
- How to store and replay chat sessions;
- How to push streaming events to the frontend;
- How to observe tool-call chains.

AgentForge Service packages these capabilities into a runnable product:

| Capability | Current implementation |
| --- | --- |
| Model management | OpenAI / Anthropic / OpenAI-compatible endpoint, API key, baseUrl, model name |
| Agent configuration | Prompt, chat model, window memory, and plugin references persisted together |
| Debug and preview | Debug uses the current draft; supports `debug` and `elegant` event density |
| Version history | Snapshots, notes, switching, and side-by-side comparison |
| Release snapshots | Published and draft configurations are isolated; publish/unpublish invalidates runtime caches |
| Sessions and replay | Session title, pinning, messages, and SSE event snapshots |
| ChatMemory | Pluggable `ChatMemoryStore`, SQLite by default, MySQL optional |
| HTTP plugins | Plugin / method / parameter model, curl and JSON import, online trial runs |
| MCP plugins | Server management, `mcpServers` import/export, tool discovery, multi-server aggregation |
| Streaming | Unified SSE events: `session` / `think` / `resp` / `tool_call` / `resp_end` / `error` |
| Tracing | Agent Loop Trace, flame graphs, and flow charts |

## Technical structure

```text
agentforge-service
├── agentforge-service-ui     # Vue 2 + Vite frontend
└── agentforge-service-web    # Spring Boot backend
    └── modules
        ├── model             # Model configuration
        ├── agent             # Agent config, versioning, publishing, runtime assembly
        ├── session           # Sessions, messages, SSE replay
        └── plugins           # HTTP / MCP plugins
```

The frontend provides workbench interactions and streaming Markdown rendering. The backend persists configuration, assembles Agents, pushes events, and handles tracing. Both share the same Agent runtime.

## When to use it

- You want to experience the full AgentForge capability quickly;
- You need a private-deployable Agent workbench;
- You want non-developers to maintain Agent configuration;
- You need debugging, versioning, publishing, sessions, and tracing in one place.

## Quick start

The fastest way is Docker one-click install:

```bash
mkdir -p agentforge && cd agentforge
curl -fsSL https://raw.githubusercontent.com/agentforge-tech/AgentForge/master/agentforge-service/docker/standalone/install.sh | bash
```

Open `http://127.0.0.1:17080` after startup.

For full steps, port changes, data directories, and upgrades, see [Service Quick Deploy](./quick-deploy).

## Relationship to the SDK

```text
AgentForge SDK
    ├── agentforge-model-*       Unified model protocol
    └── agentforge-agent-core    ReAct / Middleware / Tool
                │
                ▼
AgentForge Service
    ├── Configuration persistence
    ├── Agent assembly and runtime
    ├── SSE events and sessions
    └── Workbench UI
```

- Embed in your own Java application: use the [SDK docs](../about/introduction);
- Use the ready-made workbench: use AgentForge Service;
- Customize Service: build from source and reuse the same Agent runtime.
